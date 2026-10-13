package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private class MemorySettings : Settings {
    val values = ConcurrentHashMap(mapOf("app_key" to "public-key", "access_token" to "old-token",
        "refresh_token" to "refresh-token", "expires_at" to (System.currentTimeMillis() + 3600000).toString()))
    override fun get(key: String) = values[key].orEmpty()
    override fun put(values: Map<String, String>) { this.values.putAll(values) }
}
class DropboxApiTest {
    private lateinit var server: MockWebServer
    private lateinit var settings: MemorySettings
    private lateinit var api: DropboxApi
    @Before fun start() {
        server = MockWebServer().apply { start() }
        settings = MemorySettings()
        api = DropboxApi(OkHttpClient(), settings, server.url("/2/").toString(), server.url("/2/").toString(), server.url("/oauth2/token").toString())
    }
    @After fun stop() { server.shutdown() }
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)
    private fun entry(id: String = "id:book", path: String = "/Manga/Book/1.cbz", size: Int = 100) =
        """{".tag":"file","id":"$id","name":"1.cbz","path_display":"$path","rev":"abc123","size":$size,"server_modified":"2026-10-13T10:00:00Z"}"""
    @Test fun followsEveryDropboxCursor() {
        server.enqueue(json("""{"entries":[${entry()}],"cursor":"c1","has_more":true}"""))
        server.enqueue(json("""{"entries":[${entry("id:book2")}],"cursor":"c2","has_more":false}"""))
        assertEquals(2, api.list("/Manga", true).size)
        val first = server.takeRequest()
        assertEquals("/2/files/list_folder", first.path)
        assertEquals("Bearer old-token", first.getHeader("Authorization"))
        assertTrue(Json.parseToJsonElement(first.body.readUtf8()).jsonObject["recursive"]!!.jsonPrimitive.boolean)
        val next = server.takeRequest()
        assertEquals("/2/files/list_folder/continue", next.path)
        assertEquals("c1", Json.parseToJsonElement(next.body.readUtf8()).jsonObject.text("cursor"))
    }
    @Test fun refreshesExpiredAccessToken() {
        settings.values["expires_at"] = "0"
        server.enqueue(json("""{"access_token":"renewed-token","expires_in":14400}"""))
        server.enqueue(json(entry()))
        assertEquals("id:book", api.metadata("id:book").id)
        val refresh = server.takeRequest()
        assertEquals("/oauth2/token", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("grant_type=refresh_token"))
        assertEquals("Bearer renewed-token", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun retries401OnceWithRenewedToken() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(json("""{"access_token":"renewed-token","expires_in":14400}"""))
        server.enqueue(json(entry()))
        assertEquals("id:book", api.metadata("id:book").id)
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/oauth2/token", server.takeRequest().path)
        assertEquals("Bearer renewed-token", server.takeRequest().getHeader("Authorization"))
    }
    @Test fun pkceCodeExchangeClearsSingleUseCode() {
        val url = api.authorizationUrl()
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("token_access_type=offline"))
        assertEquals(url, settings.get("authorization_url"))
        assertFalse(url.contains("client_secret"))
        assertTrue(settings.get("verifier").length in 43..128)
        settings.values["auth_code"] = "user-code"
        server.enqueue(json("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":14400}"""))
        server.enqueue(json(entry()))
        api.metadata("id:book")
        val exchange = server.takeRequest().body.readUtf8()
        assertTrue(exchange.contains("code_verifier="))
        assertTrue(exchange.contains("grant_type=authorization_code"))
        assertFalse(exchange.contains("client_secret"))
        assertEquals("", settings.get("auth_code"))
        assertEquals("", settings.get("verifier"))
        assertEquals("", settings.get("authorization_url"))
        assertEquals("new-refresh", settings.get("refresh_token"))
    }
    @Test fun bundledKeySupportsSignInAndTokenRenewalWithoutManualKey() {
        settings.values.clear()
        api = apiWithDefaultKey("bundled-key")
        val url = api.authorizationUrl().toHttpUrl()
        assertEquals("bundled-key", url.queryParameter("client_id"))
        api.updateSetting("auth_code", "user-code")
        server.enqueue(json("""{"access_token":"new-token","refresh_token":"new-refresh","expires_in":14400}"""))
        server.enqueue(json(entry()))
        api.metadata("id:book")
        assertTrue(server.takeRequest().body.readUtf8().contains("client_id=bundled-key"))
        assertEquals("Bearer new-token", server.takeRequest().getHeader("Authorization"))

        settings.values["expires_at"] = "0"
        server.enqueue(json("""{"access_token":"renewed-token","expires_in":14400}"""))
        server.enqueue(json(entry()))
        api.metadata("id:book")
        val refresh = server.takeRequest().body.readUtf8()
        assertTrue(refresh.contains("client_id=bundled-key"))
        assertTrue(refresh.contains("refresh_token=new-refresh"))
        assertEquals("Bearer renewed-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("", settings.get("app_key"))
    }
    @Test fun customKeyOverridesBundledKeyAndClearingItReturnsToDefault() {
        api = apiWithDefaultKey("bundled-key")
        assertTrue(api.authorizationUrl().contains("client_id=public-key"))
        api.updateSetting("app_key", "")
        assertDisconnected()
        assertTrue(api.authorizationUrl().contains("client_id=bundled-key"))
    }
    @Test fun savingSameEffectiveKeyPreservesTheConnection() {
        api = apiWithDefaultKey("public-key")
        api.updateSetting("app_key", "")
        assertEquals("", settings.get("app_key"))
        assertEquals("old-token", settings.get("access_token"))
        assertEquals("refresh-token", settings.get("refresh_token"))
        api.updateSetting("app_key", "replacement-key")
        assertDisconnected()
    }
    private fun apiWithDefaultKey(key: String) = DropboxApi(OkHttpClient(), settings,
        server.url("/2/").toString(), server.url("/2/").toString(), server.url("/oauth2/token").toString(), key)
    @Test fun sourceBrowserReusesPendingPkceLinkAcrossReloadsAndCodeEntry() {
        settings.values.clear()
        api = apiWithDefaultKey("bundled-key")
        val link = api.browserUrl()
        val verifier = settings.get("verifier")
        assertEquals("bundled-key", link.toHttpUrl().queryParameter("client_id"))
        assertEquals(link, api.signInLink())
        api.updateSetting("auth_code", "pending-code")
        api = apiWithDefaultKey("bundled-key") // Host recreates a source after preference edits.
        assertEquals(link, api.browserUrl())
        assertEquals(verifier, settings.get("verifier"))
        assertEquals("pending-code", settings.get("auth_code"))
        assertEquals(0, server.requestCount)
    }
    @Test fun connectedSourceBrowserDoesNotStartANewAuthorization() {
        assertEquals("https://www.dropbox.com/home", api.browserUrl())
        assertEquals("", api.signInLink())
        assertEquals("", settings.get("verifier"))
        assertEquals("old-token", settings.get("access_token"))
        assertEquals("refresh-token", settings.get("refresh_token"))
    }
    @Test fun changingAppOrDisconnectingReplacesTheBrowserLink() {
        settings.values.clear()
        api = apiWithDefaultKey("bundled-key")
        val first = api.browserUrl()
        api.updateSetting("app_key", "custom-key")
        val second = api.browserUrl()
        assertNotEquals(first, second)
        assertEquals("custom-key", second.toHttpUrl().queryParameter("client_id"))
        val verifier = settings.get("verifier")
        api.disconnect()
        assertNotEquals(second, api.browserUrl())
        assertNotEquals(verifier, settings.get("verifier"))
    }
    @Test fun disconnectDoesNotWaitForOrRestorePendingCodeExchange() {
        api.authorizationUrl()
        api.updateSetting("auth_code", "user-code")
        pendingToken { api.disconnect() }
        assertDisconnected()
        expectFailure { api.metadata("id:book") }
        assertEquals(1, server.requestCount)
    }
    @Test fun disconnectDoesNotRestorePendingRefresh() {
        settings.values["expires_at"] = "0"
        pendingToken { api.disconnect() }
        assertDisconnected()
    }
    @Test fun appKeyChangeInvalidatesPendingExchange() {
        api.authorizationUrl()
        api.updateSetting("auth_code", "user-code")
        pendingToken { api.updateSetting("app_key", "replacement-key") }
        assertEquals("replacement-key", settings.get("app_key"))
        assertDisconnected()
    }
    @Test fun newAuthorizationKeepsItsVerifierWhenOldExchangeFinishes() {
        api.authorizationUrl()
        api.updateSetting("auth_code", "user-code")
        val oldVerifier = settings.get("verifier")
        var newVerifier = ""
        pendingToken {
            api.authorizationUrl()
            newVerifier = settings.get("verifier")
        }
        assertNotEquals(oldVerifier, newVerifier)
        assertEquals(newVerifier, settings.get("verifier"))
        assertEquals("", settings.get("auth_code"))
        assertEquals("old-token", settings.get("access_token"))
        assertEquals("refresh-token", settings.get("refresh_token"))
    }
    private fun pendingToken(change: () -> Unit) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/oauth2/token") return json(entry())
                started.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                return json("""{"access_token":"obsolete-token","refresh_token":"obsolete-refresh","expires_in":14400}""")
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val request = workers.submit<DropboxFile> { api.metadata("id:book") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // A UI action must finish while the token response is still withheld.
            workers.submit { change() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            try {
                request.get(5, TimeUnit.SECONDS)
                fail("Obsolete token exchange should fail")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is IOException)
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    private fun assertDisconnected() {
        listOf("access_token", "refresh_token", "expires_at", "auth_code", "verifier", "authorization_url").forEach {
            assertEquals("Expected $it to remain cleared", "", settings.get(it))
        }
    }
    @Test fun enforcesRangesAndPinsArchiveRevision() {
        val file = DropboxFile("id:book", "1.cbz", "/Manga/1.cbz", false, 100, "abc123")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-12/100").setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        assertArrayEquals(byteArrayOf(1, 2, 3), api.reader(file).read(10, 12))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("bytes=10-12", request.getHeader("Range"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertEquals("rev:abc123", Json.parseToJsonElement(request.getHeader("Dropbox-API-Arg")!!).jsonObject.text("path"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("full archive"))
        expectFailure { api.reader(file).read(10, 12) }
    }
    @Test fun rejectsWrongRangeAndWrongBodyLength() {
        val file = DropboxFile("id:book", "1.cbz", "", false, 100, "abc123")
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 9-11/100").setBody("abc"))
        expectFailure { api.reader(file).read(10, 12) }
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 10-12/100").setBody("ab"))
        expectFailure { api.reader(file).read(10, 12) }
    }
    @Test fun doesNotExposeTokensInErrors() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "30").setBody("old-token refresh-token"))
        try { api.metadata("id:book"); fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("30 seconds"))
            assertFalse(e.message!!.contains("old-token"))
            assertFalse(e.message!!.contains("refresh-token"))
        }
    }
    @Test fun asciiHeaderEncodingPreservesUnicode() {
        val encoded = DropboxApi.asciiJson("""{"path":"/日本語/é.cbz"}""")
        assertTrue(encoded.all { it.code < 127 })
        assertEquals("/日本語/é.cbz", Json.parseToJsonElement(encoded).jsonObject.text("path"))
    }
    @Test fun completeBrowseToArchivePageFlow() {
        val image = ByteArray(100000).apply { java.util.Random(42).nextBytes(this) }
        val cbz = archive("2.jpg" to image, "1.jpg" to byteArrayOf(1), stored = true)
        val folder = """{".tag":"folder","id":"id:series","name":"Book","path_display":"/Manga/Book"}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer old-token") return MockResponse().setResponseCode(401)
                return when (request.path) {
                    "/2/files/list_folder" -> json("""{"entries":[$folder,${entry(size = cbz.size)}],"has_more":false,"cursor":"done"}""")
                    "/2/files/get_metadata" -> json(entry(size = cbz.size))
                    "/2/files/download" -> {
                        val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty()) ?: return MockResponse().setResponseCode(400)
                        val start = range.groupValues[1].toInt()
                        val end = range.groupValues[2].toInt()
                        MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${cbz.size}")
                            .setBody(Buffer().write(cbz.copyOfRange(start, end + 1)))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val series = Library.catalog("/Manga", api.list("/Manga", true)).single()
        assertEquals("Book", series.file.name)
        val chapter = series.chapters.single()
        val metadata = api.metadata(chapter.id)
        val zip = RemoteZip(api.reader(metadata))
        assertEquals(listOf("1.jpg", "2.jpg"), zip.pages.map { it.name })
        assertArrayEquals(image, zip.image(zip.pages[1]))
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
}
