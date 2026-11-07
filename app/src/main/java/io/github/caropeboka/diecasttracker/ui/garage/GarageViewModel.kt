package io.github.caropeboka.diecasttracker.ui.garage

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.DecorationEntity
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.decor.DecorCutout
import io.github.caropeboka.diecasttracker.data.decor.DecorMaker
import io.github.caropeboka.diecasttracker.data.decor.DecorRepository
import io.github.caropeboka.diecasttracker.data.decor.DecorStore
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.data.shelf.ShelfMove
import io.github.caropeboka.diecasttracker.data.shelf.ShelfPlanner
import io.github.caropeboka.diecasttracker.data.shelf.ShelfRepository
import io.github.caropeboka.diecasttracker.data.shelf.Shelved
import io.github.caropeboka.diecasttracker.data.studio.StudioStep
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A diecast as the shelves show it: [image] cropped to the card or box, a loose car without its backdrop. */
class ShelfItem(val id: Long, val packaging: Packaging, val scale: String?, val image: Bitmap, val side: Color)

/** What's drawn off the main thread: the board's face and the black shelf's wood. */
class GarageArt(val board: BoardArt, val wood: Bitmap)

/**
 * The owned diecasts that have a photo ([rows]), those drawn where they were put, and the rest in the tray: never put
 * anywhere, or with no room left where they were (see [Layout]). [withoutPhoto] can't be shown at all.
 */
class GarageScene internal constructor(
    val rows: List<ShelfRow>,
    internal val layout: Layout,
    val withoutPhoto: Int,
    val decor: List<DecorItem> = emptyList(),
) {
    private val drawn = layout.placed.map { it.item.id }.toSet()

    val tray: List<ShelfRow> = rows.filter { it.id !in drawn }

    fun row(id: Long): ShelfRow? = rows.find { it.id == id }

    /** What stands in a compartment. */
    fun occupant(spot: Spot): Long? = rows.firstOrNull { row ->
        row.id in drawn && row.slot?.let { it.shelf == Shelf.Case && spot.shelf == Shelf.Case && it.spot == spot.spot } == true
    }?.id

    fun decoration(id: Long): DecorItem? = decor.find { it.id == id }

    /** What in the tray could go there. */
    fun fitting(spot: Spot): List<ShelfRow> = tray.filter { ShelfLayout.fits(spot.shelf, it.packaging, it.scale) }

    /** The tray's diecasts take up no place, even where they were put. */
    internal fun shelved(): List<Shelved> = rows.map { row -> Shelved(row.id, row.packaging, row.scale, row.slot?.takeIf { row.id in drawn }) }
}

