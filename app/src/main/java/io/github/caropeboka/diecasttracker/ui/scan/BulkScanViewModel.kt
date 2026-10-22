package io.github.caropeboka.diecasttracker.ui.scan

import android.app.Application
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
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
import io.github.caropeboka.diecasttracker.data.scan.ScanWork
import io.github.caropeboka.diecasttracker.data.scan.ScanWorkState
import io.github.caropeboka.diecasttracker.data.scan.isSafe
import io.github.caropeboka.diecasttracker.ui.edit.Suggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** A queued photo and, once read, the look-alikes already in the collection. */
data class QueueRow(val item: ScanItemEntity, val matches: List<DiecastListItem> = emptyList()) {
    val isSafe get() = item.isSafe(matches.isNotEmpty())
}

sealed interface BulkScanEvent {
    data object OpenCamera : BulkScanEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class BulkScanViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val queue: ScanQueue,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val matcher: CollectionMatcher,
    private val prefs: UserPrefs,
    private val messages: Messages,
    scanner: DiecastScanner,
) : ViewModel() {
    /** Null while loading. */
    val rows: StateFlow<List<QueueRow>?> = queue.observe()
        .mapLatest { items ->
            items.map { item ->
                val fields = item.fields
                if (item.status == ScanItemStatus.Done && fields != null) QueueRow(item, matcher.find(fields, 3)) else QueueRow(item)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val work: StateFlow<ScanWorkState> =
        ScanWork.observe(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanWorkState.Idle)
    val paused: StateFlow<Boolean> = prefs.bulkScanPaused.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val enabled: StateFlow<Boolean> = scanner.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val suggestions: StateFlow<Suggestions> = combine(
        repository.observePlaces(),
        repository.observeStorageLocations(),
        repository.observeTagNames(),
    ) { places, storage, tags -> Suggestions(places = places, storageLocations = storage, tags = tags) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Suggestions())

    var defaults by mutableStateOf(QueueDefaults(date = LocalDate.now().toString()))
        private set

    /** Quick edits per queued photo id. */
    var edits by mutableStateOf(emptyMap<Long, QuickEdits>())
        private set

    /** Photos being prepared before they join the queue. */
    var adding by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(emptySet<Long>())
        private set
    var savingAll by mutableStateOf(false)
        private set

    private val _events = Channel<BulkScanEvent>(Channel.BUFFERED)
    val events: Flow<BulkScanEvent> = _events.receiveAsFlow()

    init {
        val restored = saved.get<String>(DEFAULTS)?.let { runCatching { Json.decodeFromString<QueueDefaults>(it) }.getOrNull() }
        if (restored != null) {
            defaults = restored
        } else {
            // Still at the store picked on the map under 3 hours ago? Start from there (web: `recentSavedPlace`).
            viewModelScope.launch { prefs.recentPlace()?.let { updateDefaults(defaults.copy(place = it)) } }
        }
        edits = saved.get<String>(EDITS)?.let { runCatching { Json.decodeFromString<Map<Long, QuickEdits>>(it) }.getOrNull() }.orEmpty()

        // Photos left waiting (a key was added, the phone restarted…): carry on.
        viewModelScope.launch { if (queue.hasPending() && !prefs.bulkScanPaused.first()) ScanWork.start(app) }
    }

    fun updateDefaults(next: QueueDefaults) {
        defaults = next
        saved[DEFAULTS] = Json.encodeToString(next)
    }

    fun setStatus(status: DiecastStatus) = updateDefaults(defaults.copy(status = status))

    fun setPlace(place: PlaceValue) = updateDefaults(defaults.copy(place = place))

    fun edit(id: Long, change: (QuickEdits) -> QuickEdits) {
        edits = edits + (id to change(edits[id] ?: QuickEdits()))
        saved[EDITS] = Json.encodeToString(edits)
    }

    private fun forget(id: Long) {
        edits = edits - id
        saved[EDITS] = Json.encodeToString(edits)
    }

    // Adding photos

    fun addFromGallery(uris: List<Uri>) = addPhotos(uris.map { uri -> { photos.addFromUri(uri) } })

    /** Where the camera app writes the next photo; remembered in case this process is killed meanwhile. */
    fun cameraTarget(): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[CAMERA] = file.path
        return uri
    }

    /** A photo taken: queued, and the camera opens again for the next car until the collector backs out. */
    fun onCameraResult(success: Boolean) {
        val file = saved.get<String>(CAMERA)?.let(::File) ?: return
        saved[CAMERA] = null
        if (success && file.length() > 0) {
            addPhotos(listOf { photos.addFromCamera(file) })
            _events.trySend(BulkScanEvent.OpenCamera)
        } else {
            file.delete()
        }
    }

    fun cameraUnavailable() {
        saved.get<String>(CAMERA)?.let { File(it).delete() }
        saved[CAMERA] = null
        messages.show("Tidak ada aplikasi kamera di perangkat ini.")
    }

    private fun addPhotos(loads: List<() -> String>) {
        if (loads.isEmpty()) return
        adding += loads.size
        viewModelScope.launch {
            for (load in loads) {
                try {
                    queue.add(withContext(Dispatchers.IO) { load() })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PhotoException) {
                    messages.show(e.message.orEmpty())
                } catch (e: Exception) {
                    messages.show("Foto gagal diproses (${e.message ?: e::class.simpleName}).")
                } finally {
                    adding--
                }
            }
            if (!prefs.bulkScanPaused.first()) ScanWork.start(app)
        }
    }

    // The AI's reading

    fun setPaused(value: Boolean) {
        viewModelScope.launch {
            prefs.setBulkScanPaused(value)
            if (value) ScanWork.stop(app) else ScanWork.start(app)
        }
    }

    fun retry(id: Long) {
        viewModelScope.launch {
            queue.retry(id)
            if (!prefs.bulkScanPaused.first()) ScanWork.start(app)
        }
    }

    // Saving

    fun discard(id: Long) {
        viewModelScope.launch {
            queue.discard(id)
            forget(id)
        }
    }

    fun save(row: QueueRow) {
        if (row.item.id in busy) return
        viewModelScope.launch {
            if (saveOne(row)) messages.show("${nameOf(row)} disimpan.")
        }
    }

    /** Everything the AI was sure of and that looks new (web: "Simpan semua yang yakin"). */
    fun saveAll() {
        if (savingAll) return
        val safe = rows.value.orEmpty().filter { it.isSafe }
        savingAll = true
        viewModelScope.launch {
            var count = 0
            try {
                for (row in safe) if (saveOne(row)) count++
            } finally {
                savingAll = false
            }
            messages.show("$count diecast disimpan ke koleksi.")
        }
    }

    private fun nameOf(row: QueueRow) = edits[row.item.id]?.name ?: row.item.fields?.name ?: "Foto"

    private suspend fun saveOne(row: QueueRow): Boolean {
        val id = row.item.id
        busy = busy + id
        try {
            val today = LocalDate.now()
            val form = queuedForm(row.item, edits[id] ?: QuickEdits(), defaults, today)
            val problem = form.validate(today).values.firstOrNull()
            if (problem != null) {
                messages.show("${nameOf(row)}: $problem")
                return false
            }
            val sighting = form.firstSighting(today).takeIf { form.status == DiecastStatus.Spotted }
            repository.save(form.toEntity(null), form.photos, form.tags, sighting)
            queue.saved(id)
            forget(id)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messages.show("${nameOf(row)}: gagal menyimpan (${e.message ?: e::class.simpleName}).")
            return false
        } finally {
            busy = busy - id
        }
    }

    companion object {
        private const val DEFAULTS = "defaults"
        private const val EDITS = "edits"
        private const val CAMERA = "camera_file"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) {
                    BulkScanViewModel(app, createSavedStateHandle(), scanQueue, repository, photos, matcher, prefs, messages, scanner)
                }
            }
        }
    }
}
