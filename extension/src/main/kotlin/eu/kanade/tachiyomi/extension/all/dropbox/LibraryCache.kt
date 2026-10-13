package eu.kanade.tachiyomi.extension.all.dropbox

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore

/** Cache locks protect memory only; loaders may perform arbitrarily slow network I/O. */
internal class LibraryCache(private val now: () -> Long = { System.nanoTime() / 1000000 }) {
    private val lock = Any()
    private var generation = 0L
    private var catalogRequest = 0L
    private var catalogRoot: String? = null
    private var catalog: List<Series> = emptyList()
    private var catalogLoadedAt = 0L
    private var seriesById: Map<String, Series> = emptyMap()
    private val pendingArchives = mutableMapOf<String, FutureTask<RemoteZip>>()
    private val archives = LinkedHashMap<String, RemoteZip>(16, 0.75f, true)
    private var archiveMemoryBytes = 0L
    private val archiveLoads = Semaphore(2)

    fun invalidate() = synchronized(lock) {
        generation++
        clearCatalog()
        archives.clear()
        archiveMemoryBytes = 0L
        pendingArchives.values.forEach { it.cancel(false) }
        pendingArchives.clear()
    }

    fun refreshCatalog() = synchronized(lock) { clearCatalog() }

    private fun clearCatalog() {
        catalogRequest++
        catalogRoot = null
        catalog = emptyList()
        seriesById = emptyMap()
    }

    private fun catalogIsFresh() = now() - catalogLoadedAt < CATALOG_TTL

    fun series(id: String): Series? = synchronized(lock) {
        if (catalogIsFresh()) seriesById[id] else null
    }

    fun catalog(root: String, refresh: Boolean, allowStale: Boolean = false, load: () -> List<Series>): List<Series> {
        val expected = synchronized(lock) {
            if (!refresh && catalogRoot == root && (allowStale || catalogIsFresh())) return catalog
            catalogRequest++
            generation to catalogRequest
        }
        val loaded = load()
        val index = loaded.associateBy { it.file.id }
        return synchronized(lock) {
            checkGeneration(expected.first)
            if (catalogRequest != expected.second) throw IOException("A newer Dropbox library refresh started. Browse again.")
            catalogRoot = root
            catalog = loaded
            seriesById = index
            catalogLoadedAt = now()
            loaded
        }
    }

    fun archive(key: String, load: () -> RemoteZip): RemoteZip {
        val (task, leader) = synchronized(lock) {
            archives[key]?.let { return it }
            val pending = pendingArchives[key]
            if (pending != null) pending to false else {
                val expected = generation
                val created = FutureTask {
                    try {
                        archiveLoads.acquire()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Dropbox archive loading was interrupted", e)
                    }
                    try {
                        synchronized(lock) { checkGeneration(expected) }
                        val loaded = load()
                        val weight = loaded.indexMemoryBytes
                        synchronized(lock) {
                            checkGeneration(expected)
                            while (archives.size >= 16 || archiveMemoryBytes + weight > MAX_ARCHIVE_MEMORY) {
                                val eldest = archives.entries.iterator()
                                archiveMemoryBytes -= eldest.next().value.indexMemoryBytes
                                eldest.remove()
                            }
                            archives[key] = loaded
                            archiveMemoryBytes += weight
                            loaded
                        }
                    } finally {
                        archiveLoads.release()
                    }
                }
                pendingArchives[key] = created
                created to true
            }
        }
        try {
            if (leader) task.run()
            return task.get()
        } catch (_: CancellationException) {
            throw IOException("Dropbox settings changed. Refresh the source or chapter.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Dropbox archive loading was interrupted", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            synchronized(lock) {
                if (task.isDone && pendingArchives[key] === task) pendingArchives.remove(key)
            }
        }
    }

    private fun checkGeneration(expected: Long) {
        if (generation != expected) throw IOException("Dropbox settings changed. Refresh the source or chapter.")
    }

    companion object {
        const val CATALOG_TTL = 60000L
        const val MAX_ARCHIVE_MEMORY = 16L * 1024 * 1024
    }
}
