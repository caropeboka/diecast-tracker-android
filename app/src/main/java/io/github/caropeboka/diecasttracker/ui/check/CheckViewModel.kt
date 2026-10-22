package io.github.caropeboka.diecasttracker.ui.check

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
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import io.github.caropeboka.diecasttracker.data.scan.StoreCheck
import io.github.caropeboka.diecasttracker.data.scan.StoreCheckException
import io.github.caropeboka.diecasttracker.data.scan.Verdict
import io.github.caropeboka.diecasttracker.data.scan.verdictOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * One check on the screen: a photo the AI read, or a name typed in (no AI). Kept in the saved state, so the list
 * survives the process being killed while the camera is open; the matches themselves are read live by id.
 */
@Serializable
data class CheckResult(
    /** Unique on the screen: the check's id, or a negative number for a typed search. */
    val key: Long,
    /** The store check ([io.github.caropeboka.diecasttracker.data.db.ScanItemEntity]); 0 for a typed search. */
    val checkId: Long = 0,
    val photo: String? = null,
    val fields: ScanFields? = null,
    val failed: Boolean = false,
    val error: String? = null,
    /** What was typed (no AI). */
    val query: String? = null,
    val matchIds: List<Long> = emptyList(),
    /** "Harga di rak", digits only. */
    val price: String = "",
    /** Where it was logged as seen; null until then. */
    val loggedAt: String? = null,
    /** The "Ditemui" diecast logging a new one created. */
    val createdId: Long? = null,
    /** Saved through the form, which took the photo. */
    val saved: Boolean = false,
) {
    /** Nothing left to do with it but the one-tap actions on its matches. */
    val done get() = loggedAt != null || saved

    val name: String? get() = fields?.name ?: query
}

sealed interface CheckEvent {
    data object OpenCamera : CheckEvent
}

