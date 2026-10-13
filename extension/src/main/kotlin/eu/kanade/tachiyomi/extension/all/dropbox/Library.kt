package eu.kanade.tachiyomi.extension.all.dropbox

import java.util.Locale

internal data class Series(val file: DropboxFile, val chapters: List<DropboxFile>, val cover: DropboxFile? = null) {
    val modified: String get() = chapters.maxOfOrNull { it.modified }.orEmpty()
}
internal object Library {
    fun root(value: String): String {
        val path = value.trim().trimEnd('/')
        require(path.isEmpty() || path.startsWith('/')) { "Library folder must start with /" }
        return path
    }
    fun catalog(root: String, files: List<DropboxFile>): List<Series> {
        val parent = root.lowercase(Locale.ROOT)
        val archives = files.filter { !it.folder && isArchive(it.name) && visible(it.path) }
        val folders = files.filter { it.folder && parent(it.pathLower) == parent && visible(it.path) }
        val covers = mutableMapOf<String, DropboxFile>()
        files.filter { !it.folder && isCover(it.name) && visible(it.path) }.forEach {
            covers.getOrPut(parent(it.pathLower)) { it }
        }
        val prefix = parent.trimEnd('/') + "/"
        val grouped = mutableMapOf<String, MutableList<DropboxFile>>()
        val standalone = mutableListOf<Series>()
        archives.forEach { archive ->
            val path = archive.pathLower
            if (path.startsWith(prefix)) {
                val separator = path.indexOf('/', prefix.length)
                if (separator == -1) standalone.add(Series(archive, listOf(archive)))
                else grouped.getOrPut(path.substring(0, separator)) { mutableListOf() }.add(archive)
            }
        }
        val series = folders.mapNotNull { folder ->
            val chapters = grouped[folder.pathLower.trimEnd('/')] ?: return@mapNotNull null
            Series(folder, chapters.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }, covers[folder.pathLower.trimEnd('/')])
        } + standalone
        return series.sortedWith { a, b -> NaturalOrder.compare(a.file.name, b.file.name) }
    }
    fun chapters(files: List<DropboxFile>): List<DropboxFile> = files.filter {
        !it.folder && isArchive(it.name) && visible(it.path)
    }.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
    fun cover(folder: DropboxFile, files: List<DropboxFile>): DropboxFile? = files.firstOrNull {
        !it.folder && parent(it.pathLower) == folder.pathLower.trimEnd('/') &&
            isCover(it.name)
    }
    fun relativePath(folder: DropboxFile, file: DropboxFile): String {
        val prefix = folder.pathLower.trimEnd('/') + "/"
        return if (file.pathLower.startsWith(prefix)) file.path.split('/').takeLast(
            file.pathLower.removePrefix(prefix).split('/').size,
        ).joinToString("/") else file.name
    }
    private fun parent(path: String) = path.substringBeforeLast('/', "").lowercase(Locale.ROOT)
    private fun isCover(name: String) = name.lowercase(Locale.ROOT) in setOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp")
    private fun visible(path: String) = path.split('/').none { it.startsWith('.') || it == "__MACOSX" }
}
