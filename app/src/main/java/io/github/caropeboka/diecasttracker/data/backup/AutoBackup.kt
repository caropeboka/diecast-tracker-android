package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where the weekly backup goes and how the last one went. The places are content URIs as strings, null when not
 * chosen: [folder] a folder on the phone from the system's folder picker, where the newest [AutoBackup.KEEP]
 * automatic backups stay; [copy] one file anywhere the file picker can save, Google Drive included (Android doesn't
 * hand an app a Drive folder), written over each time.
 */
data class AutoBackupSettings(
    val on: Boolean = false,
    val folder: String? = null,
    val copy: String? = null,
    /** The last time at least one place was written (epoch millis). */
    val lastAt: Long? = null,
    /** Why the last try failed, a line per place; null after a try that wrote everywhere. */
    val error: String? = null,
) {
    val hasPlace get() = folder != null || copy != null
}

sealed interface AutoBackupState {
    data object Idle : AutoBackupState

    /** Writing the zip ([total] = 0 while gathering, then photos written of [total]), then [copying] it to the places. */
    data class Running(val done: Int = 0, val total: Int = 0, val copying: Boolean = false) : AutoBackupState
}

/** A folder that holds the automatic backups. */
interface BackupFolder {
    /** The names of the files in it. */
    fun names(): List<String>

    /** [name] opened for writing: made when it isn't there, emptied when it is. */
    fun write(name: String): OutputStream

    fun delete(name: String)
}

/** The one file written over each time. */
fun interface BackupCopy {
    fun write(): OutputStream
}

/** A place that can't be reached any more (its permission is gone, or it was deleted): it must be picked again. */
class BackupPlaceGoneException(cause: Throwable? = null) : Exception(cause)

/**
 * The weekly backup, written by [AutoBackupWork] with the app closed or on "Back up now": the same zip as a manual
 * backup ([BackupExporter.writeBackup]), made once in the cache and copied to each chosen place. A place that fails
 * doesn't stop the other; any place written counts as a backup for the reminder.
 */
class AutoBackup(
    private val exporter: BackupExporter,
    private val texts: Texts,
    private val cacheDir: File,
    private val settings: suspend () -> AutoBackupSettings,
    /** [at] when a place was written; [error] null when none failed. */
    private val record: suspend (at: Long?, error: String?) -> Unit,
    private val folderAt: (String) -> BackupFolder,
    private val copyAt: (String) -> BackupCopy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val running = Mutex()
    private val _state = MutableStateFlow<AutoBackupState>(AutoBackupState.Idle)

    /** Shared by the worker and the screen, which may both be in this process. */
    val state: StateFlow<AutoBackupState> = _state.asStateFlow()

    /**
     * Writes the backup to the chosen places, [now] ("Back up now") even while the weekly run is off. False when no
     * place was written or nothing was chosen; [whenBusy] when a backup is running already (the weekly run counts
     * that one as its own instead of coming back for it).
     */
    suspend fun run(now: Boolean = false, whenBusy: Boolean = false): Boolean {
        if (!running.tryLock()) return whenBusy
        try {
            val chosen = settings()
            if (!chosen.hasPlace || !(chosen.on || now)) return false
            return withContext(Dispatchers.IO) { write(chosen) }
        } finally {
            _state.value = AutoBackupState.Idle
            running.unlock()
        }
    }

    private suspend fun write(chosen: AutoBackupSettings): Boolean {
        _state.value = AutoBackupState.Running()
        val zip = File(cacheDir, TEMP_NAME)
        try {
            zip.outputStream().use { out -> exporter.writeBackup(out) { done, total -> _state.value = AutoBackupState.Running(done, total) } }
        } catch (e: CancellationException) {
            zip.delete()
            throw e
        } catch (e: Exception) {
            zip.delete()
            record(null, texts(R.string.export_failed, reason(e)))
            return false
        }

        _state.value = AutoBackupState.Running(copying = true)
        val errors = mutableListOf<String>()
        var written = 0
        try {
            chosen.folder?.let { uri ->
                attempt(R.string.auto_backup_folder_failed, R.string.auto_backup_folder_gone, errors) { toFolder(folderAt(uri), zip) }
                    ?.let { written++ }
            }
            chosen.copy?.let { uri ->
                attempt(R.string.auto_backup_copy_failed, R.string.auto_backup_copy_gone, errors) {
                    copyAt(uri).write().use { out -> zip.inputStream().use { it.copyTo(out) } }
                }?.let { written++ }
            }
        } finally {
            zip.delete()
        }

        record(clock().takeIf { written > 0 }, errors.joinToString("\n").ifEmpty { null })
        return written > 0
    }

    /** Null when [block] failed; its reason goes into [errors] under the place's [label]. */
    private inline fun attempt(label: Int, gone: Int, errors: MutableList<String>, block: () -> Unit): Unit? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val placeGone = e is BackupPlaceGoneException || e is SecurityException || e is FileNotFoundException
        errors += texts(label, if (placeGone) texts(gone) else reason(e))
        null
    }

    private fun toFolder(folder: BackupFolder, zip: File) {
        val name = fileName(Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate())
        try {
            folder.write(name).use { out -> zip.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            // Half a zip is worse than none.
            runCatching { folder.delete(name) }
            throw e
        }
        outdated(folder.names()).forEach { runCatching { folder.delete(it) } }
    }

    private fun reason(e: Exception) = e.message ?: e::class.simpleName.orEmpty()

    companion object {
        /** Automatic backups kept in the folder. */
        const val KEEP = 3

        /** Days between automatic backups. */
        const val EVERY_DAYS = 7L

        /** What the file picker offers as the copy's name. */
        const val COPY_NAME = "diecast-tracker-backup.zip"

        private const val TEMP_NAME = "auto-backup.zip"

        /** Unlike a manual backup's "backup-diecast-<day>.zip", so pruning never touches one saved by hand. */
        fun fileName(day: LocalDate) = "backup-diecast-auto-$day.zip"

        /** A provider may add " (1)" to a name that is taken. */
        private val AUTOMATIC = Regex("""backup-diecast-auto-(\d{4}-\d{2}-\d{2})(?: \((\d+)\))?\.zip""")

        /** The automatic backups among [names] beyond the newest [keep]; every other file stays. */
        fun outdated(names: List<String>, keep: Int = KEEP): List<String> =
            names.mapNotNull { name -> AUTOMATIC.matchEntire(name)?.let { name to it } }
                .sortedWith(
                    compareByDescending<Pair<String, MatchResult>> { (_, match) -> match.groupValues[1] }
                        .thenByDescending { (_, match) -> match.groupValues[2].toIntOrNull() ?: 0 },
                )
                .drop(keep)
                .map { (name, _) -> name }
    }
}
