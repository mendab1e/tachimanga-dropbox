package eu.kanade.tachiyomi.extension.all.dropbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    private fun file(path: String, folder: Boolean = false) = DropboxFile("id:$path", path.substringAfterLast('/'), path, folder)
    @Test fun groupsNestedArchivesAndStandaloneBooksWithoutPrefixCollisions() {
        val files = listOf(file("/Manga/Series", true), file("/Manga/Series 2", true), file("/Manga/Empty", true),
            file("/Manga/Series/Volume 2/Chapter 10.cbz"), file("/Manga/Series/Volume 2/Chapter 2.CBZ"),
            file("/Manga/Series 2/1.zip"), file("/Manga/Book.zip"), file("/Manga/Series/.hidden/0.cbz"), file("/Manga/Series/cover.jpg"))
        val catalog = Library.catalog("/Manga", files)
        assertEquals(listOf("Book.zip", "Series", "Series 2"), catalog.map { it.file.name })
        assertEquals(listOf("Chapter 2.CBZ", "Chapter 10.cbz"), catalog[1].chapters.map { it.name })
        assertEquals(1, catalog[2].chapters.size)
    }
    @Test fun rootAndCaseHandling() {
        assertEquals("", Library.root(" / "))
        assertEquals("/Manga", Library.root("/Manga/"))
        assertEquals(1, Library.catalog("/manga", listOf(file("/Manga/1.zip"))).size)
        assertEquals(1, Library.catalog("", listOf(file("/1.zip"))).size)
    }
    @Test fun groupsAccountRootWithoutIncludingOrphanOrHiddenPaths() {
        val folder = file("/Book", true)
        val nested = file("/Book/Volume 2/Chapter 1.cbz")
        val standalone = file("/Standalone.zip")
        val catalog = Library.catalog("", listOf(
            nested, standalone, folder, file("/Book 2/1.zip"),
            file("/Book/.hidden/2.cbz"), file("/__MACOSX", true), file("/__MACOSX/1.zip"),
        ))
        assertEquals(listOf("Book", "Standalone.zip"), catalog.map { it.file.name })
        assertEquals(listOf(nested), catalog.first().chapters)
        assertEquals(listOf(standalone), catalog.last().chapters)
    }
    @Test(timeout = 10000) fun groupsHundredThousandItemsWithoutQuadraticScan() {
        val files = ArrayList<DropboxFile>(100000)
        repeat(33333) { index ->
            val path = "/Manga/Series $index"
            files.add(file(path, true))
            files.add(file("$path/Chapter 10.cbz"))
            files.add(file("$path/Chapter 1.cbz"))
        }
        files.add(file("/Manga/Standalone.zip"))
        val catalog = Library.catalog("/Manga", files)
        assertEquals(33334, catalog.size)
        assertEquals("Series 0", catalog.first().file.name)
        assertEquals(listOf("Chapter 1.cbz", "Chapter 10.cbz"), catalog.first().chapters.map { it.name })
        assertEquals("Standalone.zip", catalog.last().file.name)
        assertEquals(1, catalog.last().chapters.size)
    }
    @Test fun numbersDoNotOverflow() {
        assertTrue(NaturalOrder.compare("2.jpg", "10.jpg") < 0)
        assertTrue(NaturalOrder.compare("99999999999999999999.jpg", "100000000000000000000.jpg") < 0)
        assertEquals(listOf("1", "02", "2", "10"), listOf("10", "2", "02", "1").sortedWith(NaturalOrder))
    }
    @Test fun coversAndChapterNamesUseCanonicalPathsAfterCaseOnlyRename() {
        val folder = file("/Manga/BOOK", true)
        val cover = DropboxFile.parse(Json.parseToJsonElement("""{
            ".tag":"file", "id":"id:cover", "name":"cover.jpg",
            "path_display":"/manga/Book/cover.jpg", "path_lower":"/manga/book/cover.jpg"
        }""").jsonObject)
        val nestedCover = file("/Manga/Book/Volume 2/cover.jpg")
        val chapter = file("/manga/Book/Volume 2/Chapter 001.cbz")
        assertEquals("/manga/book/cover.jpg", cover.pathLower)
        assertEquals(cover, Library.cover(folder, listOf(nestedCover, cover)))
        assertNull(Library.cover(folder, listOf(nestedCover)))
        assertEquals("Volume 2/Chapter 001.cbz", Library.relativePath(folder, chapter))
        assertEquals(listOf(chapter), Library.catalog("/Manga", listOf(folder, chapter)).single().chapters)
    }
}
