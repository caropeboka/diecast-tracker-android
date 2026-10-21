package io.github.caropeboka.diecasttracker.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportKind(val mime: String) {
    /** Everything, to restore on a phone or in the web app. */
    Backup("application/zip"),

    /** The collection as a table, to read or share. */
    Excel(CollectionSheet.MIME);

    fun fileName(day: LocalDate) = when (this) {
        Backup -> "backup-diecast-$day.zip"
        Excel -> "koleksi-diecast-$day.xlsx"
    }
}

/** [photos]: of the diecasts; [sightingPhotos]: of their "Ditemui" notes. */
data class ExportSummary(val diecasts: Int, val photos: Int = 0, val sightingPhotos: Int = 0, val dexes: Int = 0)

sealed interface ExportState {
    data object Idle : ExportState

    /** [total] = 0 while gathering; then photos written of [total]. */
    data class Running(val kind: ExportKind, val done: Int, val total: Int) : ExportState

    /** [share]: the file to hand to the share sheet, until the screen has done so. */
    data class Finished(
        val kind: ExportKind,
        val fileName: String,
        val bytes: Long,
        val summary: ExportSummary,
        val share: Uri? = null,
        val forSharing: Boolean = share != null,
    ) : ExportState

    data class Failed(val kind: ExportKind, val message: String) : ExportState
}

/**
 * Writes the backup zip or the Excel export, into a document the user created with the system's file picker or into
 * a file for the share sheet. The zip is what the web's `CollectionBackup::create` makes (`diecasts.json`, `photos/`,
 * `koleksi.xlsx`) plus the Dex ([DexBackup]), so either app can restore it.
 */
class BackupExporter(
    private val context: Context,
    private val db: AppDatabase,
    private val photos: PhotoStore,
    private val scope: CoroutineScope,
    /** Holds the `dex/` folder, like [io.github.caropeboka.diecasttracker.data.dex.DexCustom]'s root. */
    private val root: File = context.filesDir,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A backup was written: the reminder starts over. */
    private val onBackup: suspend (at: Long) -> Unit = {},
) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Lives outside any screen, so a backup keeps going when the user navigates away. */
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private val shareDir = File(context.cacheDir, SHARE_DIR)

    fun fileName(kind: ExportKind): String = kind.fileName(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate())

    /** Writes into [uri], a document made by the file picker; a failed write removes it again. */
    fun save(kind: ExportKind, uri: Uri) = run(kind) {
        val resolver = context.contentResolver
        try {
            val out = Counting(resolver.openOutputStream(uri, "w") ?: throw IOException("file tujuan tidak bisa dibuka"))
            val summary = out.use { write(kind, it) }
            ExportState.Finished(kind, displayName(uri) ?: fileName(kind), out.count, summary)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw e
        }
    }

    /** Writes into the app's cache, for the share sheet (Drive, WhatsApp, e-mail…). */
    fun share(kind: ExportKind) = run(kind) {
        // Earlier files went out long ago; a recent one may still be uploading.
        housekeeping(maxAge = HOUR_MILLIS)
        val file = File(shareDir.apply { mkdirs() }, fileName(kind))
        val summary = file.outputStream().use { write(kind, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        ExportState.Finished(kind, file.name, file.length(), summary, share = uri)
    }

    /** The share sheet has been opened. */
    fun shared() {
        (_state.value as? ExportState.Finished)?.let { _state.value = it.copy(share = null) }
    }

    fun dismiss() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }

    /** Removes shared files: at start-up those older than a day. */
    fun housekeeping(maxAge: Long = DAY_MILLIS) {
        val cutoff = clock() - maxAge
        shareDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach(File::delete)
    }

    private fun run(kind: ExportKind, block: suspend () -> ExportState.Finished) {
        if (_state.value is ExportState.Running) return
        _state.value = ExportState.Running(kind, 0, 0)

        scope.launch {
            _state.value = try {
                withContext(Dispatchers.IO) { block() }.also { if (kind == ExportKind.Backup) onBackup(clock()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ExportState.Failed(kind, "File tidak bisa ditulis: ${e.message ?: e::class.simpleName}")
            } catch (e: Exception) {
                ExportState.Failed(kind, "Gagal membuat file. (${e.message ?: e::class.simpleName})")
            }
        }
    }

    suspend fun write(kind: ExportKind, out: OutputStream): ExportSummary = when (kind) {
        ExportKind.Backup -> writeBackup(out) { done, total -> _state.value = ExportState.Running(kind, done, total) }
        ExportKind.Excel -> writeExcel(out)
    }

    suspend fun writeExcel(out: OutputStream): ExportSummary = withContext(Dispatchers.IO) {
        val diecasts = db.diecasts().allDetails()
        CollectionSheet.write(out, diecasts)
        ExportSummary(diecasts.size)
    }

    /** Closes [out]. */
    suspend fun writeBackup(out: OutputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ExportSummary =
        withContext(Dispatchers.IO) {
            val dexDao = db.dex()
            val (diecasts, catalogs, priceChecks) = db.withTransaction {
                Triple(
                    db.diecasts().allDetails(),
                    dexDao.allCatalogs().map { it to dexDao.items(it.id) },
                    db.priceChecks().all().groupBy { it.diecastId },
                )
            }

            // Zip entry → file, for every photo that is really there (the web skips missing ones too).
            val files = linkedMapOf<String, File>()
            fun add(folder: String, file: File): String? =
                file.takeIf(File::isFile)?.let { "$folder/${it.name}" }?.also { files[it] = file }
            fun photo(name: String?): String? = name?.let { add("photos", photos.file(it)) }

            var photoCount = 0
            var sightingPhotoCount = 0
            val records = diecasts.map { details ->
                val own = details.orderedPhotos.mapNotNull { photo(it.fileName) }.also { photoCount += it.size }
                val sightingPhoto = { sighting: SightingEntity -> photo(sighting.photoFile)?.also { sightingPhotoCount++ } }
                WebBackupFormat.record(details, own, sightingPhoto, priceChecks[details.diecast.id].orEmpty())
            }
            val dex = catalogs.map { (catalog, items) ->
                DexBackup.catalog(catalog, items) { item ->
                    item.imagePath?.takeIf { catalog.source == DexMatcher.CUSTOM }?.let { add("dex", File(root, it)) }
                }
            }

            ZipOutputStream(out.buffered()).use { zip ->
                zip.deflated(WebBackupFormat.MANIFEST, WebBackupFormat.manifest(records, clock()).toByteArray())
                zip.deflated(DexBackup.ENTRY, DexBackup.encode(dex).toByteArray())
                zip.putNextEntry(ZipEntry("koleksi.xlsx"))
                CollectionSheet.write(zip, diecasts)
                zip.closeEntry()

                files.entries.forEachIndexed { i, (name, file) ->
                    onProgress(i, files.size)
                    zip.stored(name, file.readBytes())
                }
                onProgress(files.size, files.size)
            }

            ExportSummary(diecasts.size, photoCount, sightingPhotoCount, catalogs.size)
        }

    private fun ZipOutputStream.deflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /** Photos are JPEGs already: compressing them again only costs time. */
    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            },
        )
        write(bytes)
        closeEntry()
    }

    /** The name the file picker gave the document (it may add " (1)"). */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        /** Under the cache; shared through the FileProvider (`res/xml/file_paths.xml`). */
        const val SHARE_DIR = "exports"
        private const val HOUR_MILLIS = 60 * 60 * 1000L
        private const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