class GarageViewModel(
    repository: DiecastRepository,
    private val shelves: ShelfRepository,
    private val photos: PhotoStore,
    private val decorations: DecorRepository,
    private val decorStore: DecorStore,
    private val decorMaker: DecorMaker,
    private val messages: Messages,
    private val texts: Texts,
    assets: AssetManager,
) : ViewModel() {
    /** Each photo decoded once (by diecast, cover, packaging and scale), for those on the shelves. */
    private val decoded = ConcurrentHashMap<String, ShelfItem>()

    /** Each decoration's cut-out decoded once, by file. */
    private val cutouts = ConcurrentHashMap<String, Bitmap>()

    val scene: StateFlow<GarageScene?> = combine(repository.observeShelf(), decorations.observe()) { rows, decorated ->
        val shown = rows.filter { it.coverFile != null }
        val items = items(shown.filter { it.slot != null })
        decoded.keys.retainAll(shown.map(::key).toSet())
        val decor = decor(decorated)
        withContext(Dispatchers.Default) { GarageScene(shown, layOut(shown, items, decor), rows.size - shown.size, decor) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A decoration being made for [spot]: its photo cut out and cleaned ([cutout], null while that runs), or why not. */
    class DecorDraft(val spot: Spot, val cutout: DecorCutout? = null, val error: String? = null)

    private val _draft = MutableStateFlow<DecorDraft?>(null)
    val draft: StateFlow<DecorDraft?> = _draft.asStateFlow()

    private val log = AiProgressLog(texts, R.string.ai_hint_decor)
    val progress: StateFlow<AiProgress?> = log.state

    private val _art = MutableStateFlow<GarageArt?>(null)
    val art: StateFlow<GarageArt?> = _art.asStateFlow()

    /** What the 3D view showed when a diecast's page opened: covers the view on the way back until it's drawn. */
    var snapshot: Bitmap? = null

    init {
        viewModelScope.launch(Dispatchers.Default) { _art.value = GarageArt(BoardArt.draw(assets), blackWood()) }
    }

    /** A file for the camera to write a decoration's photo into. */
    fun cameraTarget(): Pair<File, Uri> = photos.newCameraTarget()

    /** Cuts the subject out of a photo (picked, or the camera's [file]) for a new decoration at [spot]. */
    fun makeDecoration(spot: Spot, uri: Uri? = null, file: File? = null) {
        _draft.value = DecorDraft(spot)
        viewModelScope.launch {
            log.start(texts(R.string.decor_reading_photo))
            val result = runCatching {
                val name = withContext(Dispatchers.IO) { if (file != null) photos.addFromCamera(file) else photos.addFromUri(uri!!) }
                val photo = try {
                    withContext(Dispatchers.IO) { photos.bitmap(name) }
                } finally {
                    photos.delete(name)
                }
                log.step(texts(R.string.decor_separating))
                decorMaker.make(photo) { step ->
                    if (step is StudioStep.Downloading) log.step(texts(R.string.decor_downloading))
                    if (step is StudioStep.Composing) log.step(texts(R.string.decor_tidying))
                }
            }
            log.stop()
            if (_draft.value?.spot != spot) return@launch
            _draft.value = result.fold(
                onSuccess = { DecorDraft(spot, it) },
                onFailure = { DecorDraft(spot, error = it.message ?: texts(R.string.decor_photo_failed)) },
            )
        }
    }

    fun dropDraft() {
        _draft.value = null
    }

    /** The draft onto the shelf as [name], [heightMm] tall; [cleaned] or as photographed. */
    fun saveDecoration(name: String, heightMm: Int, cleaned: Boolean) {
        val draft = _draft.value ?: return
        val cutout = draft.cutout ?: return
        _draft.value = null
        viewModelScope.launch {
            val position = (draft.spot.angle * 10).roundToInt()
            val id = decorations.add(name, if (cleaned) cutout.cleaned else cutout.original, heightMm, draft.spot.spot, position)
            settleDecoration(id)
            messages.show(texts(R.string.decor_placed, name, ShelfLayout.label(texts, Shelf.Corner, draft.spot.spot)))
        }
    }

    /** A decoration to [spot] on the black shelf; false, with a message, when there's no room. */
    suspend fun placeDecoration(id: Long, spot: Spot): Boolean {
        val scene = scene.value ?: return false
        if (spot.shelf != Shelf.Corner) return false.also { messages.show(texts(R.string.decor_corner_only)) }
        val position = (spot.angle * 10).roundToInt()
        val moved = scene.decor.map { if (it.id == id) DecorItem(it.id, it.name, it.image, it.heightMm, spot.spot, position) else it }
        val items = items(scene.rows.filter { it.slot != null })
        val after = withContext(Dispatchers.Default) { layOut(scene.rows, items, moved) }
        val angle = after.decorAngles[id]
        if (angle == null || (after.overflow - scene.layout.overflow).isNotEmpty()) {
            messages.show(texts(R.string.garage_no_room))
            return false
        }
        decorations.move(id, spot.spot, (angle * 10).roundToInt())
        return true
    }

    fun editDecoration(id: Long, name: String, heightMm: Int) {
        viewModelScope.launch { decorations.rename(id, name, heightMm) }
    }

    fun deleteDecoration(id: Long) {
        viewModelScope.launch {
            val name = scene.value?.decoration(id)?.name
            decorations.delete(id)
            name?.let { messages.show(texts(R.string.decor_deleted, it)) }
        }
    }

    /** Where a new decoration really stands, pushed aside by its neighbours. */
    private suspend fun settleDecoration(id: Long) {
        val current = scene.first { it?.decoration(id) != null } ?: return
        val angle = current.layout.decorAngles[id] ?: return
        val decoration = current.decoration(id) ?: return
        decorations.move(id, decoration.tier, (angle * 10).roundToInt())
    }

    private suspend fun decor(rows: List<DecorationEntity>): List<DecorItem> = withContext(Dispatchers.IO) {
        cutouts.keys.retainAll(rows.map { it.photo }.toSet())
        rows.mapNotNull { row ->
            val image = cutouts[row.photo] ?: runCatching { decorStore.bitmap(row.photo) }.getOrNull()?.also { cutouts[row.photo] = it }
            image?.let { DecorItem(row.id, row.name, it, row.heightMm, row.tier, row.position) }
        }
    }

    /** [id] to [spot]; false, with a message why, when it can't go there. */
    suspend fun place(id: Long, spot: Spot): Boolean {
        val scene = scene.value ?: return false
        return carryOut(scene, id, spot.shelf, ShelfPlanner.place(scene.shelved(), id, spot.shelf, spot.spot, (spot.angle * 10).roundToInt()))
    }

    /** [id] takes [other]'s place, and [other] its old one if it fits there (else the tray). */
    suspend fun swap(id: Long, other: Long): Boolean {
        val scene = scene.value ?: return false
        val there = scene.row(other)?.slot ?: return false
        return carryOut(scene, id, there.shelf, ShelfPlanner.swap(scene.shelved(), id, other))
    }

    fun release(id: Long) {
        viewModelScope.launch { shelves.release(id) }
    }

    /** Everything in the tray onto the shelves, wherever there's room (see [arrange]). */
    fun arrangeAll() {
        viewModelScope.launch {
            val scene = scene.value ?: return@launch
            val waiting = scene.tray
            val waitingIds = waiting.map { it.id }.toSet()
            // Cards too: with the lanes full they stand on the black shelf.
            val items = items(scene.rows.filter { it.slot != null && it.id !in waitingIds } + waiting)
            val moves = withContext(Dispatchers.Default) { arrange(scene.rows, waiting, items, scene.decor) }
            shelves.apply(moves)
            val left = waiting.size - moves.size
            messages.show(
                when {
                    moves.isEmpty() -> texts(R.string.garage_arrange_none)
                    left == 0 -> texts.plural(R.plurals.garage_arranged, moves.size, moves.size)
                    else -> texts.plural(R.plurals.garage_arranged_some, moves.size, moves.size) + " " + texts.plural(R.plurals.garage_arranged_left, left, left)
                },
            )
        }
    }

    fun say(message: String) = messages.show(message)

    private suspend fun carryOut(scene: GarageScene, id: Long, shelf: Shelf, move: ShelfMove): Boolean {
        val row = scene.row(id) ?: return false
        val moves = when (move) {
            ShelfMove.Full -> null.also { messages.show(texts(R.string.garage_lane_full, ShelfLayout.LANE_CARDS)) }
            ShelfMove.DoesNotFit -> null.also { messages.show(texts(doesNotFit(row, shelf))) }
            is ShelfMove.Done -> settle(scene, move.moves).also { if (it == null) messages.show(texts(R.string.garage_no_room)) }
        } ?: return false
        shelves.apply(moves)
        moves.forEach { (other, slot) -> if (other != id && slot == null) scene.row(other)?.let { messages.show(texts(R.string.garage_to_tray_done, it.name)) } }
        return true
    }

    /**
     * [moves] with the black shelf's angles where things really end up, pushed aside by their neighbours; null when
     * that leaves anything without room.
     */
    private suspend fun settle(scene: GarageScene, moves: Map<Long, ShelfSlotEntity?>): Map<Long, ShelfSlotEntity?>? {
        val rows = scene.rows.map { row -> if (row.id in moves) row.placed(moves[row.id]) else row }
        val items = items(rows.filter { it.slot != null })
        return withContext(Dispatchers.Default) {
            val after = layOut(rows, items, scene.decor)
            val stuck = moves.any { (id, slot) -> slot != null && id in after.overflow }
            if (stuck || (after.overflow - scene.layout.overflow).isNotEmpty()) return@withContext null
            moves.mapValues { (id, slot) ->
                val angle = after.angles[id]
                if (slot?.shelf == Shelf.Corner && angle != null) slot.copy(position = (angle * 10).roundToInt()) else slot
            }
        }
    }

    private suspend fun items(rows: List<ShelfRow>): Map<Long, ShelfItem> = withContext(Dispatchers.IO) {
        rows.mapNotNull { row ->
            val key = key(row)
            val item = decoded[key] ?: runCatching { shelfItem(row, photos) }.getOrNull()?.also { decoded[key] = it }
            item?.let { row.id to it }
        }.toMap()
    }

    private fun key(row: ShelfRow) = "${row.id}/${row.coverFile}/${row.packaging.key}/${row.scale}"

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) {
                    GarageViewModel(repository, shelves, photos, decorations, decorStore, decorMaker, messages, texts, app.assets)
                }
            }
        }
    }
}

