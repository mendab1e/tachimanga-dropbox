package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SecurityTest {
    private fun zip(vararg names: String): RemoteZip {
        val bytes = archive(*names.map { it to byteArrayOf(1) }.toTypedArray(), stored = true)
        return RemoteZip(object : RangeReader {
            override val size = bytes.size.toLong()
            override fun read(start: Long, end: Long) = bytes.copyOfRange(start.toInt(), end.toInt() + 1)
        })
    }

    @Test fun rejectsOversizedFilenameBeforeDecodingIt() {
        assertEquals(1, zip("a".repeat(1020) + ".jpg").pages.size)
        assertFails { zip("a".repeat(1021) + ".jpg").pages }
        assertFails { zip("é".repeat(511) + ".jpg").pages }
    }

    @Test fun rejectsAggregateIndexMemoryEvenWithIndividuallyAllowedNames() {
        val names = Array(2200) { "a".repeat(990) + "$it.jpg" }
        assertFails { zip(*names).pages }
    }

    @Test fun cacheEvictsByMemoryAndResetsAccountingOnDisconnect() {
        val cache = LibraryCache()
        val names = Array(1800) { "a".repeat(990) + "$it.jpg" }
        val first = zip(*names)
        val second = zip(*names)
        val third = zip(*names)
        assertTrue(first.indexMemoryBytes * 3 > LibraryCache.MAX_ARCHIVE_MEMORY)
        cache.archive("first") { first }
        cache.archive("second") { second }
        // Touch the first index: the second is now the least recently used.
        assertSame(first, cache.archive("first") { error("Must reuse cached index") })
        cache.archive("third") { third }
        assertSame(first, cache.archive("first") { error("Most recently used index must remain") })
        var reloaded = false
        cache.archive("second") { reloaded = true; second }
        assertTrue(reloaded)
        cache.invalidate()
        assertSame(first, cache.archive("first") { first })
        assertSame(second, cache.archive("second") { second })
    }

    @Test fun limitsSimultaneousIndexLoadsAcrossDifferentArchives() {
        val cache = LibraryCache()
        val workers = Executors.newFixedThreadPool(4)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        try {
            val tasks = (1..4).map { key -> workers.submit<RemoteZip> {
                cache.archive("archive-$key") {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    started.countDown()
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        zip("page.jpg")
                    } finally { active.decrementAndGet() }
                }
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            tasks.forEach { assertEquals(1, it.get(5, TimeUnit.SECONDS).pages.size) }
            assertEquals(2, peak.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun malformedAndOversizedTokenResponsesNeverExposeSecretsOrChangeCredentials() {
        MockWebServer().use { server ->
            server.start()
            val values = mutableMapOf("app_key" to "dummy-key", "refresh_token" to "old-refresh", "expires_at" to "0")
            val api = api(server, values, OkHttpClient())
            val responses = listOf(
                """{"access_token":"DUMMY_ACCESS","refresh_token":"DUMMY_REFRESH"""",
                """{"access_token":"DUMMY_ACCESS","refresh_token":{"value":"DUMMY_REFRESH"}}""",
                """{"access_token":"DUMMY_ACCESS","expires_in":"DUMMY_REFRESH"}""",
                """{"access_token":"DUMMY_ACCESS","expires_in":9223372036854775807}""",
                """{"access_token":"DUMMY_ACCESS","padding":"${"x".repeat(65536)}DUMMY_REFRESH"}""",
            )
            responses.forEach { response ->
                server.enqueue(MockResponse().setBody(response))
                try {
                    api.metadata("id:book")
                    fail("Expected invalid token response")
                } catch (e: IOException) {
                    val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                    assertFalse(trace.contains("DUMMY_ACCESS"))
                    assertFalse(trace.contains("DUMMY_REFRESH"))
                    assertNull(e.cause)
                    assertEquals("old-refresh", values["refresh_token"])
                    assertNull(values["access_token"])
                }
            }
        }
    }

    @Test fun privateRequestsBypassHostObserversAndDoNotFollowTokenRedirects() {
        MockWebServer().use { server ->
            server.start()
            val observations = AtomicInteger()
            val host = OkHttpClient.Builder()
                .addInterceptor { chain -> observations.incrementAndGet(); chain.proceed(chain.request()) }
                .addNetworkInterceptor { chain -> observations.incrementAndGet(); chain.proceed(chain.request()) }
                .eventListenerFactory { observations.incrementAndGet(); EventListener.NONE }
                .cookieJar(object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> { observations.incrementAndGet(); return emptyList() }
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { observations.incrementAndGet() }
                }).build()
            val values = mutableMapOf("app_key" to "dummy-key", "refresh_token" to "old-refresh", "expires_at" to "0")
            val api = api(server, values, host)
            server.enqueue(MockResponse().setBody("""{"access_token":"DUMMY_ACCESS","expires_in":14400}"""))
            server.enqueue(MockResponse().setBody("""{"id":"id:book"}"""))
            assertEquals("id:book", api.metadata("id:book").id)
            assertEquals("/oauth2/token", server.takeRequest().path)
            assertEquals("Bearer DUMMY_ACCESS", server.takeRequest().getHeader("Authorization"))
            assertEquals(0, observations.get())
            values["expires_at"] = "0"
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/capture")))
            assertFails { api.metadata("id:book") }
            assertEquals("/oauth2/token", server.takeRequest().path)
            assertEquals(3, server.requestCount)
            assertEquals(0, observations.get())
            assertFalse(privateDropboxClient(host).followRedirects)
            assertNull(privateDropboxClient(host).cache)
            assertEquals(2, host.interceptors.size + host.networkInterceptors.size)
        }
    }

    private fun api(server: MockWebServer, stored: MutableMap<String, String>, client: OkHttpClient): DropboxApi = DropboxApi(
        client, object : Settings {
            override fun get(key: String) = stored[key].orEmpty()
            override fun put(values: Map<String, String>) { stored.putAll(values) }
        }, server.url("/2/").toString(), server.url("/2/").toString(), server.url("/oauth2/token").toString(),
    )

    private fun assertFails(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
}
