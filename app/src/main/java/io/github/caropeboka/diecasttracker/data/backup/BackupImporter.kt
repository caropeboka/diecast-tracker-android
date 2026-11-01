package io.github.caropeboka.diecasttracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DecorationEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.decor.DecorStore
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
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
    /** Day the backup was made. */
    val exportedOn: LocalDate?,
    /** Dex lists in a phone backup (restored, or matched to ones already here). */
    val dexes: Int = 0,
)

sealed interface ImportState {
    data object Idle : ImportState

    data class Running(val done: Int, val total: Int) : ImportState

    data class Finished(val result: ImportResult, val replaced: Boolean) : ImportState

    data class Failed(val message: String) : ImportState
}

/**
 * Restores a backup zip, made by the web app or on the phone. "Replace" deletes the current collection first, "merge"
 * adds to it. Everything happens in one transaction: a failed import leaves the collection as it was.
 *
 * A phone backup also holds the Dex ([DexBackup]): the diecasts keep their links, and "replace" swaps the own dexes
 * too. Wiki lists are shared reference data: the phone's own years are kept and reused. Its decorations for the 3D
 * garage ([DecorBackup]) come back as well; "replace" swaps them.
 */
class BackupImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = LocalDate::now,
    /** Holds the `dex/` folder, like [io.github.caropeboka.diecasttracker.data.dex.DexCustom]'s root. */
    private val root: File = context.filesDir,
    /** After a Dex came back: its wiki thumbnails need downloading again. */
    private val onDexRestored: suspend () -> Unit = {},
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
        val skipped = records.filterIsInstance<WebBackupFormat.Record.Invalid>().map { it.reason }.toMutableList()

        if (replace && valid.isEmpty()) {
            throw BackupException("Tidak ada diecast yang bisa dipulihkan dari backup ini. Koleksi tidak diubah.")
        }

        val dex = zip.getEntry(DexBackup.ENTRY)?.let { entry ->
            entry.takeIf { it.size <= MAX_MANIFEST_BYTES }
                ?.let { DexBackup.decode(zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() }) }
                .also { if (it == null) skipped.add(0, "Data Dex di backup ini rusak, jadi Dex tidak ikut dipulihkan.") }
        }

        val decor = zip.getEntry(DecorBackup.ENTRY)?.let { entry ->
            entry.takeIf { it.size <= MAX_MANIFEST_BYTES }
                ?.let { DecorBackup.decode(zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() }) }
                .also { if (it == null) skipped.add(0, "Data hiasan garasi di backup ini rusak, jadi tidak ikut dipulihkan.") }
        }
        val decorStore = DecorStore(root)
        val decorFiles = mutableListOf<String>()
        val replacedDecor = mutableListOf<String>()

        val dao = db.diecasts()
        val sightingDao = db.sightings()
        val dexDao = db.dex()
        val restorer = DexRestorer(db, photos, root, System::currentTimeMillis)
        val created = mutableListOf<String>()
        val replacedFiles = mutableListOf<String>()
        val replacedDexes = mutableListOf<Long>()

        try {
            db.withTransaction {
                if (replace) {
                    replacedFiles += dao.allPhotoFiles() + sightingDao.allPhotoFiles()
                    dao.deleteAll()
                    dao.pruneTags()
                    // A web backup has no Dex: the own dexes stay.
                    if (dex != null) {
                        replacedDexes += dexDao.customCatalogIds()
                        replacedDexes.forEach { dexDao.deleteCatalog(it) }
                    }
                }

                val itemIds = dex?.let { restorer.restore(zip, it.catalogs) }.orEmpty()

                if (decor != null) {
                    if (replace) {
                        replacedDecor += db.decorations().all().map { it.photo }
                        db.decorations().deleteAll()
                    }
                    decor.decorations.forEach { item ->
                        if (item.tier !in 0..2 || !DecorBackup.PHOTO_ENTRY.matches(item.photo)) return@forEach
                        val entry = zip.getEntry(item.photo)?.takeIf { it.size in 0..DecorBackup.MAX_PHOTO_BYTES } ?: return@forEach
                        val photo = decorStore.import(zip.getInputStream(entry).use { it.readBytes() }).also { decorFiles += it }
                        db.decorations().insert(
                            DecorationEntity(
                                name = item.name,
                                photo = photo,
                                heightMm = item.heightMm,
                                tier = item.tier,
                                position = item.position,
                                createdAt = item.createdAt,
                            ),
                        )
                    }
                }

                valid.forEachIndexed { i, record ->
                    onProgress(i, valid.size)

                    val photoNames = record.photos.mapNotNull { extractPhoto(zip, it) }.also { created += it }
                    val itemId = record.dexItem?.let(itemIds::get)
                    val diecast = record.diecast.copy(
                        catalogItemId = itemId,
                        // A link to an entry that didn't come back is worked out again; "not in the Dex" stays.
                        catalogMatch = if (itemId != null) {
                            record.diecast.catalogMatch ?: CatalogMatch.Auto
                        } else {
                            record.diecast.catalogMatch?.takeIf { it == CatalogMatch.Ignored }
                        },
                    )
                    val id = repository.save(diecast, photoNames, record.tags)
                    record.createdAt?.let { dao.setTimestamps(id, it, record.updatedAt ?: it) }

                    record.priceChecks.forEach { db.priceChecks().insert(it.copy(diecastId = id)) }
                    record.shelfSlot?.let { restoreSlot(id, diecast, it) }
                    record.sightings.forEach { sighting ->
                        val photo = sighting.photo?.let { extractPhoto(zip, it) }?.also { created += it }
                        val createdAt = sighting.entity.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()
                        sightingDao.insert(sighting.entity.copy(diecastId = id, photoFile = photo, createdAt = createdAt))
                    }
                }
                onProgress(valid.size, valid.size)
            }
        } catch (e: Throwable) {
            created.forEach(photos::delete)
            restorer.written.forEach(File::delete)
            decorFiles.forEach(decorStore::delete)
            throw e
        }

        created.filter(photos::isPending).forEach(photos::promote)
        replacedFiles.forEach(photos::delete)
        replacedDexes.forEach { File(root, "dex/$it").deleteRecursively() }
        replacedDecor.forEach(decorStore::delete)
        if (dex != null) onDexRestored()

        return ImportResult(valid.size, skipped, manifest.exportedOn, dexes = dex?.catalogs?.size ?: 0)
    }

    /**
     * Back where it stood in the 3D garage, as long as it is owned, fits there and, when merging, the place is free:
     * a compartment holds one, a full lane takes no more, and a card at the same height puts it on top.
     */
    private suspend fun restoreSlot(id: Long, diecast: DiecastEntity, slot: ShelfSlotEntity) {
        if (diecast.status != DiecastStatus.Owned || !ShelfLayout.fits(slot.shelf, diecast.packaging, diecast.scale)) return
        val shelves = db.shelves()
        val there = shelves.at(slot.shelf, slot.spot)
        val position = when (slot.shelf) {
            Shelf.Case -> if (there.isEmpty()) 0 else return
            Shelf.Boards -> when {
                there.size >= ShelfLayout.LANE_CARDS -> return
                there.any { it.position == slot.position } -> there.maxOf { it.position } + 1
                else -> slot.position
            }
            Shelf.Corner -> slot.position
        }
        shelves.put(slot.copy(diecastId = id, position = position))
    }

    /** Copies one photo out of the zip as a pending photo; null if it is missing, too big or not a JPEG. */
    private fun extractPhoto(zip: ZipFile, name: String): String? {
        if (!WebBackupFormat.PHOTO_ENTRY.matches(name)) return null

        val entry = zip.getEntry(name)?.takeIf { it.size in 0..WebBackupFormat.MAX_PHOTO_BYTES } ?: return null
        val bytes = zip.getInputStream(entry).use { it.readBytes() }

        return photos.addFromBackup(bytes)
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
    }
}
