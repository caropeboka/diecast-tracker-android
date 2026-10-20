package io.github.caropeboka.diecasttracker.ui.dex

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.dex.CatalogField
import io.github.caropeboka.diecasttracker.data.dex.ChecklistReader
import io.github.caropeboka.diecasttracker.data.dex.DexCustom
import io.github.caropeboka.diecasttracker.data.dex.DexCustomException
import io.github.caropeboka.diecasttracker.data.dex.DexEntry
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.DexRow
import io.github.caropeboka.diecasttracker.data.dex.DexSync
import io.github.caropeboka.diecasttracker.data.dex.MAX_CHECKLIST_IMAGES
import io.github.caropeboka.diecasttracker.data.dex.PhotoEdit
import io.github.caropeboka.diecasttracker.data.dex.RowField
import io.github.caropeboka.diecasttracker.ui.DexCatalogRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The entry being typed in or corrected. [photo] is a new pending photo; [currentPhoto] the entry's own (a path
 * under `files/`), shown until it's removed or replaced.
 */
@Serializable
data class EntryDraft(
    val itemId: Long = 0,
    val row: DexRow = DexRow(),
    val photo: String? = null,
    val currentPhoto: String? = null,
    val removePhoto: Boolean = false,
) {
    val isNew get() = itemId == 0L
}

/** What the AI read from a checklist, to be ticked before it's added. */
@Serializable
data class ChecklistReview(val rows: List<DexRow>, val known: List<Boolean>, val selected: Set<Int>)

/** The dex's name, brand and year being edited. */
@Serializable
data class CatalogDraft(val id: Long = 0, val brand: String = "", val name: String = "", val year: String = "")

enum class ImportBusy { Checklist, Spreadsheet, Collection }

