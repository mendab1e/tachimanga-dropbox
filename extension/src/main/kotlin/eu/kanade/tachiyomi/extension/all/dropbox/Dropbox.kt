package eu.kanade.tachiyomi.extension.all.dropbox

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
        val root = Library.root(settings.get("library_path").ifBlank { "/Manga" })
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
    override fun getMangaUrl(manga: SManga) = api.browserUrl()
    override fun getChapterUrl(chapter: SChapter) = api.browserUrl()
    override fun getFilterList() = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        api.signInLink() // Prepare once; opening/reloading settings must not rotate the verifier.
        // The executable source reports its own build, independently of the
        // host's extension-list metadata or any persisted preference value.
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = "extension_build"
            title = "Dropbox extension ${BuildConfig.VERSION_NAME}"
            summary = "Installed source build ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
            setDefaultValue("${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            isEnabled = false
        })
        fun text(key: String, title: String, summary: String, secret: Boolean = false, readOnly: Boolean = false) {
            screen.addPreference(EditTextPreference(screen.context).apply {
                this.key = key
                this.title = title
                this.summary = summary
                dialogTitle = title
                val current = settings.get(key).ifBlank { if (key == "library_path") "/Manga" else "" }
                // Tachimanga's Android compatibility layer has no setPersistent().
                // A typed default is also required when it serializes preferences.
                setDefaultValue(current)
                this.text = current
                if (secret) setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
                setOnPreferenceChangeListener { _, value ->
                    if (readOnly) return@setOnPreferenceChangeListener false
                    val text = (value as String).trim()
                    if (key == "library_path" && runCatching { Library.root(text) }.isFailure) return@setOnPreferenceChangeListener false
                    api.updateSetting(key, text)
                    cache.invalidate()
                    setDefaultValue(text)
                    false // Already applied; do not let the preference persist it a second time.
                }
            })
        }
        if (BuildConfig.DROPBOX_APP_KEY.isBlank()) {
            text("app_key", "Dropbox app key", "Public app key from your Dropbox developer app. No app secret is needed.")
        } else {
            text("app_key", "Custom Dropbox app (optional)", "Leave blank to use the included Dropbox connection. Changing apps requires reconnecting.")
        }
        text("library_path", "Library folder", "Default: /Manga. One subfolder per series; CBZ/ZIP files are chapters. Use / for the Dropbox root.")
        // Tachimanga ignores click listeners on plain Preference rows. List
        // selections reach its supported preference-change endpoint instead.
        screen.addPreference(ListPreference(screen.context).apply {
            key = "connection_action"
            title = "Dropbox library actions"
            summary = "Refresh the library or disconnect. Use the Dropbox sign-in link below to connect."
            entries = arrayOf("Choose an action", "Refresh Dropbox library", "Disconnect Dropbox")
            entryValues = arrayOf("", "refresh", "disconnect")
            setDefaultValue("")
            setOnPreferenceChangeListener { _, value ->
                when (value as String) {
                    "refresh" -> cache.refreshCatalog()
                    "disconnect" -> clearConnection()
                }
                false // Commands execute once and are never persisted as a selection.
            }
        })
        text("authorization_url", "Dropbox sign-in", "Open this automatically prepared link in Safari to authorize Dropbox, then paste the returned code below.", readOnly = true)
        text("auth_code", "Dropbox authorization code", "Paste the code from Dropbox. Open the source to finish sign-in; the code is then cleared.", secret = true)
    }
    private fun clearConnection() {
        api.disconnect()
        cache.invalidate()
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