@StringRes
private fun doesNotFit(row: ShelfRow, shelf: Shelf) = when {
    row.packaging == Packaging.Carded -> R.string.garage_case_no_cards
    shelf == Shelf.Boards -> R.string.garage_boards_cards_only
    shelf == Shelf.Case -> R.string.garage_case_small_only
    else -> R.string.garage_does_not_fit
}

private fun shelfItem(row: ShelfRow, photos: PhotoStore): ShelfItem {
    val photo = trimWhite(photos.bitmap(row.coverFile!!, 512))
    return when (row.packaging) {
        Packaging.Carded -> ShelfItem(row.id, row.packaging, row.scale, photo, Color.White)
        Packaging.Boxed -> ShelfItem(row.id, row.packaging, row.scale, photo, borderColour(photo))
        Packaging.Loose -> ShelfItem(row.id, row.packaging, row.scale, cutOut(photo), Color.White)
    }
}

/** The studio photo's white backdrop cut away, so the card fills the texture. */
private fun trimWhite(image: Bitmap): Bitmap {
    val width = image.width
    val height = image.height
    val pixels = IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
    var left = width
    var right = -1
    var top = height
    var bottom = -1
    for (y in 0 until height) for (x in 0 until width) {
        val p = pixels[y * width + x]
        val darkest = minOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
        if (darkest < 200) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right <= left || bottom <= top) return image
    return Bitmap.createBitmap(image, left, top, right - left + 1, bottom - top + 1)
}

