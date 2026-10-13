package eu.kanade.tachiyomi.extension.all.dropbox

import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LibraryRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: DropboxApi
    private lateinit var cache: LibraryCache
    private lateinit var library: LibraryRepository
    private var now = 0L
    private val folder = """{".tag":"folder","id":"id:series","name":"Book","path_display":"/Manga/BOOK","path_lower":"/manga/book"}"""
    private fun file(id: String, name: String, path: String, rev: String = "abc123456", size: Int = 1) =
        """{".tag":"file","id":"$id","name":"$name","path_display":"$path","path_lower":"${path.lowercase()}","rev":"$rev","size":$size}"""
    private fun listing(vararg files: String) = MockResponse().setBody("""{"entries":[${files.joinToString(",")}],"has_more":false}""")

    @Before fun start() {
        now = 0
        server = MockWebServer().apply { start() }
        val settings = object : Settings {
            private val values = ConcurrentHashMap(mapOf("access_token" to "token", "expires_at" to (System.currentTimeMillis() + 3600000).toString()))
            override fun get(key: String) = values[key].orEmpty()
            override fun put(values: Map<String, String>) { this.values.putAll(values) }
        }
        api = DropboxApi(OkHttpClient(), settings, server.url("/2/").toString(), server.url("/2/").toString())
        cache = LibraryCache { now }
        library = LibraryRepository(api, cache)
    }
    @After fun stop() { server.shutdown() }

    @Test fun cachedCatalogProvidesExplicitCoverWithoutMetadataOrSubtreeRequests() {
        server.enqueue(listing(folder, file("id:chapter", "1.cbz", "/Manga/Book/1.cbz"),
            file("id:cover", "cover.jpg", "/Manga/Book/cover.jpg")))
        server.enqueue(MockResponse().setBody("x"))
        library.catalog("/Manga", true)
        val cover = library.series("id:series").cover!!
        assertEquals("abc123456", cover.rev)
        assertArrayEquals(byteArrayOf('x'.code.toByte()), api.image(cover))
        assertEquals(2, server.requestCount)
        assertEquals("/2/files/list_folder", server.takeRequest().path)
        val download = server.takeRequest()
        assertEquals("/2/files/download", download.path)
        assertTrue(download.getHeader("Dropbox-API-Arg")!!.contains("rev:abc123456"))
    }
    @Test fun cachedCatalogSuppliesFirstNaturalArchiveAndStandaloneMetadata() {
        server.enqueue(listing(folder, file("id:ten", "10.cbz", "/Manga/Book/10.cbz"),
            file("id:two", "2.cbz", "/Manga/Book/2.cbz"), file("id:solo", "Solo.zip", "/Manga/Solo.zip")))
        library.catalog("/Manga", true)
        assertNull(library.series("id:series").cover)
        assertEquals("id:two", library.series("id:series").chapters.first().id)
        assertEquals("id:solo", library.series("id:solo").chapters.single().id)
        assertEquals(1, server.requestCount)
    }
    @Test fun forcedRefreshReplacesCachedCoverWithItsNewRevision() {
        val chapter = file("id:chapter", "1.cbz", "/Manga/Book/1.cbz")
        server.enqueue(listing(folder, chapter, file("id:cover", "cover.jpg", "/Manga/Book/cover.jpg")))
        server.enqueue(listing(folder, chapter, file("id:cover", "cover.jpg", "/Manga/Book/cover.jpg", rev = "def123456")))
        server.enqueue(MockResponse().setBody("x"))
        library.catalog("/Manga", true)
        assertEquals("abc123456", library.series("id:series").cover!!.rev)
        library.catalog("/Manga", true)
        val cover = library.series("id:series").cover!!
        assertEquals("def123456", cover.rev)
        api.image(cover)
        server.takeRequest()
        server.takeRequest()
        assertTrue(server.takeRequest().getHeader("Dropbox-API-Arg")!!.contains("rev:def123456"))
        assertEquals(3, server.requestCount)
    }
    @Test fun searchesReuseSnapshotUntilExpiryAndExplicitRefresh() {
        repeat(4) { server.enqueue(listing(folder, file("id:chapter", "1.cbz", "/Manga/Book/1.cbz"))) }
        val first = library.catalog("/Manga", false)
        assertSame(first, library.catalog("/Manga", false))
        assertEquals(1, server.requestCount)
        now = LibraryCache.CATALOG_TTL
        val renewed = library.catalog("/Manga", false)
        assertNotSame(first, renewed)
        assertEquals(2, server.requestCount)
        cache.refreshCatalog()
        library.catalog("/Manga", false)
        assertEquals(3, server.requestCount)
        library.catalog("/Manga", true)
        assertEquals(4, server.requestCount)
    }
    @Test fun coldOrExpiredCoversFetchCurrentSeriesMetadata() {
        repeat(2) {
            server.enqueue(MockResponse().setBody(folder))
            server.enqueue(listing(file("id:chapter", "1.cbz", "/Manga/Book/1.cbz")))
        }
        assertEquals("id:chapter", library.series("id:series").chapters.single().id)
        cache.catalog("/Manga", true) { listOf(Series(DropboxFile("id:series", "Book", "/Manga/Book", true), emptyList())) }
        now = LibraryCache.CATALOG_TTL
        assertEquals("id:chapter", library.series("id:series").chapters.single().id)
        assertEquals(4, server.requestCount)
    }
}
