package eu.kanade.tachiyomi.extension.all.dropbox

import java.util.Locale

/** Compare digit runs without converting to an integer (filenames can contain huge numbers). */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.lowercase(Locale.ROOT)
        val y = b.lowercase(Locale.ROOT)
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            if (x[i] in '0'..'9' && y[j] in '0'..'9') {
                val startI = i
                val startJ = j
                while (i < x.length && x[i] in '0'..'9') i++
                while (j < y.length && y[j] in '0'..'9') j++
                val nx = x.substring(startI, i).trimStart('0').ifEmpty { "0" }
                val ny = y.substring(startJ, j).trimStart('0').ifEmpty { "0" }
                val diff = nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                if (diff != 0) return diff
            } else {
                val diff = x[i].compareTo(y[j])
                if (diff != 0) return diff
                i++
                j++
            }
        }
        return (x.length - i).compareTo(y.length - j).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

internal fun isArchive(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("cbz", "zip")
internal fun isImage(name: String): Boolean {
    val parts = name.replace('\\', '/').split('/')
    return parts.none { it.startsWith('.') || it == "__MACOSX" } &&
        parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
}
internal fun imageMime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}