/**
 * The backdrop made see-through: flooded in from the bright edges, a step at a time to any neighbour of nearly the
 * same light grey, so a backdrop fading to grey and the soft shadow under the car go too while the car's own outline
 * stops it. Stray specks left in the cleared part go after.
 */
private fun cutOut(image: Bitmap): Bitmap {
    val width = image.width
    val height = image.height
    val pixels = IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
    val clear = BooleanArray(pixels.size)
    val queue = IntArray(pixels.size)
    var tail = 0
    fun greyish(p: Int, lightest: Int): Boolean {
        val darkest = minOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
        val brightest = maxOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)
        return darkest > lightest && brightest - darkest < 40
    }
    fun near(p: Int, q: Int) = maxOf(
        abs((p shr 16 and 0xff) - (q shr 16 and 0xff)),
        abs((p shr 8 and 0xff) - (q shr 8 and 0xff)),
        abs((p and 0xff) - (q and 0xff)),
    ) <= 10
    fun seed(i: Int) {
        if (!clear[i] && greyish(pixels[i], 190)) {
            clear[i] = true
            queue[tail++] = i
        }
    }
    fun step(from: Int, i: Int) {
        if (!clear[i] && greyish(pixels[i], 150) && near(pixels[from], pixels[i])) {
            clear[i] = true
            queue[tail++] = i
        }
    }
    for (x in 0 until width) {
        seed(x)
        seed((height - 1) * width + x)
    }
    for (y in 0 until height) {
        seed(y * width)
        seed(y * width + width - 1)
    }
    var head = 0
    while (head < tail) {
        val i = queue[head++]
        val x = i % width
        if (x > 0) step(i, i - 1)
        if (x < width - 1) step(i, i + 1)
        if (i >= width) step(i, i - width)
        if (i < pixels.size - width) step(i, i + width)
    }
    // Specks: mostly surrounded by cleared pixels.
    val around = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
    repeat(2) {
        val specks = (0 until pixels.size).filter { i ->
            val x = i % width
            val y = i / width
            !clear[i] && x in 1 until width - 1 && y in 1 until height - 1 && around.count { clear[i + it] } >= 5
        }
        specks.forEach { clear[it] = true }
    }
    for (i in pixels.indices) {
        if (clear[i]) {
            pixels[i] = 0
            continue
        }
        // Half see-through where it meets the backdrop, so the cut isn't jagged.
        val x = i % width
        val edge = (x > 0 && clear[i - 1]) || (x < width - 1 && clear[i + 1]) ||
            (i >= width && clear[i - width]) || (i < pixels.size - width && clear[i + width])
        if (edge) pixels[i] = (pixels[i] and 0x00FFFFFF) or (0x99 shl 24)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

/** The colour round a box's front, for its sides and top. */
private fun borderColour(image: Bitmap): Color {
    val width = image.width
    val height = image.height
    val pixels = IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
    val band = maxOf(2, minOf(width, height) / 25)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (x in band until width - band && y in band until height - band) continue
        val p = pixels[y * width + x]
        red += p shr 16 and 0xff
        green += p shr 8 and 0xff
        blue += p and 0xff
        count++
    }
    return Color((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}
