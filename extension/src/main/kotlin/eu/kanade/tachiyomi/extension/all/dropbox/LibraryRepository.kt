package eu.kanade.tachiyomi.extension.all.dropbox

/** Browse and cover requests share the same fresh, revision-pinned library metadata. */
internal class LibraryRepository(private val api: DropboxApi, private val cache: LibraryCache) {
    fun catalog(root: String, refresh: Boolean, allowStale: Boolean = false): List<Series> =
        cache.catalog(root, refresh, allowStale) { Library.catalog(root, api.list(root, recursive = true)) }

    fun series(id: String): Series {
        cache.series(id)?.let { return it }
        val file = api.metadata(id)
        if (!file.folder) return Series(file, listOf(file))
        val files = api.list(file.id, recursive = true)
        return Series(file, Library.chapters(files), Library.cover(file, files))
    }
}
