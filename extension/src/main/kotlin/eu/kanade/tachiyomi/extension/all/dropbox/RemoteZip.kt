package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Inclusive byte ranges. The implementation must return exactly the requested bytes. */
internal interface RangeReader {
    val size: Long
    fun read(start: Long, end: Long): ByteArray
}

/** Read the central directory and individual pages, without downloading the entire archive. */
internal class RemoteZip(private val reader: RangeReader) {
    data class Entry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val crc: Long,
        val offset: Long,
    )

    val pages: List<Entry> by lazy { directory().filter { isImage(it.name) }.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) } }
    // Conservative accounting includes entry objects, strings and the lazy lookup maps.
    val indexMemoryBytes: Long by lazy { pages.sumOf { entryMemoryBytes(it.name) } }
    private class PageIndex(val positions: Map<Pair<String, Long>, Entry>, val uniqueNames: Map<String, Entry?>)
    private val pageIndex by lazy {
        val positions = mutableMapOf<Pair<String, Long>, Entry>()
        val names = mutableMapOf<String, Entry?>()
        pages.forEach {
            positions.getOrPut(it.name to it.offset) { it }
            if (names.containsKey(it.name)) names[it.name] = null else names[it.name] = it
        }
        PageIndex(positions, names)
    }

    /** Old name-only URLs remain usable when the filename is unambiguous. */
    fun page(name: String, offset: String?): Entry {
        val entry = if (offset == null) pageIndex.uniqueNames[name] else {
            val position = offset.toLongOrNull() ?: fail("Page reference is invalid. Refresh the chapter.")
            pageIndex.positions[name to position]
        }
        // Ambiguous legacy URLs must not silently display the wrong image.
        return entry ?: fail("Page no longer exists or its name is ambiguous. Refresh the chapter.")
    }

    fun image(entry: Entry): ByteArray {
        if (entry.size > MAX_PAGE || entry.compressedSize > MAX_PAGE) fail("Page exceeds the 32 MiB limit")
        val header = read(entry.offset, 30)
        if (u32(header, 0) != 0x04034b50L) fail("Invalid ZIP page header")
        if (u16(header, 6) and 1 != 0 || u16(header, 8) != entry.method) fail("Unsupported ZIP page header")
        val start = entry.offset + 30 + u16(header, 26) + u16(header, 28)
        val compressed = read(start, entry.compressedSize)
        val bytes = when (entry.method) {
            0 -> compressed
            8 -> {
                val inflater = Inflater(true)
                try {
                    InflaterInputStream(ByteArrayInputStream(compressed), inflater).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (output.size().toLong() + n > entry.size || output.size().toLong() + n > MAX_PAGE) fail("ZIP page expands beyond its declared size")
                            output.write(buffer, 0, n)
                        }
                        if (!inflater.finished()) fail("Truncated ZIP page")
                        output.toByteArray()
                    }
                } finally { inflater.end() }
            }
            else -> fail("Only stored and deflated ZIP pages are supported")
        }
        if (bytes.size.toLong() != entry.size || CRC32().apply { update(bytes) }.value != entry.crc) fail("ZIP page is damaged (size or CRC mismatch)")
        return bytes
    }

    private fun directory(): List<Entry> {
        if (reader.size < 22) fail("Not a ZIP/CBZ archive")
        val tailStart = (reader.size - 65557).coerceAtLeast(0)
        val tail = read(tailStart, reader.size - tailStart)
        val end = (tail.size - 22 downTo 0).firstOrNull {
            u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size
        } ?: fail("ZIP end record was not found")
        if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0 || u16(tail, end + 8) != u16(tail, end + 10)) fail("Multi-part ZIP archives are not supported")
        val count = u16(tail, end + 10)
        val length = u32(tail, end + 12)
        val offset = u32(tail, end + 16)
        if (count == 65535 || length == 0xffffffffL || offset == 0xffffffffL) fail("ZIP64 archives are not supported; split into smaller CBZ files")
        if (count > 20000 || length > 16L * 1024 * 1024 || offset + length > tailStart + end) fail("Invalid or oversized ZIP directory")
        val data = if (offset >= tailStart) {
            val start = (offset - tailStart).toInt()
            tail.copyOfRange(start, start + length.toInt())
        } else read(offset, length)
        var pos = 0
        var memoryBytes = 0L
        val entries = ArrayList<Entry>()
        repeat(count) {
            if (pos + 46 > data.size || u32(data, pos) != 0x02014b50L) fail("Invalid ZIP directory entry")
            val flags = u16(data, pos + 8)
            val nameSize = u16(data, pos + 28)
            if (nameSize > MAX_NAME_BYTES) fail("ZIP filename exceeds the 1024-byte limit")
            val extraSize = u16(data, pos + 30)
            val commentSize = u16(data, pos + 32)
            val next = pos + 46 + nameSize + extraSize + commentSize
            if (next > data.size) fail("Truncated ZIP directory entry")
            val charset = if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437")
            val name = String(data, pos + 46, nameSize, charset)
            if (isImage(name)) {
                memoryBytes += entryMemoryBytes(name)
                if (memoryBytes > MAX_INDEX_MEMORY) fail("ZIP page index exceeds the 8 MiB memory budget")
                if (flags and 1 != 0) fail("Password-protected archives are not supported")
                val method = u16(data, pos + 10)
                if (method != 0 && method != 8) fail("Only stored and deflated ZIP pages are supported")
                val compressed = u32(data, pos + 20)
                val size = u32(data, pos + 24)
                val localOffset = u32(data, pos + 42)
                if (compressed == 0xffffffffL || size == 0xffffffffL || localOffset == 0xffffffffL) fail("ZIP64 pages are not supported")
                if (u16(data, pos + 34) != 0 || localOffset + 30 + compressed > offset) fail("Invalid ZIP page offset")
                entries.add(Entry(name, method, compressed, size, u32(data, pos + 16), localOffset))
            }
            pos = next
        }
        if (pos != data.size) fail("Invalid ZIP directory length")
        return entries
    }

    private fun read(start: Long, length: Long): ByteArray {
        if (start < 0 || length < 0 || start > reader.size || length > reader.size - start || length > Int.MAX_VALUE) fail("Invalid ZIP byte range")
        if (length == 0L) return ByteArray(0)
        val bytes = reader.read(start, start + length - 1)
        if (bytes.size.toLong() != length) fail("Incomplete ZIP byte range")
        return bytes
    }

    companion object {
        const val MAX_PAGE = 32L * 1024 * 1024
        const val MAX_NAME_BYTES = 1024
        const val MAX_INDEX_MEMORY = 8L * 1024 * 1024
        private fun entryMemoryBytes(name: String) = 320L + 4L * name.length
        private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
        private fun u32(b: ByteArray, p: Int) = u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
        private fun fail(message: String): Nothing = throw IOException(message)
    }
}
