package io.github.caropeboka.diecasttracker.data.scan

import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.AiOfflineException
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import io.github.caropeboka.diecasttracker.data.ai.Confidence
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** Where a read photo came from (web: `scan_items.source`). */
enum class ScanSource(val key: String) {
    /** The bulk scan queue. */
    Bulk("bulk"),

    /** "Cek di toko". */
    Check("check");

    companion object {
        fun fromKey(key: String?): ScanSource = entries.find { it.key == key } ?: Bulk
    }
}

enum class ScanItemStatus(val key: String) {
    /** Waiting for the AI. */
    Pending("pending"),

    /** Read: to check and save. */
    Done("done"),
    Failed("failed");

    companion object {
        fun fromKey(key: String?): ScanItemStatus = entries.find { it.key == key } ?: Pending
    }
}

/** Ready for "Simpan semua": the AI was confident and nothing in the collection looks like it (web: `isSafe`). */
fun ScanItemEntity.isSafe(hasMatches: Boolean) =
    status == ScanItemStatus.Done && !fields?.name.isNullOrBlank() && fields?.confidence != Confidence.Low && !hasMatches

/**
 * Bulk scan (web: `BulkScanController`): many photos go into a queue, the AI reads them one by one ([ScanWork] in
 * the background), and the collector checks and saves them. A queued photo stays a pending photo of the
 * [PhotoStore] until its diecast is saved, which takes it over.
 */
class ScanQueue(
    db: AppDatabase,
    private val photos: PhotoStore,
    private val scanner: DiecastScanner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.scanItems()

    fun observe(): Flow<List<ScanItemEntity>> = dao.observeBulk()

    /** Everything not saved or thrown away yet: what the banners count. */
    fun observeCount(): Flow<Int> = dao.observeBulkCount()

    suspend fun item(id: Long): ScanItemEntity? = dao.get(id)

    /** [photoName]: a pending photo. */
    suspend fun add(photoName: String): Long = dao.insert(ScanItemEntity(photoFile = photoName, createdAt = clock()))

    suspend fun hasPending(): Boolean = dao.countPending() > 0

    /** The oldest photo waiting for the AI. */
    suspend fun next(): ScanItemEntity? = dao.nextPending()

    /**
     * Lets the AI read [item]. A busy AI, used-up quota or no connection leaves it waiting (the exception goes to the
     * caller, which tries again later); any other problem marks it failed with the reason.
     */
    suspend fun process(item: ScanItemEntity) {
        val read = try {
            item.copy(status = ScanItemStatus.Done, fields = scanner.scan(listOf(item.photoFile)), error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiRetryLaterException) {
            throw e
        } catch (e: AiOfflineException) {
            throw e
        } catch (e: AiException) {
            item.copy(status = ScanItemStatus.Failed, error = e.message)
        } catch (e: Exception) {
            item.copy(status = ScanItemStatus.Failed, error = "Scan gagal (${e.message ?: e::class.simpleName}). Isi manual atau coba lagi.")
        }
        // Thrown away in the meantime: nothing to update.
        if (dao.get(item.id) != null) dao.update(read)
    }

    /** Back in line for the AI. */
    suspend fun retry(id: Long) {
        val item = dao.get(id) ?: return
        dao.update(item.copy(status = ScanItemStatus.Pending, error = null))
    }

    /** Thrown away, photo and all. */
    suspend fun discard(id: Long) {
        val item = dao.get(id) ?: return
        dao.delete(id)
        photos.delete(item.photoFile)
    }

    /** Saved as a diecast, which took its photo: only the queue entry goes. */
    suspend fun saved(id: Long) = dao.delete(id)

    /** Photos the housekeeping must leave pending. */
    suspend fun photoFiles(): Set<String> = dao.allPhotoFiles().toSet()
}
