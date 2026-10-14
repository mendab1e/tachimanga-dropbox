package eu.kanade.tachiyomi.extension.all.dropbox

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Dropbox : HttpSource(), ConfigurableSource {
    override val name = "Dropbox"
    override val lang = "all"
    override val baseUrl = "https://www.dropbox.com"
    override val supportsLatest = true

    // Tachimanga also displays this URL beneath the browse title. Keep source
    // metadata static and free of pending OAuth links or authentication effects.
    // The older 1.4 compile-only API does not declare this runtime hook.
    fun getHomeUrl(): String = baseUrl

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }
    private val settings by lazy {
        object : Settings {
            override fun get(key: String) = preferences.getString(key, "").orEmpty()
            override fun put(values: Map<String, String>) {
                val editor = preferences.edit()
                values.forEach { (key, value) -> editor.putString(key, value) }
                if (!editor.commit()) throw IOException("Unable to save Dropbox settings")
            }
        }
    }
    private val api by lazy {
        DropboxApi(network.client.newBuilder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).build(), settings, defaultAppKey = BuildConfig.DROPBOX_APP_KEY)
    }
    private val cache = LibraryCache()
    private val library by lazy { LibraryRepository(api, cache) }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            if (url.host != IMAGE_HOST || url.encodedPath !in setOf(PAGE_PATH, COVER_PATH)) {
                chain.proceed(request)
            } else {
                val ref = url.queryParameter("ref") ?: throw IOException("Missing Dropbox file reference")
                val image = if (url.encodedPath == PAGE_PATH) {
                    val rev = url.queryParameter("rev") ?: throw IOException("Missing Dropbox revision")
                    val page = url.queryParameter("entry") ?: throw IOException("Missing ZIP page")
                    val zip = zip(ref, rev)
                    val entry = zip.page(page, url.queryParameter("offset"))
                    Pair(zip.image(entry), imageMime(entry.name))
                } else {
                    val series = library.series(ref)
                    val cover = series.cover
                    if (cover != null) Pair(api.image(cover), imageMime(cover.name))
                    else archiveCover(series.chapters.firstOrNull() ?: throw IOException("No CBZ/ZIP chapters in this series"))
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Content-Type", image.second).header("Cache-Control", "no-store")
                    .body(image.first.toResponseBody(image.second.toMediaType())).build()
            }
        }.build()
    }

    private fun loadZip(file: DropboxFile): RemoteZip {
        if (file.folder || file.rev.isBlank()) throw IOException("Dropbox item is not a downloadable archive")
        return RemoteZip(api.reader(file)).also {
            if (it.pages.isEmpty()) throw IOException("No supported page images in ${file.name}")
        }
    }
    private fun zip(file: DropboxFile) = cache.archive("${file.id}:${file.rev}") { loadZip(file) }
    private fun zip(ref: String, rev: String) = cache.archive("$ref:$rev") {
        val file = api.metadata("rev:$rev")
        if (file.id != ref) throw IOException("Dropbox page reference does not match its revision")
        loadZip(file)
    }
    private fun archiveCover(file: DropboxFile): Pair<ByteArray, String> {
        val zip = zip(file)
        val cover = zip.pages.first()
        return Pair(zip.image(cover), imageMime(cover.name))
    }
    private fun imageUrl(path: String, ref: String, rev: String = "", entry: String = "", offset: Long? = null) =
        "https://$IMAGE_HOST$path".toHttpUrl().newBuilder().addQueryParameter("ref", ref)
            .apply {
                if (rev.isNotEmpty()) addQueryParameter("rev", rev)
                if (entry.isNotEmpty()) addQueryParameter("entry", entry)
                if (offset != null) addQueryParameter("offset", offset.toString())
            }.build().toString()
    private fun reference(url: String): String = (baseUrl + url).toHttpUrl().queryParameter("ref")
        ?: throw IOException("Invalid Dropbox library item. Remove and add it again.")
    private fun itemUrl(file: DropboxFile) = "/tachimanga/${if (file.folder) "series" else "archive"}?ref=" +
        "https://$IMAGE_HOST/".toHttpUrl().newBuilder().addQueryParameter("ref", file.id).build().encodedQuery!!.substringAfter("ref=")
    private fun manga(file: DropboxFile): SManga = SManga.create().apply {
        url = itemUrl(file)
        title = if (file.folder) file.name else file.name.substringBeforeLast('.')
        thumbnail_url = imageUrl(COVER_PATH, file.id)
        description = "Read directly from your Dropbox library.\nFolder: ${file.path}\nSupported chapters: CBZ and ZIP."
        status = SManga.UNKNOWN
        initialized = true
    }
    private fun browse(page: Int, query: String = "", latest: Boolean = false, search: Boolean = false): MangasPage {
        require(page >= 1) { "Page numbers start at 1" }
        val root = Library.root(settings.get(SettingKey.LIBRARY_PATH).ifBlank { "/Manga" })
        val catalog = library.catalog(root, refresh = page == 1 && !search, allowStale = page > 1)
        val matches = catalog.filter { it.file.name.contains(query.trim(), ignoreCase = true) }
            .let { if (latest) it.sortedByDescending { series -> series.modified } else it }
        val start = (page.toLong() - 1) * PAGE_SIZE
        if (start >= matches.size) return MangasPage(emptyList(), false)
        val end = minOf(start + PAGE_SIZE, matches.size.toLong()).toInt()
        return MangasPage(matches.subList(start.toInt(), end).map { manga(it.file) }, end < matches.size)
    }
    private fun <T> io(block: () -> T): Observable<T> = Observable.fromCallable(block).subscribeOn(Schedulers.io())
    override fun fetchPopularManga(page: Int) = io { browse(page) }
    override fun fetchLatestUpdates(page: Int) = io { browse(page, latest = true) }
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) = io { browse(page, query, search = true) }
    override fun fetchMangaDetails(manga: SManga) = io { manga(api.metadata(reference(manga.url))) }
    override fun fetchChapterList(manga: SManga) = io {
        val file = api.metadata(reference(manga.url))
        val files = if (file.folder) Library.chapters(api.list(file.id, recursive = true)) else listOf(file)
        if (files.isEmpty()) throw IOException("No CBZ/ZIP chapters in this folder")
        files.mapIndexed { index, chapter -> SChapter.create().apply {
            url = itemUrl(chapter)
            name = (if (file.folder) Library.relativePath(file, chapter) else chapter.name).substringBeforeLast('.')
            chapter_number = chapterNumber(chapter.name, index)
            date_upload = parseDate(chapter.modified)
        } }.reversed()
    }
    override fun fetchPageList(chapter: SChapter) = io {
        val file = api.metadata(reference(chapter.url))
        zip(file).pages.mapIndexed { index, entry ->
            Page(index, imageUrl = imageUrl(PAGE_PATH, file.id, file.rev, entry.name, entry.offset))
        }
    }
    override fun getMangaUrl(manga: SManga) = baseUrl
    override fun getChapterUrl(chapter: SChapter) = baseUrl
    override fun getFilterList() = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        DropboxPreferences(screen, settings, api, { cache.invalidate() }, { cache.refreshCatalog() }).setup()
    }

    // Fetch methods above own the multi-request API/ZIP workflow.
    override fun popularMangaRequest(page: Int): Request = unused()
    override fun popularMangaParse(response: Response): MangasPage = unused()
    override fun latestUpdatesRequest(page: Int): Request = unused()
    override fun latestUpdatesParse(response: Response): MangasPage = unused()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = unused()
    override fun searchMangaParse(response: Response): MangasPage = unused()
    override fun mangaDetailsParse(response: Response): SManga = unused()
    override fun chapterListParse(response: Response): List<SChapter> = unused()
    override fun pageListParse(response: Response): List<Page> = unused()
    override fun imageUrlParse(response: Response): String = unused()
    private fun unused(): Nothing = throw UnsupportedOperationException("Dropbox uses the direct API fetch workflow")

    companion object {
        private const val IMAGE_HOST = "api.dropboxapi.com"
        private const val PAGE_PATH = "/tachimanga-page"
        private const val COVER_PATH = "/tachimanga-cover"
        private const val PAGE_SIZE = 50
        private fun chapterNumber(name: String, index: Int): Float {
            val explicit = Regex("(?i)(?:chapter|ch[. _-]*)\\s*(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)
            val leading = Regex("^(\\d+(?:\\.\\d+)?)(?:[^0-9]|$)").find(name)?.groupValues?.get(1)
            return (explicit ?: leading)?.toFloatOrNull() ?: (index + 1).toFloat()
        }
        private fun parseDate(value: String): Long = runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time ?: 0
        }.getOrDefault(0)
    }
}
