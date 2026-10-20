package io.github.caropeboka.diecasttracker.ui.edit

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.caropeboka.diecasttracker.data.COMMON_BRANDS
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_PHOTOS
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import io.github.caropeboka.diecasttracker.data.ai.Confidence
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.dex.DexLinkState
import io.github.caropeboka.diecasttracker.data.dex.DexRef
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.LinkAction
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.ui.DiecastEditRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** Values used before, offered while typing (the web form's datalists). */
data class Suggestions(
    val brands: List<String> = COMMON_BRANDS,
    val series: List<String> = emptyList(),
    val places: List<String> = emptyList(),
    val storageLocations: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

sealed interface EditEvent {
    data object Close : EditEvent

    data object ScrollToTop : EditEvent

    /** Opened with the Scan button: take the photo right away. */
    data object OpenCamera : EditEvent
}

sealed interface ScanState {
    data object Idle : ScanState

    data object Scanning : ScanState

    /** [matches]: diecasts already in the collection that look like this one; [dex]: the entry it most likely is (new diecasts). */
    data class Done(val confidence: Confidence, val matches: List<DiecastListItem>, val dex: DexRef? = null) : ScanState

    /** [retryLater]: the AI is busy or out of quota, nothing is wrong with the photo. */
    data class Failed(val message: String, val retryLater: Boolean) : ScanState
}

class DiecastEditViewModel(
    private val saved: SavedStateHandle,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val messages: Messages,
    private val scanner: DiecastScanner,
    private val matcher: CollectionMatcher,
    private val prefs: UserPrefs,
    private val dex: DexRepository,
) : ViewModel() {
    private val route = saved.toRoute<DiecastEditRoute>()
    val isNew = route.id == 0L

    var form by mutableStateOf(DiecastForm())
        private set
    private var original by mutableStateOf(DiecastForm())
    var errors by mutableStateOf(emptyMap<Field, String>())
        private set
    var loading by mutableStateOf(!isNew)
        private set
    var saving by mutableStateOf(false)
        private set
    var processingPhotos by mutableIntStateOf(0)
        private set
    var photoError by mutableStateOf<String?>(null)
        private set
    var selectedPhoto by mutableIntStateOf(0)
        private set

    /** The saved record (edit only). */
    var existing by mutableStateOf<DiecastEntity?>(null)
        private set
    var sightings by mutableStateOf(emptyList<SightingEntity>())
        private set

    /** The "Catat temuan" sheet's entry; null while the sheet is closed. */
    var sightingDraft by mutableStateOf<SightingDraft?>(null)
        private set
    var sightingErrors by mutableStateOf(emptyMap<SightingField, String>())
        private set
    var savingSighting by mutableStateOf(false)
        private set
    var processingSightingPhoto by mutableStateOf(false)
        private set

    val isDirty get() = form != original

    var scan by mutableStateOf<ScanState>(ScanState.Idle)
        private set

    /** Fields the AI filled in that haven't been changed since (marked ✦). */
    var aiFields by mutableStateOf(emptySet<Field>())
        private set

    val scanning get() = scan == ScanState.Scanning

    /** Edit: the diecast's dex entry or suggestions; changed right away, apart from the form. */
    var dexLink by mutableStateOf<DexLinkState?>(null)
        private set
    var dexBusy by mutableStateOf(false)
        private set

    /** New: the entry [DiecastForm.catalogItemId] points at. */
    var dexChosen by mutableStateOf<DexRef?>(null)
        private set

    val scanEnabled: StateFlow<Boolean> =
        scanner.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _events = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = _events.receiveAsFlow()

    val suggestions: StateFlow<Suggestions> = combine(
        repository.observeBrands().map { (it + COMMON_BRANDS).distinctBy(String::lowercase) },
        repository.observeSeries(),
        repository.observePlaces(),
        repository.observeStorageLocations(),
        repository.observeTagNames(),
        ::Suggestions,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Suggestions())

    init {
        val restored = saved.get<String>(FORM)?.let { Json.decodeFromString<DiecastForm>(it) }
        if (restored != null) {
            form = restored
            original = saved.get<String>(ORIGINAL)?.let { Json.decodeFromString<DiecastForm>(it) } ?: restored
            aiFields = saved.get<List<String>>(AI_FIELDS).orEmpty().mapNotNull { name -> Field.entries.find { it.name == name } }.toSet()
            restored.catalogItemId?.let { id -> viewModelScope.launch { dexChosen = dex.item(id)?.let(::DexRef) } }
        } else if (isNew) {
            val status = DiecastStatus.fromKey(route.status) ?: DiecastStatus.Owned
            reset(DiecastForm.new(status, LocalDate.now()))
            if (route.dexItem != 0L) startFromDex(route.dexItem)
            startAtRecentPlace()
        }
        sightingDraft = saved.get<String>(SIGHTING)?.let { Json.decodeFromString<SightingDraft>(it) }

        if (route.scan && saved.get<Boolean>(CAMERA_OPENED) != true) {
            saved[CAMERA_OPENED] = true
            _events.trySend(EditEvent.OpenCamera)
        }

        if (!isNew) {
            viewModelScope.launch {
                val details = repository.details(route.id)
                if (details == null) {
                    messages.show("Diecast tidak ditemukan.")
                    _events.send(EditEvent.Close)
                    return@launch
                }
                existing = details.diecast
                sightings = details.orderedSightings
                if (restored == null) reset(DiecastForm.from(details, LocalDate.now()))
                dexLink = dex.linkState(details.diecast)
                loading = false
                repository.observeSightings(route.id).collect { sightings = it }
            }
        }
    }

    /** A new diecast starts at the store chosen on the map under 3 hours ago, untouched (not "dirty"). */
    private fun startAtRecentPlace() {
        viewModelScope.launch {
            val here = prefs.recentPlace() ?: return@launch
            if (form == original) reset(form.at(here))
        }
    }

    /** Added from a dex entry: the form starts the way its card prints it, linked to it. */
    private fun startFromDex(itemId: Long) {
        viewModelScope.launch {
            val row = dex.item(itemId) ?: return@launch
            reset(form.withDex(dex.prefill(row), itemId))
            dexChosen = DexRef(row)
        }
    }

    fun update(change: (DiecastForm) -> DiecastForm) {
        replaceForm(change(form))
    }

    fun changeStatus(status: DiecastStatus) {
        update { it.withStatus(status, isNew, LocalDate.now()) }
    }

    fun clearError(field: Field) {
        if (field in errors) errors = errors - field
    }

    /** The user changed [field]: its error and its AI mark go. */
    fun fieldEdited(field: Field) {
        clearError(field)
        if (field in aiFields) markAiFields(aiFields - field)
    }

    // AI scan

    /** Lets the AI read the photos and fill the form, and looks for look-alikes already in the collection. */
    fun runScan() {
        if (scanning || form.photos.isEmpty()) return

        scan = ScanState.Scanning
        viewModelScope.launch {
            scan = try {
                val fields = scanner.scan(form.photos)
                val (next, filled) = form.withScan(fields)
                replaceForm(next)
                markAiFields(filled)
                errors = errors - filled
                ScanState.Done(
                    fields.confidence,
                    matcher.find(fields).filter { it.id != existing?.id },
                    if (isNew) dex.suggestFor(next.toEntity(null)) else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                ScanState.Failed(e.message.orEmpty(), retryLater = e is AiRetryLaterException)
            } catch (e: Exception) {
                ScanState.Failed("Scan gagal (${e.message ?: e::class.simpleName}). Isi manual dulu atau coba lagi.", retryLater = false)
            }
        }
    }

    // Dex (web: `dex-link-panel.tsx`)

    /** New diecast: link it to [ref] on save (or not, with null). */
    fun chooseDex(ref: DexRef?) {
        dexChosen = ref
        update { it.copy(catalogItemId = ref?.row?.item?.id) }
    }

    /** Takes the entry's release year (the card's year is usually the casting's copyright year). */
    fun useDexYear(year: Int) {
        update { it.copy(year = year.toString()) }
        fieldEdited(Field.Year)
    }

    /** Saved diecast: confirms, changes or drops its entry right away. */
    fun linkDex(action: LinkAction, itemId: Long? = null) {
        val diecast = existing ?: return
        if (dexBusy) return

        dexBusy = true
        viewModelScope.launch {
            try {
                val linked = dex.link(diecast.id, action, itemId) ?: return@launch
                existing = linked
                dexLink = dex.linkState(linked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal mengubah tautan Dex: ${e.message ?: e::class.simpleName}")
            } finally {
                dexBusy = false
            }
        }
    }

    private fun markAiFields(fields: Set<Field>) {
        aiFields = fields
        saved[AI_FIELDS] = ArrayList(fields.map { it.name })
    }

    // Photos

    fun selectPhoto(index: Int) {
        selectedPhoto = index
    }

    fun addFromGallery(uris: List<Uri>) {
        addPhotos(uris.map { uri -> { photos.addFromUri(uri) } })
    }

    /** Where the camera app should write the next photo; remembered in case this process is killed meanwhile. */
    fun cameraTarget(): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[CAMERA] = file.path
        return uri
    }

    fun onCameraResult(success: Boolean) {
        val file = saved.get<String>(CAMERA)?.let(::File) ?: return
        saved[CAMERA] = null
        if (success && file.length() > 0) {
            addPhotos(listOf { photos.addFromCamera(file) })
        } else {
            file.delete()
        }
    }

    fun cameraUnavailable() {
        saved.get<String>(CAMERA)?.let { File(it).delete() }
        saved[CAMERA] = null
        photoError = "Tidak ada aplikasi kamera di perangkat ini."
    }

    private fun addPhotos(loads: List<() -> String>) {
        val room = (MAX_PHOTOS - form.photos.size - processingPhotos).coerceAtLeast(0)
        photoError = if (loads.size > room) "Maksimal $MAX_PHOTOS foto." else null
        val todo = loads.take(room)
        if (todo.isEmpty()) return

        val firstPhotos = form.photos.isEmpty() && processingPhotos == 0
        processingPhotos += todo.size
        viewModelScope.launch {
            for (load in todo) {
                try {
                    val name = withContext(Dispatchers.IO) { load() }
                    update { it.copy(photos = it.photos + name) }
                    clearError(Field.Photos)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PhotoException) {
                    photoError = e.message
                } catch (e: Exception) {
                    photoError = "Foto gagal diproses (${e.message ?: e::class.simpleName})."
                } finally {
                    processingPhotos--
                }
            }

            // First photo(s) of a new diecast: let the AI fill the form straight away.
            if (firstPhotos && isNew && form.photos.isNotEmpty() && scanner.isEnabled()) runScan()
        }
    }

    fun removePhoto(index: Int) {
        val name = form.photos.getOrNull(index) ?: return
        // A photo added in this session goes right away; a saved one only when the change is saved.
        if (photos.isPending(name)) photos.delete(name)
        update { it.copy(photos = it.photos - name) }
        selectedPhoto = 0
    }

    fun makeCover(index: Int) {
        val name = form.photos.getOrNull(index) ?: return
        update { it.copy(photos = listOf(name) + (it.photos - name)) }
        selectedPhoto = 0
    }

    // Sightings ("Catatan temuan"): saved right away, apart from the form (web: `SightingController`)

    fun openSighting() {
        viewModelScope.launch {
            storeSightingDraft(SightingDraft(place = prefs.recentPlace() ?: PlaceValue(), seenOn = LocalDate.now().toString()))
            sightingErrors = emptyMap()
        }
    }

    fun updateSighting(field: SightingField? = null, change: (SightingDraft) -> SightingDraft) {
        val draft = sightingDraft ?: return
        storeSightingDraft(change(draft))
        if (field != null && field in sightingErrors) sightingErrors = sightingErrors - field
    }

    fun sightingCameraTarget(): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[SIGHTING_CAMERA] = file.path
        return uri
    }

    fun onSightingCamera(success: Boolean) {
        val file = saved.get<String>(SIGHTING_CAMERA)?.let(::File) ?: return
        saved[SIGHTING_CAMERA] = null
        if (success && file.length() > 0) setSightingPhoto { photos.addFromCamera(file) } else file.delete()
    }

    fun addSightingPhoto(uri: Uri) = setSightingPhoto { photos.addFromUri(uri) }

    fun removeSightingPhoto() {
        val draft = sightingDraft ?: return
        photos.delete(draft.photo)
        storeSightingDraft(draft.copy(photo = null))
    }

    private fun setSightingPhoto(load: () -> String) {
        processingSightingPhoto = true
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { load() }
                val draft = sightingDraft
                if (draft == null) {
                    photos.delete(name)
                } else {
                    photos.delete(draft.photo)
                    storeSightingDraft(draft.copy(photo = name))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show((e as? PhotoException)?.message ?: "Foto gagal diproses (${e.message ?: e::class.simpleName}).")
            } finally {
                processingSightingPhoto = false
            }
        }
    }

    fun saveSighting() {
        val draft = sightingDraft ?: return
        val diecast = existing ?: return
        if (savingSighting || processingSightingPhoto) return

        val today = LocalDate.now()
        sightingErrors = draft.validate(today)
        if (sightingErrors.isNotEmpty()) return

        savingSighting = true
        viewModelScope.launch {
            try {
                repository.addSighting(draft.toEntity(diecast.id, today, System.currentTimeMillis()))
                storeSightingDraft(null)
                messages.show("Temuan dicatat.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal menyimpan temuan: ${e.message ?: e::class.simpleName}")
            } finally {
                savingSighting = false
            }
        }
    }

    /** Closes the sheet without saving; its photo is thrown away. */
    fun closeSighting() {
        sightingDraft?.photo?.takeIf(photos::isPending)?.let(photos::delete)
        storeSightingDraft(null)
        sightingErrors = emptyMap()
    }

    fun deleteSighting(id: Long) {
        viewModelScope.launch {
            repository.deleteSighting(id)
            messages.show("Temuan dihapus.")
        }
    }

    private fun storeSightingDraft(draft: SightingDraft?) {
        sightingDraft = draft
        saved[SIGHTING] = draft?.let { Json.encodeToString(it) }
    }

    // Saving

    fun save(addAnother: Boolean) {
        if (saving || processingPhotos > 0 || loading || scanning) return

        val today = LocalDate.now()
        errors = form.validate(today)
        if (errors.isNotEmpty()) {
            messages.show("Periksa lagi isian yang ditandai.")
            return
        }

        saving = true
        viewModelScope.launch {
            try {
                val entity = form.toEntity(existing)
                val sighting = if (isNew && form.status == DiecastStatus.Spotted) form.firstSighting(today) else null
                repository.save(entity, form.photos, form.tags, sighting)

                messages.show(
                    when {
                        !isNew -> "Perubahan disimpan."
                        form.status == DiecastStatus.Spotted -> "${entity.name} dicatat sebagai temuan."
                        else -> "${entity.name} ditambahkan ke koleksi."
                    },
                )

                if (addAnother) {
                    reset(DiecastForm.new(form.status, today))
                    dexChosen = null
                    startAtRecentPlace()
                    selectedPhoto = 0
                    _events.send(EditEvent.ScrollToTop)
                    // Scanning a pile of cars: straight on to the next one.
                    if (route.scan) _events.send(EditEvent.OpenCamera)
                } else {
                    original = form
                    _events.send(EditEvent.Close)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal menyimpan: ${e.message ?: e::class.simpleName}")
            } finally {
                saving = false
            }
        }
    }

    fun delete() {
        val diecast = existing ?: return
        viewModelScope.launch {
            repository.delete(diecast.id)
            messages.show("${diecast.name} dihapus.")
            original = form
            _events.send(EditEvent.Close)
        }
    }

    /** Leaves without saving: photos added in this session are thrown away. */
    fun discard() {
        form.photos.filter(photos::isPending).forEach(photos::delete)
        original = form
        viewModelScope.launch { _events.send(EditEvent.Close) }
    }

    private fun replaceForm(next: DiecastForm) {
        form = next
        saved[FORM] = Json.encodeToString(next)
    }

    private fun reset(next: DiecastForm) {
        replaceForm(next)
        original = next
        saved[ORIGINAL] = saved.get<String>(FORM)
        errors = emptyMap()
        photoError = null
        scan = ScanState.Idle
        markAiFields(emptySet())
    }

    companion object {
        private const val FORM = "form"
        private const val ORIGINAL = "original"
        private const val CAMERA = "camera_file"
        private const val CAMERA_OPENED = "scan_camera_opened"
        private const val AI_FIELDS = "ai_fields"
        private const val SIGHTING = "sighting_draft"
        private const val SIGHTING_CAMERA = "sighting_camera_file"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) {
                    DiecastEditViewModel(createSavedStateHandle(), repository, photos, messages, scanner, matcher, prefs, dex)
                }
            }
        }
    }
}
