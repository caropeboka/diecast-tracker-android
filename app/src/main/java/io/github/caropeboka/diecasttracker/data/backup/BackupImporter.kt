package io.github.caropeboka.diecasttracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.zip.ZipFile

data class ImportResult(
    val imported: Int,
    val skipped: List<String>,
    /** Day the web app made the backup. */
    val exportedOn: LocalDate?,
)

sealed interface ImportState {
    data object Idle : ImportState

    data class Running(val done: Int, val total: Int) : ImportState

    data class Finished(val result: ImportResult, val replaced: Boolean) : ImportState

    data class Failed(val message: String) : ImportState
}

/**
 * Restores a backup zip of the web app. "Replace" deletes the current collection first, "merge" adds to it.
 * Everything happens in one transaction: a failed import leaves the collection as it was.
 */
class BackupImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)

    /** Lives outside any screen, so an import keeps going when the user navigates away. */
    val state: StateFlow<ImportState> = _state.asStateFlow()

    fun start(uri: Uri, replace: Boolean) {
        if (_state.value is ImportState.Running) return
        _state.value = ImportState.Running(0, 0)

        scope.launch {
            _state.value = try {
                ImportState.Finished(import(uri, replace), replace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                ImportState.Failed(e.message.orEmpty())
            } catch (e: IOException) {
                ImportState.Failed("File tidak bisa dibaca: ${e.message}")
            } catch (e: Exception) {
                ImportState.Failed("Impor gagal, koleksi tidak diubah. (${e.message ?: e::class.simpleName})")
            }
        }
    }

    fun dismiss() {
        if (_state.value !is ImportState.Running) _state.value = ImportState.Idle
    }

    private suspend fun import(uri: Uri, replace: Boolean): ImportResult = withContext(Dispatchers.IO) {
        // ZipFile needs random access, and the web writes diecasts.json after the photos.
        val copy = File.createTempFile("backup", ".zip", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("File tidak bisa dibuka.")
            input.use { source -> copy.outputStream().use { source.copyTo(it) } }
            import(copy, replace) { done, total -> _state.value = ImportState.Running(done, total) }
        } finally {
            copy.delete()
        }
    }

    suspend fun import(file: File, replace: Boolean, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ImportResult =
        withContext(Dispatchers.IO) {
            val zip = try {
                ZipFile(file)
            } catch (_: IOException) {
                throw BackupException("File ZIP tidak bisa dibuka.")
            }

            zip.use { importFrom(it, replace, onProgress) }
        }

    private suspend fun importFrom(zip: ZipFile, replace: Boolean, onProgress: (Int, Int) -> Unit): ImportResult {
        val entry = zip.getEntry(WebBackupFormat.MANIFEST)?.takeIf { it.size <= MAX_MANIFEST_BYTES }
            ?: throw BackupException(WebBackupFormat.NOT_A_BACKUP)
        val manifest = WebBackupFormat.parseManifest(zip.getInputStream(entry).bufferedReader().use { it.readText() })

        val today = today()
        val records = manifest.records.mapIndexed { i, record -> WebBackupFormat.parseRecord(i, record, today) }
        val valid = records.filterIsInstance<WebBackupFormat.Record.Valid>()
        val skipped = records.filterIsInstance<WebBackupFormat.Record.Invalid>().map { it.reason }

        if (replace && valid.isEmpty()) {
            throw BackupException("Tidak ada diecast yang bisa dipulihkan dari backup ini. Koleksi tidak diubah.")
        }

        val dao = db.diecasts()
        val sightingDao = db.sightings()
        val created = mutableListOf<String>()
        val replacedFiles = mutableListOf<String>()

        try {
            db.withTransaction {
                if (replace) {
                    replacedFiles += dao.allPhotoFiles() + sightingDao.allPhotoFiles()
                    dao.deleteAll()
                    dao.pruneTags()
                }

                valid.forEachIndexed { i, record ->
                    onProgress(i, valid.size)

                    val photoNames = record.photos.mapNotNull { extractPhoto(zip, it) }.also { created += it }
                    val id = repository.save(record.diecast, photoNames, record.tags)

                    record.sightings.forEach { sighting ->
                        val photo = sighting.photo?.let { extractPhoto(zip, it) }?.also { created += it }
                        sightingDao.insert(sighting.entity.copy(diecastId = id, photoFile = photo, createdAt = System.currentTimeMillis()))
                    }
                }
                onProgress(valid.size, valid.size)
            }
        } catch (e: Throwable) {
            created.forEach(photos::delete)
            throw e
        }

        created.filter(photos::isPending).forEach(photos::promote)
        replacedFiles.forEach(photos::delete)

        return ImportResult(valid.size, skipped, manifest.exportedOn)
    }

    /** Copies one photo out of the zip as a pending photo; null if it is missing, too big or not a JPEG. */
    private fun extractPhoto(zip: ZipFile, name: String): String? {
        if (!WebBackupFormat.PHOTO_ENTRY.matches(name)) return null

        val entry = zip.getEntry(name)?.takeIf { it.size in 0..MAX_PHOTO_BYTES } ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }

        return photos.addFromBackup(bytes)
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
        const val MAX_PHOTO_BYTES = 15L * 1024 * 1024
    }
}