/** Building an own dex: entries by hand, many at once, and the dex itself (web: `custom-dex-tools.tsx`). */
class CustomDexViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val dex: DexRepository,
    private val custom: DexCustom,
    private val checklist: ChecklistReader,
    private val dexSync: DexSync,
    private val photos: PhotoStore,
    private val messages: Messages,
) : ViewModel() {
    val catalogId = saved.toRoute<DexCatalogRoute>().id

    val aiEnabled: StateFlow<Boolean> = checklist.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var draft by mutableStateOf(saved.get<String>(DRAFT)?.let { Json.decodeFromString<EntryDraft>(it) })
        private set
    var draftErrors by mutableStateOf(emptyMap<RowField, String>())
        private set
    var savingEntry by mutableStateOf(false)
        private set
    var processingPhoto by mutableStateOf(false)
        private set

    /** The entry just added while the sheet stays open for the next one (the snackbar is behind the sheet). */
    var lastAdded by mutableStateOf<String?>(null)
        private set

    var importing by mutableStateOf(saved.get<Boolean>(IMPORTING) == true)
        private set
    var busy by mutableStateOf<ImportBusy?>(null)
        private set

    /** Why the last import didn't work, shown in the sheet. */
    var importNote by mutableStateOf<String?>(null)
        private set
    var review by mutableStateOf(saved.get<String>(REVIEW)?.let { Json.decodeFromString<ChecklistReview>(it) })
        private set

    var catalogDraft by mutableStateOf(saved.get<String>(CATALOG)?.let { Json.decodeFromString<CatalogDraft>(it) })
        private set
    var catalogErrors by mutableStateOf(emptyMap<CatalogField, String>())
        private set

    private val _closed = Channel<Unit>(Channel.CONFLATED)

    /** The dex was deleted: leave its screen. */
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    // One entry by hand

    fun addEntry() = storeDraft(EntryDraft())

    /** Another colour of [entry]: its number, name and series filled in. */
    fun addVariant(entry: DexEntry) = storeDraft(
        EntryDraft(row = DexRow(number = entry.number, name = entry.name, series = entry.series, seriesNumber = entry.seriesNumber)),
    )

    fun editEntry(item: CatalogItemEntity) = storeDraft(
        EntryDraft(
            itemId = item.id,
            row = DexRow(item.number, item.name, item.series, item.seriesNumber, item.variantLabel),
            currentPhoto = item.imagePath,
        ),
    )

    fun updateDraft(field: RowField?, change: (DexRow) -> DexRow) {
        val current = draft ?: return
        storeDraft(current.copy(row = change(current.row)))
        if (field != null && field in draftErrors) draftErrors = draftErrors - field
        lastAdded = null
    }

    fun closeEntry() {
        draft?.photo?.let(photos::delete)
        storeDraft(null)
        draftErrors = emptyMap()
        lastAdded = null
    }

    /** New entries keep the sheet open for the next one, with the series kept (web: `AddItemDialog`). */
    fun saveEntry() {
        val current = draft ?: return
        if (savingEntry || processingPhoto) return
        draftErrors = DexCustom.checkRow(current.row)
        if (draftErrors.isNotEmpty()) return

        savingEntry = true
        viewModelScope.launch {
            try {
                if (current.isNew) {
                    val item = custom.addOne(catalogId, current.row, current.photo)
                    if (item == null) {
                        draftErrors = mapOf(RowField.Name to "Entri itu sudah ada di dex ini.")
                    } else {
                        lastAdded = "#${item.number} ${item.name}" + (item.variantLabel?.let { " ($it)" } ?: "") + " ditambahkan."
                        storeDraft(EntryDraft(row = DexRow(series = current.row.series)))
                    }
                } else {
                    val photo = when {
                        current.photo != null -> PhotoEdit.Replace(current.photo)
                        current.removePhoto -> PhotoEdit.Remove
                        else -> PhotoEdit.Keep
                    }
                    custom.edit(current.itemId, current.row, photo)
                    storeDraft(null)
                    messages.show("Perubahan disimpan.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DexCustomException) {
                draftErrors = mapOf(RowField.Name to e.message.orEmpty())
            } catch (e: Exception) {
                messages.show("Gagal menyimpan: ${e.message ?: e::class.simpleName}")
            } finally {
                savingEntry = false
            }
        }
    }

    fun deleteEntry(item: CatalogItemEntity) {
        viewModelScope.launch {
            custom.delete(item.id)
            messages.show("#${item.number} ${item.name} dihapus.")
        }
    }

    // The entry's photo

    /** Where the camera app should write the photo; remembered in case this process is killed meanwhile. */
    fun cameraTarget(key: String): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[key] = file.path
        return uri
    }

    fun cameraUnavailable(key: String) {
        saved.get<String>(key)?.let { File(it).delete() }
        saved[key] = null
        messages.show("Tidak ada aplikasi kamera di perangkat ini.")
    }

    fun onEntryCamera(success: Boolean) {
        val file = takeCameraFile(ENTRY_CAMERA) ?: return
        if (success && file.length() > 0) setPhoto { photos.addFromCamera(file) } else file.delete()
    }

    fun pickEntryPhoto(uri: Uri) = setPhoto { photos.addFromUri(uri) }

    fun removeEntryPhoto() {
        val current = draft ?: return
        current.photo?.let(photos::delete)
        storeDraft(current.copy(photo = null, removePhoto = current.currentPhoto != null))
    }

    private fun setPhoto(load: () -> String) {
        processingPhoto = true
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { load() }
                val current = draft
                if (current == null) {
                    photos.delete(name)
                } else {
                    current.photo?.let(photos::delete)
                    storeDraft(current.copy(photo = name, removePhoto = false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show((e as? PhotoException)?.message ?: "Foto gagal diproses (${e.message ?: e::class.simpleName}).")
            } finally {
                processingPhoto = false
            }
        }
    }

    // Many at once

    fun openImport(open: Boolean) {
        importing = open
        saved[IMPORTING] = open
        importNote = null
    }

    /** In the open sheet when it is open: the snackbar would be behind it. */
    private fun note(message: String) {
        if (importing) importNote = message else messages.show(message)
    }

    /** Checklist screenshots from the gallery, read by the AI. */
    fun readChecklist(uris: List<Uri>) {
        if (uris.isEmpty()) return
        readChecklist { uris.take(MAX_CHECKLIST_IMAGES).map(photos::screenshot) }
        if (uris.size > MAX_CHECKLIST_IMAGES) note("Maksimal $MAX_CHECKLIST_IMAGES screenshot sekali baca; $MAX_CHECKLIST_IMAGES pertama yang dibaca.")
    }

    /** A photo of a printed checklist. */
    fun onChecklistCamera(success: Boolean) {
        val file = takeCameraFile(CHECKLIST_CAMERA) ?: return
        if (success && file.length() > 0) readChecklist { listOf(photos.screenshot(file)) } else file.delete()
    }

    private fun readChecklist(load: () -> List<ByteArray>) {
        if (busy != null) return
        busy = ImportBusy.Checklist
        viewModelScope.launch {
            try {
                val images = withContext(Dispatchers.IO) { load() }
                val catalog = dex.catalog(catalogId) ?: return@launch
                val rows = checklist.read(catalog, images)
                if (rows.isEmpty()) {
                    note("AI tidak menemukan rilisan di gambar itu.")
                } else {
                    val known = custom.known(catalogId, rows)
                    storeReview(ChecklistReview(rows, known, rows.indices.filterNot { known[it] }.toSet()))
                    openImport(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                note(e.message.orEmpty())
            } catch (e: PhotoException) {
                note(e.message.orEmpty())
            } catch (e: Exception) {
                note("Gagal membaca checklist (${e.message ?: e::class.simpleName}).")
            } finally {
                busy = null
            }
        }
    }

    fun toggleReview(index: Int) {
        val current = review ?: return
        if (current.known.getOrElse(index) { true }) return
        storeReview(current.copy(selected = if (index in current.selected) current.selected - index else current.selected + index))
    }

    fun closeReview() = storeReview(null)

    fun confirmReview() {
        val current = review ?: return
        if (busy != null) return
        busy = ImportBusy.Checklist
        viewModelScope.launch {
            try {
                val result = custom.add(catalogId, current.selected.sorted().map(current.rows::get))
                storeReview(null)
                messages.show(result.summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal menambahkan: ${e.message ?: e::class.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    fun importSpreadsheet(uri: Uri) {
        if (busy != null) return
        busy = ImportBusy.Spreadsheet
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > MAX_SHEET_BYTES) throw DexCustomException("File maksimal 5 MB.")
                        }
                        out.toByteArray()
                    } ?: throw DexCustomException("File tidak bisa dibuka.")
                }
                messages.show(custom.fromSpreadsheet(catalogId, bytes).summary)
                openImport(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DexCustomException) {
                note(e.message.orEmpty())
            } catch (e: Exception) {
                note("Gagal mengimpor (${e.message ?: e::class.simpleName}).")
            } finally {
                busy = null
            }
        }
    }

    fun fromCollection() {
        if (busy != null) return
        busy = ImportBusy.Collection
        viewModelScope.launch {
            try {
                val result = custom.fromCollection(catalogId)
                val brand = dex.catalog(catalogId)?.brand.orEmpty()
                messages.show(
                    if (result.linked > 0) {
                        "${result.added} entri ditambahkan · ${result.linked} diecast tertaut."
                    } else {
                        "Belum ada diecast $brand di koleksimu yang bisa dimasukkan."
                    },
                )
                openImport(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal mengambil dari koleksi: ${e.message ?: e::class.simpleName}")
            } finally {
                busy = null
            }
        }
    }

    // The dex itself

    fun editCatalog() {
        viewModelScope.launch {
            val catalog = dex.catalog(catalogId) ?: return@launch
            storeCatalogDraft(CatalogDraft(catalog.id, catalog.brand, catalog.name, catalog.year?.toString().orEmpty()))
        }
    }

    fun updateCatalogDraft(field: CatalogField, change: (CatalogDraft) -> CatalogDraft) {
        val current = catalogDraft ?: return
        storeCatalogDraft(change(current))
        if (field in catalogErrors) catalogErrors = catalogErrors - field
    }

    fun closeCatalog() {
        storeCatalogDraft(null)
        catalogErrors = emptyMap()
    }

    fun saveCatalog() {
        val current = catalogDraft ?: return
        viewModelScope.launch {
            catalogErrors = custom.checkCatalog(current.brand, current.name, current.year, catalogId)
            if (catalogErrors.isNotEmpty()) return@launch
            custom.update(catalogId, current.brand, current.name, current.year)
            closeCatalog()
            messages.show("Dex disimpan.")
        }
    }

    fun deleteCatalog() {
        viewModelScope.launch {
            val name = dex.catalog(catalogId)?.name
            dexSync.delete(catalogId)
            messages.show("$name dihapus dari Dex.")
            _closed.send(Unit)
        }
    }

    private fun takeCameraFile(key: String): File? {
        val file = saved.get<String>(key)?.let(::File) ?: return null
        saved[key] = null
        return file
    }

    private fun storeDraft(next: EntryDraft?) {
        draft = next
        saved[DRAFT] = next?.let { Json.encodeToString(it) }
    }

    private fun storeReview(next: ChecklistReview?) {
        review = next
        saved[REVIEW] = next?.let { Json.encodeToString(it) }
    }

    private fun storeCatalogDraft(next: CatalogDraft?) {
        catalogDraft = next
        saved[CATALOG] = next?.let { Json.encodeToString(it) }
    }

    companion object {
        const val ENTRY_CAMERA = "entry_camera_file"
        const val CHECKLIST_CAMERA = "checklist_camera_file"
        private const val DRAFT = "entry_draft"
        private const val REVIEW = "checklist_review"
        private const val CATALOG = "catalog_draft"
        private const val IMPORTING = "importing"
        private const val MAX_SHEET_BYTES = 5 * 1024 * 1024

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { CustomDexViewModel(app, createSavedStateHandle(), dex, dexCustom, checklist, dexSync, photos, messages) }
            }
        }
    }
}
