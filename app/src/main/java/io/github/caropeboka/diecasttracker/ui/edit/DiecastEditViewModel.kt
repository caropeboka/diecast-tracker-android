package io.github.caropeboka.diecasttracker.ui.edit

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
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
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.COMMON_BRANDS
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_PHOTOS
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import io.github.caropeboka.diecasttracker.data.ai.ChatContext
import io.github.caropeboka.diecasttracker.data.ai.Confidence
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.FieldSuggestion
import io.github.caropeboka.diecasttracker.data.ai.MAX_SCAN_PHOTOS
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.dex.DexLinkState
import io.github.caropeboka.diecasttracker.data.dex.DexRef
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.LinkAction
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
import io.github.caropeboka.diecasttracker.data.studio.CardCutout
import io.github.caropeboka.diecasttracker.data.studio.StudioException
import io.github.caropeboka.diecasttracker.data.studio.StudioPhoto
import io.github.caropeboka.diecasttracker.data.studio.StudioStep
import io.github.caropeboka.diecasttracker.ui.DiecastEditRoute
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /**
     * [matches]: diecasts already in the collection that look like this one; [dex]: the entry it most likely is (new
     * diecasts); [by]: the AI that read it when it wasn't Gemini.
     */
    data class Done(val confidence: Confidence, val matches: List<DiecastListItem>, val dex: DexRef? = null, val by: String? = null) : ScanState

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
    private val queue: ScanQueue,
    private val studio: StudioPhoto,
    private val texts: Texts,
) : ViewModel() {
    private val route = saved.toRoute<DiecastEditRoute>()
    val diecastId = route.id
    val isNew = diecastId == 0L

    /** Started from a bulk scan or store check photo: saving takes it out of the queue. */
    val fromQueue = isNew && route.scanItem != 0L

    /** The queue's photo: the form may drop it, but never deletes it (it stays in the queue). */
    private val queuePhoto: String? get() = saved.get<String>(QUEUE_PHOTO)

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

    private val scanLog = AiProgressLog(texts, R.string.ai_hint_quick)

    /** What the running scan is doing, step by step. */
    val scanProgress: StateFlow<AiProgress?> = scanLog.state

    var makingStudio by mutableStateOf(false)
        private set

    /** Studio photos made in this session: copies of another photo, so they don't count as a side of the diecast. */
    var studioPhotos by mutableStateOf(saved.get<List<String>>(STUDIO_PHOTOS).orEmpty().toSet())
        private set

    private val studioLog = AiProgressLog(texts, R.string.ai_hint_studio)
    val studioProgress: StateFlow<AiProgress?> = studioLog.state

    /** The corner editor of a card or box's studio photo; null while closed. */
    var cardCrop by mutableStateOf<CardCrop?>(null)
        private set
    private var cardCropJob: Job? = null

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
            reset(DiecastForm.new(status, LocalDate.now()).let { new -> route.name?.let { new.copy(name = it.trim()) } ?: new })
            if (route.dexItem != 0L) startFromDex(route.dexItem)
            if (fromQueue) startFromQueue(route.scanItem) else startAtRecentPlace()
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
                    messages.show(texts(R.string.edit_not_found))
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

    /**
     * From the bulk scan queue (web: `scan_item`): its photo and what the AI read, marked ✦, with its look-alikes —
     * the starting point, so leaving without changes asks nothing.
     */
    private fun startFromQueue(itemId: Long) {
        viewModelScope.launch {
            val item = queue.item(itemId)
            if (item == null) {
                messages.show(texts(R.string.edit_queue_photo_gone))
                _events.send(EditEvent.Close)
                return@launch
            }
            saved[QUEUE_PHOTO] = item.photoFile

            val here = prefs.recentPlace()
            val start = form.let { if (here != null) it.at(here) else it }.copy(photos = listOf(item.photoFile))
            val fields = item.fields
            if (fields == null) {
                reset(start)
                return@launch
            }
            val (next, filled) = start.withScan(fields)
            reset(next)
            markAiFields(filled)
            scan = ScanState.Done(fields.confidence, matcher.find(fields), dex.suggestFor(next.toEntity(null)), fields.readBy)
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
        if (scanning || makingStudio || form.photos.isEmpty()) return

        scan = ScanState.Scanning
        minOf(form.photos.size, MAX_SCAN_PHOTOS).let { count -> scanLog.start(texts.plural(R.plurals.ai_preparing_photos, count, count)) }
        viewModelScope.launch {
            scan = try {
                // The AI reads the first few: the sides themselves before their studio copies.
                val (copies, sides) = form.photos.partition { it in studioPhotos }
                val fields = scanner.scan(sides + copies, scanLog::onEvent)
                val (next, filled) = form.withScan(fields)
                replaceForm(next)
                markAiFields(filled)
                errors = errors - filled
                scanLog.step(texts(if (isNew) R.string.ai_matching_dex else R.string.ai_matching))
                ScanState.Done(
                    fields.confidence,
                    matcher.find(fields).filter { it.id != existing?.id },
                    if (isNew) dex.suggestFor(next.toEntity(null)) else null,
                    fields.readBy,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                ScanState.Failed(e.message.orEmpty(), retryLater = e is AiRetryLaterException)
            } catch (e: Exception) {
                ScanState.Failed(texts(R.string.scan_failed, e.message ?: e::class.simpleName.orEmpty()), retryLater = false)
            } finally {
                scanLog.stop()
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
                messages.show(texts(R.string.edit_dex_link_failed, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                dexBusy = false
            }
        }
    }

    /**
     * A price check set the market value; it is saved already, so the form shows it without counting it as an
     * unsaved change.
     */
    fun marketValueApplied(diecast: DiecastEntity) {
        existing = diecast
        val value = diecast.marketValue?.toString().orEmpty()
        original = original.copy(marketValue = value)
        saved[ORIGINAL] = Json.encodeToString(original)
        replaceForm(form.copy(marketValue = value))
        clearError(Field.MarketValue)
    }

    private fun markAiFields(fields: Set<Field>) {
        aiFields = fields
        saved[AI_FIELDS] = ArrayList(fields.map { it.name })
    }

    // Tanya AI

    /** What the AI is shown with a question: the photos (real sides first), the fields, the linked Dex entry. */
    fun chatContext(): ChatContext {
        val (copies, sides) = form.photos.partition { it in studioPhotos }
        val entry = (dexLink?.linked ?: dexChosen)?.row?.let { row ->
            val item = row.item
            listOfNotNull(
                "${row.catalogName}${row.catalogYear?.let { " ($it)" }.orEmpty()}",
                "#${item.number}",
                item.name,
                item.variantLabel,
                item.series?.let { series -> series + item.seriesNumber?.let { " $it" }.orEmpty() },
                item.toyCode?.let { "toy $it" },
                "Treasure Hunt".takeIf { item.isTh },
                "Super Treasure Hunt".takeIf { item.isSth },
            ).joinToString(" · ")
        }
        return ChatContext(sides + copies, form.chatFields(), entry)
    }

    /** One of the AI's suggestions into the form, marked ✦ like a scan's; false when it no longer fits. */
    fun applySuggestion(suggestion: FieldSuggestion): Boolean {
        val (next, field) = form.withSuggestion(suggestion.field, suggestion.value, LocalDate.now().year) ?: return false
        replaceForm(next)
        clearError(field)
        markAiFields(aiFields + field)
        return true
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

    /**
     * A photo taken. A new diecast gets every side shot before the AI reads them, so the camera opens again for
     * the next side until the collector backs out (or the photos are full).
     */
    fun onCameraResult(success: Boolean) {
        val file = saved.get<String>(CAMERA)?.let(::File) ?: return
        saved[CAMERA] = null
        if (success && file.length() > 0) {
            addPhotos(listOf { photos.addFromCamera(file) })
            if (isNew && form.photos.size + processingPhotos < MAX_PHOTOS) _events.trySend(EditEvent.OpenCamera)
        } else {
            file.delete()
        }
    }

    fun cameraUnavailable() {
        saved.get<String>(CAMERA)?.let { File(it).delete() }
        saved[CAMERA] = null
        photoError = texts(R.string.edit_no_camera)
    }

    private fun addPhotos(loads: List<() -> String>) {
        val room = (MAX_PHOTOS - form.photos.size - processingPhotos).coerceAtLeast(0)
        photoError = if (loads.size > room) texts(R.string.edit_max_photos, MAX_PHOTOS) else null
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
                    photoError = texts(R.string.edit_photo_failed, e.message ?: e::class.simpleName.orEmpty())
                } finally {
                    processingPhotos--
                }
            }
        }
    }

    fun removePhoto(index: Int) {
        val name = form.photos.getOrNull(index) ?: return
        // A photo added in this session goes right away; a saved one only when the change is saved.
        if (photos.isPending(name) && name != queuePhoto) photos.delete(name)
        update { it.copy(photos = it.photos - name) }
        selectedPhoto = 0
    }

    fun makeCover(index: Int) {
        val name = form.photos.getOrNull(index) ?: return
        update { it.copy(photos = listOf(name) + (it.photos - name)) }
        selectedPhoto = 0
    }

    /**
     * "Foto studio": the diecast on white as the new cover; the photo itself stays. A card or box goes by its corners
     * (the corner editor: the background remover takes a card's printed picture for the subject at times), a loose
     * car is cut out by the background remover.
     */
    fun makeStudioPhoto(index: Int) {
        val name = form.photos.getOrNull(index) ?: return
        if (makingStudio || scanning || processingPhotos > 0 || cardCrop != null) return
        if (form.photos.size >= MAX_PHOTOS) {
            photoError = texts(R.string.edit_studio_full, MAX_PHOTOS)
            return
        }

        photoError = null
        if (form.packaging == Packaging.Loose) cutOut(name) else openCardCrop(name)
    }

    private fun cutOut(name: String) = makeStudio(texts(R.string.studio_preparing)) {
        studio.make(name) { step ->
            when (step) {
                is StudioStep.Downloading -> if (step.fraction == null) {
                    studioLog.step(texts(R.string.studio_downloading))
                } else {
                    studioLog.progress(step.fraction)
                }
                StudioStep.Cutting -> studioLog.step(texts(R.string.studio_cutting))
                StudioStep.Composing -> studioLog.step(texts(R.string.studio_composing))
            }
        }
    }

    private fun openCardCrop(name: String) {
        cardCrop = CardCrop(name, rounded = form.packaging == Packaging.Carded)
        cardCropJob = viewModelScope.launch {
            try {
                val image = studio.load(name)
                cardCrop = cardCrop?.copy(image = image, corners = CardCrop.startCorners(image))
                val guess = studio.guessCorners(image)
                cardCrop = cardCrop?.let { crop ->
                    if (guess != null && !crop.moved) {
                        crop.copy(corners = CardCrop.corners(guess), guessing = false, guessed = true)
                    } else {
                        crop.copy(guessing = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cardCrop = null
                photoError = texts(R.string.edit_photo_open_failed, e.message ?: e::class.simpleName.orEmpty())
            }
        }
    }

    /** A corner dragged to [to] (the photo's pixels), kept on the photo. */
    fun moveCorner(index: Int, to: Offset) {
        val crop = cardCrop ?: return
        val image = crop.image ?: return
        val point = Offset(to.x.coerceIn(0f, image.width.toFloat()), to.y.coerceIn(0f, image.height.toFloat()))
        cardCrop = crop.copy(corners = crop.corners.toMutableList().apply { set(index, point) }, moved = true)
    }

    fun closeCardCrop() {
        cardCropJob?.cancel()
        cardCrop = null
    }

    /** From the corner editor: a loose car after all, cut out by the background remover. */
    fun cutOutInstead() {
        val name = cardCrop?.photo ?: return
        closeCardCrop()
        cutOut(name)
    }

    /** Step two: the card squared up, the hang hole erased if found, to tap the rest of the background away. */
    fun continueCardCrop() {
        val crop = cardCrop ?: return
        val image = crop.image ?: return
        val corners = crop.quad ?: return
        if (crop.working) return
        cardCropJob?.cancel()
        cardCrop = crop.copy(working = true, guessing = false)
        cardCropJob = viewModelScope.launch {
            try {
                val cutout = studio.cutCard(image, corners, crop.rounded)
                cardCrop = cardCrop?.copy(working = false, cutout = cutout, preview = cutout.image, lastTap = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cardCrop = cardCrop?.copy(working = false)
                photoError = e.message
            }
        }
    }

    /** Step two: a tap on the card at [x], [y] (its pixels) erases the background there, or brings an erased part back. */
    fun tapCard(x: Int, y: Int) = changeCard { cutout -> cutout.tap(x, y) }

    fun undoCard() = changeCard { cutout ->
        cutout.undo()
        null
    }

    private fun changeCard(change: (CardCutout) -> CardCutout.Tap?) {
        val crop = cardCrop ?: return
        val cutout = crop.cutout ?: return
        if (crop.working) return
        cardCrop = crop.copy(working = true)
        viewModelScope.launch {
            val tap = withContext(Dispatchers.Default) { change(cutout) }
            cardCrop = cardCrop?.takeIf { it.cutout === cutout }?.copy(working = false, preview = cutout.image, lastTap = tap)
        }
    }

    fun backToCorners() {
        cardCrop = cardCrop?.takeIf { !it.working }?.copy(cutout = null, preview = null, lastTap = null)
    }

    fun finishCardCrop() {
        val crop = cardCrop ?: return
        val cutout = crop.cutout ?: return
        if (crop.working) return
        closeCardCrop()
        makeStudio(texts(R.string.studio_card)) { studio.makeCard(cutout) }
    }

    /** Makes a studio photo with [make], its steps shown from [first]; it becomes the cover. */
    private fun makeStudio(first: String, make: suspend () -> String) {
        makingStudio = true
        studioLog.start(first)
        viewModelScope.launch {
            try {
                val made = make()
                studioPhotos = studioPhotos + made
                saved[STUDIO_PHOTOS] = ArrayList(studioPhotos)
                update { it.copy(photos = listOf(made) + it.photos) }
                selectedPhoto = 0
                messages.show(texts(R.string.studio_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: StudioException) {
                photoError = e.message
            } catch (e: Exception) {
                photoError = texts(R.string.studio_failed, e.message ?: e::class.simpleName.orEmpty())
            } finally {
                makingStudio = false
                studioLog.stop()
            }
        }
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
                messages.show((e as? PhotoException)?.message ?: texts(R.string.edit_photo_failed, e.message ?: e::class.simpleName.orEmpty()))
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
        sightingErrors = draft.validate(today, texts)
        if (sightingErrors.isNotEmpty()) return

        savingSighting = true
        viewModelScope.launch {
            try {
                repository.addSighting(draft.toEntity(diecast.id, today, System.currentTimeMillis()))
                storeSightingDraft(null)
                messages.show(texts(R.string.sighting_logged))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show(texts(R.string.sighting_save_failed, e.message ?: e::class.simpleName.orEmpty()))
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
            messages.show(texts(R.string.sighting_deleted))
        }
    }

    private fun storeSightingDraft(draft: SightingDraft?) {
        sightingDraft = draft
        saved[SIGHTING] = draft?.let { Json.encodeToString(it) }
    }

    // Saving

    fun save(addAnother: Boolean) {
        if (saving || processingPhotos > 0 || loading || scanning || makingStudio) return

        val today = LocalDate.now()
        errors = form.validate(today, texts)
        if (errors.isNotEmpty()) {
            messages.show(texts(R.string.edit_check_marked))
            return
        }

        saving = true
        viewModelScope.launch {
            try {
                val entity = form.toEntity(existing)
                val sighting = if (isNew && form.status == DiecastStatus.Spotted) form.firstSighting(today) else null
                repository.save(entity, form.photos, form.tags, sighting)
                if (fromQueue) queue.saved(route.scanItem)

                messages.show(
                    when {
                        !isNew -> texts(R.string.edit_saved)
                        form.status == DiecastStatus.Spotted -> texts(R.string.edit_saved_spotted, entity.name)
                        else -> texts(R.string.edit_saved_new, entity.name)
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
                messages.show(texts(R.string.save_failed, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                saving = false
            }
        }
    }

    fun delete() {
        val diecast = existing ?: return
        viewModelScope.launch {
            repository.delete(diecast.id)
            messages.show(texts(R.string.deleted_named, diecast.name))
            original = form
            _events.send(EditEvent.Close)
        }
    }

    /** Leaves without saving: photos added in this session are thrown away (not the queue's: it stays queued). */
    fun discard() {
        form.photos.filter { photos.isPending(it) && it != queuePhoto }.forEach(photos::delete)
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
        studioPhotos = emptySet()
        saved[STUDIO_PHOTOS] = null
    }

    companion object {
        private const val FORM = "form"
        private const val ORIGINAL = "original"
        private const val CAMERA = "camera_file"
        private const val CAMERA_OPENED = "scan_camera_opened"
        private const val AI_FIELDS = "ai_fields"
        private const val SIGHTING = "sighting_draft"
        private const val SIGHTING_CAMERA = "sighting_camera_file"
        private const val QUEUE_PHOTO = "queue_photo"
        private const val STUDIO_PHOTOS = "studio_photos"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) {
                    DiecastEditViewModel(createSavedStateHandle(), repository, photos, messages, scanner, matcher, prefs, dex, scanQueue, studio, texts)
                }
            }
        }
    }
}