/** "Cek di toko" (web: `diecasts/check.tsx`). */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckViewModel(
    private val saved: SavedStateHandle,
    private val store: StoreCheck,
    private val repository: DiecastRepository,
    private val photos: PhotoStore,
    private val prefs: UserPrefs,
    private val messages: Messages,
    scanner: DiecastScanner,
) : ViewModel() {
    private val _results = MutableStateFlow(
        saved.get<String>(RESULTS)?.let { runCatching { Json.decodeFromString<List<CheckResult>>(it) }.getOrNull() }.orEmpty(),
    )

    /** Newest first. */
    val results: StateFlow<List<CheckResult>> = _results

    /** Every diecast the results point at, live. */
    val diecasts: StateFlow<Map<Long, DiecastListItem>> = _results
        .map { all -> all.flatMap { it.matchIds + listOfNotNull(it.createdId) }.toSet() }
        .distinctUntilChanged()
        .flatMapLatest { ids -> repository.observeItems(ids) }
        .map { items -> items.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Null while loading, so the "AI off" note doesn't flash. */
    val enabled: StateFlow<Boolean?> = scanner.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val places: StateFlow<List<String>> =
        repository.observePlaces().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var place by mutableStateOf(saved.get<String>(PLACE)?.let { runCatching { Json.decodeFromString<PlaceValue>(it) }.getOrNull() } ?: PlaceValue())
        private set
    var checking by mutableStateOf(false)
        private set
    var busy by mutableStateOf(emptySet<Long>())
        private set
    var query by mutableStateOf(saved.get<String>(QUERY).orEmpty())
        private set

    private val _events = Channel<CheckEvent>(Channel.BUFFERED)
    val events: Flow<CheckEvent> = _events.receiveAsFlow()

    init {
        if (saved.get<String>(PLACE) == null) {
            // Still at the store picked on the map under 3 hours ago (web: `readSavedPlace`).
            viewModelScope.launch { prefs.recentPlace()?.let { if (place.name.isEmpty()) changePlace(it) } }
        }
        // Straight to the camera: the collector is standing at the shelf.
        if (saved.get<Boolean>(CAMERA_OPENED) != true) {
            saved[CAMERA_OPENED] = true
            viewModelScope.launch { if (scanner.isEnabled()) _events.send(CheckEvent.OpenCamera) }
        }
    }

    fun changePlace(value: PlaceValue) {
        place = value
        saved[PLACE] = Json.encodeToString(value)
    }

    fun changeQuery(value: String) {
        query = value
        saved[QUERY] = value
    }

    fun setPrice(key: Long, digits: String) = change(key) { it.copy(price = digits) }

    /** Back on the screen (from the form, say): photo checks it saved are done with. */
    fun refresh() {
        viewModelScope.launch {
            val waiting = _results.value.filter { it.checkId != 0L && !it.done }
            val saved = waiting.filterNot { store.isOpen(it.checkId) }.map { it.key }.toSet()
            if (saved.isEmpty()) return@launch
            _results.update { all -> all.map { if (it.key in saved) it.copy(saved = true) else it } }
            persist()
        }
    }

    // Checking

    /** Where the camera app writes the photo; remembered in case this process is killed meanwhile. */
    fun cameraTarget(): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[CAMERA] = file.path
        return uri
    }

    fun onCameraResult(success: Boolean) {
        val file = saved.get<String>(CAMERA)?.let(::File) ?: return
        saved[CAMERA] = null
        if (!success || file.length() == 0L) {
            file.delete()
            return
        }
        checking = true
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { photos.addFromCamera(file) }
                add(result(store.check(photo)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoException) {
                messages.show(e.message.orEmpty())
            } catch (e: Exception) {
                messages.show("Foto gagal diproses (${e.message ?: e::class.simpleName}).")
            } finally {
                checking = false
            }
        }
    }

    fun cameraUnavailable() {
        saved.get<String>(CAMERA)?.let { File(it).delete() }
        saved[CAMERA] = null
        messages.show("Tidak ada aplikasi kamera di perangkat ini.")
    }

    /** Typed instead of photographed: no AI needed, works without a connection. */
    fun search() {
        val text = query.trim()
        if (text.length < 2) {
            messages.show("Ketik minimal 2 huruf.")
            return
        }
        viewModelScope.launch {
            val found = store.search(text)
            add(CheckResult(key = -System.currentTimeMillis(), query = text, matchIds = found.map { it.id }))
            changeQuery("")
        }
    }

    fun retry(result: CheckResult) = act(result.key) {
        val item = store.retry(result.checkId)
        if (item == null) {
            messages.show("Foto ini sudah tidak ada.")
        } else {
            val read = result(item)
            change(result.key) { it.copy(fields = read.fields, failed = read.failed, error = read.error, matchIds = read.matchIds) }
            if (read.failed) messages.show(read.error ?: "Masih belum terbaca.")
        }
    }

    private suspend fun result(item: ScanItemEntity): CheckResult {
        val fields = item.fields
        return CheckResult(
            key = item.id,
            checkId = item.id,
            photo = item.photoFile,
            fields = fields,
            failed = item.status == ScanItemStatus.Failed,
            error = item.error,
            matchIds = fields?.let { store.matches(it).map(DiecastListItem::id) }.orEmpty(),
        )
    }

    // What the shelf turned out to hold

    /** Bought one more of the same. */
    fun addOne(result: CheckResult, match: DiecastListItem) = act(result.key) {
        repository.addOne(match.id)?.let { messages.show("${it.name}: sekarang punya ${it.quantity}×") }
    }

    /** Got a wishlist or spotted one: in the garage from today, bought here. */
    fun acquire(result: CheckResult, diecastId: Long) = act(result.key) {
        repository.acquire(diecastId, place, LocalDate.now())?.let { messages.show("${it.name} dipindah ke koleksi 🎉") }
    }

    /** "Ditemui" here: on [match], or as a new "Ditemui" diecast. */
    fun spot(result: CheckResult, match: DiecastListItem? = null) = act(result.key) {
        val today = LocalDate.now()
        val price = result.price.toLongOrNull()?.takeIf { it <= MAX_MONEY }
        val checkId = result.checkId.takeIf { it != 0L }
        try {
            val createdId = if (match != null) {
                store.spotExisting(match.id, place, price, today, checkId, result.photo)
                null
            } else {
                store.spotNew(result.fields ?: ScanFields(name = result.query), result.photo != null, place, price, today, checkId, result.photo)
            }
            val here = place.name.trim().ifEmpty { "tempat ini" }
            change(result.key) { it.copy(loggedAt = here, createdId = createdId) }
            messages.show("${match?.name ?: result.name} dicatat sebagai temuan.")
        } catch (e: StoreCheckException) {
            messages.show(e.message.orEmpty())
        }
    }

    /** Runs [block] for one result at a time; errors become a message. */
    private fun act(key: Long, block: suspend () -> Unit) {
        if (key in busy) return
        busy = busy + key
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal (${e.message ?: e::class.simpleName}).")
            } finally {
                busy = busy - key
            }
        }
    }

    private fun add(result: CheckResult) {
        _results.update { (listOf(result) + it).take(MAX_RESULTS) }
        persist()
    }

    private fun change(key: Long, change: (CheckResult) -> CheckResult) {
        _results.update { all -> all.map { if (it.key == key) change(it) else it } }
        persist()
    }

    private fun persist() {
        saved[RESULTS] = Json.encodeToString(_results.value)
    }

    companion object {
        /** Older checks drop off the screen (web: 10). */
        const val MAX_RESULTS = 10

        private const val RESULTS = "results"
        private const val PLACE = "place"
        private const val QUERY = "query"
        private const val CAMERA = "camera_file"
        private const val CAMERA_OPENED = "camera_opened"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) {
                    CheckViewModel(createSavedStateHandle(), storeCheck, repository, photos, prefs, messages, scanner)
                }
            }
        }
    }
}

/** The verdict without the "Ditemui" entry the check itself created. */
fun CheckResult.verdict(diecasts: Map<Long, DiecastListItem>): Verdict =
    verdictOf(failed, matchIds.mapNotNull(diecasts::get))
