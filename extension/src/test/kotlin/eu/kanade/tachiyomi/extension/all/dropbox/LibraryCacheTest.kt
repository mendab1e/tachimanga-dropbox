package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LibraryCacheTest {
    @Test fun searchSnapshotsExpireButLaterPagesKeepTheirSnapshot() {
        var now = 0L
        val cache = LibraryCache { now }
        val file = DropboxFile("id:book", "Book", "/Manga/Book", true)
        val first = listOf(Series(file, emptyList()))
        cache.catalog("/Manga", false) { first }
        now = LibraryCache.CATALOG_TTL - 1
        assertSame(first, cache.catalog("/Manga", false) { error("Fresh searches should reuse the catalog") })
        assertSame(first.single(), cache.series(file.id))
        now++
        assertNull(cache.series(file.id))
        assertSame(first, cache.catalog("/Manga", false, allowStale = true) { error("Pagination should retain its snapshot") })
        val second = emptyList<Series>()
        assertSame(second, cache.catalog("/Manga", false) { second })
        assertNull(cache.series(file.id))
    }
    @Test fun explicitCatalogRefreshKeepsImmutableArchiveIndexes() {
        val cache = LibraryCache()
        val index = zip()
        cache.archive("id:book:rev") { index }
        cache.catalog("/Manga", false) { emptyList() }
        cache.refreshCatalog()
        var loaded = false
        cache.catalog("/Manga", false) { loaded = true; emptyList() }
        assertTrue(loaded)
        assertSame(index, cache.archive("id:book:rev") { error("Library refresh should not discard immutable indexes") })
    }
    @Test fun concurrentArchiveMissesShareOneLoadWhileOtherKeysProceed() {
        val cache = LibraryCache()
        val index = zip()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val workers = Executors.newFixedThreadPool(3)
        try {
            val leader = workers.submit<RemoteZip> {
                cache.archive("id:book:rev") {
                    loads.incrementAndGet()
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    index
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val follower = workers.submit<RemoteZip> {
                cache.archive("id:book:rev") { loads.incrementAndGet(); zip() }
            }
            try {
                follower.get(200, TimeUnit.MILLISECONDS)
                fail("Follower should wait for the shared load")
            } catch (_: TimeoutException) { }
            assertSame(index, workers.submit<RemoteZip> { cache.archive("another:rev") { index } }.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertSame(index, leader.get(5, TimeUnit.SECONDS))
            assertSame(index, follower.get(5, TimeUnit.SECONDS))
            assertEquals(1, loads.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    @Test fun failedSharedArchiveLoadCanBeRetried() {
        val cache = LibraryCache()
        try {
            cache.archive("id:book:rev") { throw IOException("Test failure") }
            fail("Load should fail")
        } catch (_: IOException) { }
        val index = zip()
        assertSame(index, cache.archive("id:book:rev") { index })
    }
    @Test fun invalidationWakesSharedLoadWaitersBeforeOldNetworkWorkFinishes() {
        val cache = LibraryCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val leader = workers.submit<RemoteZip> {
                cache.archive("id:book:rev") {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    zip()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val follower = workers.submit<RemoteZip> { cache.archive("id:book:rev") { error("Must join the pending load") } }
            try {
                follower.get(200, TimeUnit.MILLISECONDS)
                fail("Follower should be waiting")
            } catch (_: TimeoutException) { }
            workers.submit { cache.invalidate() }.get(2, TimeUnit.SECONDS)
            try {
                follower.get(2, TimeUnit.SECONDS)
                fail("Invalidated follower should fail")
            } catch (e: ExecutionException) { assertTrue(e.cause is IOException) }
            val replacement = zip()
            assertSame(replacement, cache.archive("id:book:rev") { replacement })
            release.countDown()
            try {
                leader.get(5, TimeUnit.SECONDS)
                fail("Obsolete leader should fail")
            } catch (e: ExecutionException) { assertTrue(e.cause is IOException) }
            assertSame(replacement, cache.archive("id:book:rev") { error("Old task must not overwrite or remove the replacement") })
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    @Test fun explicitCatalogRefreshRejectsAPendingOlderListing() {
        val cache = LibraryCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val pending = workers.submit<List<Series>> {
                cache.catalog("/Manga", false) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    emptyList()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workers.submit { cache.refreshCatalog() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            try {
                pending.get(5, TimeUnit.SECONDS)
                fail("Listing cleared by explicit refresh should fail")
            } catch (e: ExecutionException) { assertTrue(e.cause is IOException) }
            var loaded = false
            cache.catalog("/Manga", false) { loaded = true; emptyList() }
            assertTrue(loaded)
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    @Test fun slowerEarlierRefreshCannotReplaceNewerCatalog() {
        val cache = LibraryCache()
        fun series(name: String) = listOf(Series(DropboxFile("id:$name", name, "/Manga/$name", true), emptyList()))
        val older = series("Older")
        val newer = series("Newer")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<List<Series>> {
                cache.catalog("/Manga", true) {
                    started.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    older
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertSame(newer, cache.catalog("/Manga", true) { newer })
            release.countDown()
            try {
                first.get(5, TimeUnit.SECONDS)
                fail("Superseded refresh should fail")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is IOException)
            }
            assertSame(newer, cache.catalog("/Manga", false) { error("Later pages should use the newer catalog") })
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
    @Test fun invalidationDoesNotWaitForCatalogIoOrAllowStaleResults() {
        val cache = LibraryCache()
        pendingLoad(cache) { started, release ->
            cache.catalog("/Manga", true) {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                emptyList()
            }
        }
        var loaded = false
        cache.catalog("/Manga", false) { loaded = true; emptyList() }
        assertTrue(loaded)
    }
    @Test fun invalidationDoesNotWaitForArchiveIoOrRepopulateOldIndex() {
        val cache = LibraryCache()
        val zip = zip()
        pendingLoad(cache) { started, release ->
            cache.archive("id:book:rev") {
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                zip
            }
        }
        var loaded = false
        cache.archive("id:book:rev") { loaded = true; zip }
        assertTrue(loaded)
    }
    @Test fun retainsOnlySixteenIndexesAndReusesCachedRevisions() {
        val cache = LibraryCache()
        val zip = zip()
        (0..15).forEach { cache.archive("$it") { zip } }
        assertSame(zip, cache.archive("0") { error("Cached index should be reused") })
        cache.archive("16") { zip }
        var reloaded = false
        cache.archive("1") { reloaded = true; zip }
        assertTrue(reloaded)
        assertSame(zip, cache.archive("0") { error("Recently accessed index should remain cached") })
    }
    private fun pendingLoad(cache: LibraryCache, load: (CountDownLatch, CountDownLatch) -> Any) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val result = workers.submit<Any> { load(started, release) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workers.submit { cache.invalidate() }.get(2, TimeUnit.SECONDS)
            release.countDown()
            try {
                result.get(5, TimeUnit.SECONDS)
                fail("Invalidated load should fail")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is IOException)
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
    private fun zip(): RemoteZip {
        val bytes = archive("1.jpg" to byteArrayOf(1))
        return RemoteZip(object : RangeReader {
            override val size = bytes.size.toLong()
            override fun read(start: Long, end: Long) = bytes.copyOfRange(start.toInt(), end.toInt() + 1)
        })
    }
}
