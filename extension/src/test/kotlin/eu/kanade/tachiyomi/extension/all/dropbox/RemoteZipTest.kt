package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

internal fun archive(vararg files: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        files.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}
private class MemoryReader(val bytes: ByteArray) : RangeReader {
    override val size = bytes.size.toLong()
    val reads = mutableListOf<LongRange>()
    override fun read(start: Long, end: Long): ByteArray {
        reads.add(start..end)
        return bytes.copyOfRange(start.toInt(), end.toInt() + 1)
    }
}
class RemoteZipTest {
    @Test fun readsDeflatedPagesWithDataDescriptorsAndNaturalOrder() {
        val reader = MemoryReader(archive("pages/10.png" to byteArrayOf(10), "pages/2.png" to byteArrayOf(2),
            "pages/1.png" to byteArrayOf(1), "__MACOSX/._1.png" to byteArrayOf(0),
            "ComicInfo.xml" to byteArrayOf(0), "hidden/.1.png" to byteArrayOf(0), "日本語/3.PNG" to byteArrayOf(3)))
        val zip = RemoteZip(reader)
        assertEquals(listOf("pages/1.png", "pages/2.png", "pages/10.png", "日本語/3.PNG"), zip.pages.map { it.name })
        assertArrayEquals(byteArrayOf(2), zip.image(zip.pages[1]))
    }
    @Test fun readsStoredPages() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val zip = RemoteZip(MemoryReader(archive("1.jpg" to bytes, stored = true)))
        assertArrayEquals(bytes, zip.image(zip.pages.single()))
    }
    @Test fun resolvesDuplicateFilenamesByOffsetAndRejectsAmbiguousLegacyUrls() {
        // ZipOutputStream disallows duplicate names; rename both headers of the second entry.
        val bytes = archive("1.jpg" to byteArrayOf(1), "2.jpg" to byteArrayOf(2), stored = true)
        val name = "2.jpg".toByteArray()
        (0..bytes.size - name.size).forEach { pos ->
            if (bytes.copyOfRange(pos, pos + name.size).contentEquals(name)) bytes[pos] = '1'.code.toByte()
        }
        val zip = RemoteZip(MemoryReader(bytes))
        assertEquals(listOf("1.jpg", "1.jpg"), zip.pages.map { it.name })
        assertNotEquals(zip.pages[0].offset, zip.pages[1].offset)
        zip.pages.forEachIndexed { index, page ->
            assertArrayEquals(byteArrayOf((index + 1).toByte()), zip.image(zip.page(page.name, page.offset.toString())))
        }
        expectFailure { zip.page("1.jpg", null) }
        expectFailure { zip.page("1.jpg", "invalid") }
        expectFailure { zip.page("1.jpg", "999999") }
    }
    @Test fun preservesUnambiguousLegacyPageReferences() {
        val zip = RemoteZip(MemoryReader(archive("1.png" to byteArrayOf(1))))
        assertArrayEquals(byteArrayOf(1), zip.image(zip.page("1.png", null)))
        expectFailure { zip.page("wrong.png", zip.pages.single().offset.toString()) }
    }
    @Test fun centralDirectoryWithinTailNeedsOnlyOneRangeRequest() {
        val reader = MemoryReader(archive("1.jpg" to byteArrayOf(1)))
        val zip = RemoteZip(reader)
        assertEquals(1, zip.pages.size)
        assertEquals(listOf(0L until reader.size), reader.reads)
    }
    @Test fun centralDirectoryOutsideTailStillUsesAnExactDirectoryRange() {
        val reader = MemoryReader(archive(*Array(1500) { "page-$it.jpg" to byteArrayOf(1) }, stored = true))
        val zip = RemoteZip(reader)
        assertEquals(1500, zip.pages.size)
        assertEquals(2, reader.reads.size)
        assertEquals(reader.size - 65557, reader.reads[0].first)
        assertTrue(reader.reads[1].first < reader.reads[0].first)
        assertArrayEquals(byteArrayOf(1), zip.image(zip.pages.last()))
    }
    @Test(timeout = 10000) fun indexedLookupsHandleTwentyThousandEntries() {
        val zip = RemoteZip(MemoryReader(archive(*Array(20000) { "page-$it.jpg" to byteArrayOf(1) }, stored = true)))
        assertEquals(20000, zip.pages.size)
        zip.pages.forEach { assertSame(it, zip.page(it.name, it.offset.toString())) }
    }
    @Test fun readsOnlySelectedPageOfLargeArchive() {
        val random = java.util.Random(1)
        val a = ByteArray(150000).apply { random.nextBytes(this) }
        val b = ByteArray(150000).apply { random.nextBytes(this) }
        val reader = MemoryReader(archive("1.jpg" to a, "2.jpg" to b, stored = true))
        val zip = RemoteZip(reader)
        val second = zip.pages[1]
        assertTrue(reader.reads.sumOf { it.last - it.first + 1 } < reader.size / 2)
        reader.reads.clear()
        assertArrayEquals(b, zip.image(second))
        assertTrue(reader.reads.all { it.first >= second.offset })
        assertTrue(reader.reads.sumOf { it.last - it.first + 1 } < reader.size)
    }
    @Test fun rejectsTruncatedAndNonZipFiles() {
        expectFailure { RemoteZip(MemoryReader(byteArrayOf(0, 1))).pages }
        val data = archive("1.png" to byteArrayOf(1))
        expectFailure { RemoteZip(MemoryReader(data.copyOf(data.size - 10))).pages }
    }
    @Test fun rejectsDamagedCrc() {
        val data = archive("1.png" to byteArrayOf(1, 2), stored = true)
        val nameLength = "1.png".length
        data[30 + nameLength] = 8
        val zip = RemoteZip(MemoryReader(data))
        expectFailure { zip.image(zip.pages.single()) }
    }
    @Test fun rejectsEncryptedAndZip64Archives() {
        val data = archive("1.png" to byteArrayOf(1))
        val central = signature(data, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val encrypted = data.copyOf().apply { this[central + 8] = (this[central + 8].toInt() or 1).toByte() }
        expectFailure { RemoteZip(MemoryReader(encrypted)).pages }
        val zip64 = data.copyOf().apply { this[size - 22 + 10] = -1; this[size - 22 + 11] = -1 }
        expectFailure { RemoteZip(MemoryReader(zip64)).pages }
    }
    @Test fun rejectsPageSizeLimitBeforeReadingData() {
        val zip = RemoteZip(MemoryReader(archive("1.png" to byteArrayOf(1))))
        val entry = zip.pages.single().copy(size = RemoteZip.MAX_PAGE + 1)
        expectFailure { zip.image(entry) }
    }
    @Test fun ignoresZipSignaturesInsideComment() {
        val data = archive("1.png" to byteArrayOf(1))
        val comment = ByteArray(50).apply { this[0] = 0x50; this[1] = 0x4b; this[2] = 0x05; this[3] = 0x06 }
        val bytes = data.copyOf().apply { this[size - 2] = comment.size.toByte() } + comment
        assertEquals(1, RemoteZip(MemoryReader(bytes)).pages.size)
    }
    @Test fun rejectsShortRangeResponses() {
        val data = archive("1.png" to byteArrayOf(1))
        expectFailure { RemoteZip(object : RangeReader {
            override val size = data.size.toLong()
            override fun read(start: Long, end: Long) = data.copyOfRange(start.toInt(), end.toInt())
        }).pages }
    }
    private fun signature(data: ByteArray, sig: ByteArray) = (0..data.size - 4).first { data.copyOfRange(it, it + 4).contentEquals(sig) }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
}
