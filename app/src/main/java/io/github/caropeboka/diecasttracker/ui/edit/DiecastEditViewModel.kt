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
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_PHOTOS
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
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
}

class DiecastEditViewModel(
    private val saved: SavedStateHandle,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val messages: Messages,
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

    val isDirty get() = form != original

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
        } else if (isNew) {
            val status = DiecastStatus.fromKey(route.status) ?: DiecastStatus.Owned
            reset(DiecastForm.new(status, LocalDate.now()))
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
                loading = false
            }
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

    // Saving

    fun save(addAnother: Boolean) {
        if (saving || processingPhotos > 0 || loading) return

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
                    selectedPhoto = 0
                    _events.send(EditEvent.ScrollToTop)
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
    }

    companion object {
        private const val FORM = "form"
        private const val ORIGINAL = "original"
        private const val CAMERA = "camera_file"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                DiecastEditViewModel(createSavedStateHandle(), app.container.repository, app.container.photos, app.container.messages)
            }
        }
    }
}
