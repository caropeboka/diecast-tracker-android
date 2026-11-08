package io.github.caropeboka.diecasttracker.ui.garage

import android.graphics.Bitmap
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.data.shelf.scaleRatio
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// Where each diecast is drawn, from the place the collector put it in (see data/shelf), and which place a tap is
// on. Sizes in metres.

private const val CARD_W = 0.108f
private const val CARD_H = 0.165f

/** A card standing on the black shelf: as deep as its blister, tipped back against nothing much. */
private const val CARD_D = 0.03f
private const val CARD_TILT = 8f

/** From this scale up (1:32, 1:24, 1:18…) a diecast stands in the black shelf's back row, like the cards. */
private const val BIG_SCALE = 40f

/** How much of each card shows below the one above it, which hangs in front of it: the car's blister. */
private const val STEP = 0.044f
private const val BOTTOM = LEDGE_TOP + 0.001f

/**
 * A place tapped on the furniture: a lane, a tier at an angle (degrees from the back wall), a compartment, a point on
 * the desk ([spot] millimetres across, [depth] in from the back), a lift's level.
 */
data class Spot(val shelf: Shelf, val spot: Int, val angle: Float = 0f, val depth: Int = 0) {
    /** What a slot keeps of it besides [spot]: the angle in tenths of a degree, or the depth on the desk. */
    val position: Int
        get() = when (shelf) {
            Shelf.Corner -> (angle * 10).roundToInt()
            Shelf.Desk -> depth
            else -> 0
        }
}

/** Where a thing is and which way it faces: turned [yaw] degrees about the vertical, then tipped [pitch] (top towards you). */
internal class Pose(val position: Position, val yaw: Float, val pitch: Float)

/** A diecast in its place, and where it is once it's free of the shelf, on its way out. */
internal class Placed(val item: ShelfItem, val rest: Pose, val clear: Pose, val size: Size)

/** A decoration on the black shelf (see [io.github.caropeboka.diecasttracker.data.db.DecorationEntity]), its cut-out decoded. */
class DecorItem(val id: Long, val name: String, val image: Bitmap, val heightMm: Int, val tier: Int, val position: Int) {
    /** As tall as it really is, as wide as its picture says, about as deep as wide. */
    internal val size: Size
        get() {
            val height = heightMm / 1000f
            val width = height * image.width / image.height
            return Size(width, height, width * 0.8f)
        }
}

/** A decoration standing on its tier: [centre], at [angle] along it. It turns to face the camera. */
internal class PlacedDecor(val item: DecorItem, val centre: Position, val angle: Float)

/** Where the nanoblock dog stands on the desk, in millimetres across and in. */
internal class PlacedDog(val x: Int, val depth: Int)

/**
 * What the shelves show: [placed]; the angle each diecast on the black shelf really stands at, pushed aside by its
 * neighbours, and the point on the desk ([desk], millimetres across and in), clear of the rest; those with no room
 * where they were put (a lane, a tier or the desk too full, a packaging that changed), which wait in the tray again;
 * the decorations ([decor]) the diecasts make room for, and they for them; and the [dog] on the desk.
 */
internal class Layout(
    val placed: List<Placed>,
    val angles: Map<Long, Float>,
    val overflow: Set<Long>,
    val decor: List<PlacedDecor> = emptyList(),
    val decorAngles: Map<Long, Float> = emptyMap(),
    val desk: Map<Long, Pair<Int, Int>> = emptyMap(),
    val dog: PlacedDog? = null,
)

/**
 * [rows] where they were put; those without an image in [items] aren't drawn. [decor] stand among them on the black
 * shelf, the [dog] (millimetres across and in) on the desk. On the desk what stands where it was put keeps its place:
 * the ones being put there now ([settling], or the dog with [dogSettling]) move to the nearest free point, those in
 * [packing] to the first free one in rows from the back. A lane of the boards takes [laneCards].
 */
internal fun layOut(
    rows: List<ShelfRow>,
    items: Map<Long, ShelfItem>,
    decor: List<DecorItem> = emptyList(),
    dog: Pair<Int, Int> = DOG_HOME,
    settling: Set<Long> = emptySet(),
    dogSettling: Boolean = false,
    packing: Set<Long> = emptySet(),
    laneCards: Int = ShelfLayout.LANE_CARDS,
): Layout {
    class Put(val row: ShelfRow, val slot: ShelfSlotEntity, val item: ShelfItem)

    val placed = mutableListOf<Placed>()
    val angles = mutableMapOf<Long, Float>()
    val overflow = mutableSetOf<Long>()
    val put = rows.mapNotNull { row ->
        val slot = row.slot ?: return@mapNotNull null
        val item = items[row.id] ?: return@mapNotNull null
        if (!ShelfLayout.fits(slot.shelf, row.packaging, row.scale)) null.also { overflow += row.id } else Put(row, slot, item)
    }

    put.filter { it.slot.shelf == Shelf.Boards }.groupBy { it.slot.spot }.forEach { (lane, cards) ->
        cards.sortedWith(compareBy({ it.slot.position }, { it.row.id })).forEachIndexed { index, card ->
            if (index < laneCards) placed += onLane(card.item, lane, index, laneCards) else overflow += card.row.id
        }
    }
    put.filter { it.slot.shelf == Shelf.Case }.groupBy { it.slot.spot }.forEach { (cell, inside) ->
        inside.sortedBy { it.row.id }.forEachIndexed { index, it ->
            if (index == 0) placed += inCompartment(it.item, cell) else overflow += it.row.id
        }
    }
    put.filter { it.slot.shelf == Shelf.Lifts }.groupBy { it.slot.spot }.forEach { (level, on) ->
        on.sortedBy { it.row.id }.forEachIndexed { index, it ->
            if (index == 0) placed += onLift(it.item, level) else overflow += it.row.id
        }
    }

    val desk = mutableMapOf<Long, Pair<Int, Int>>()
    val taken = LIFT_FOOTPRINTS.toMutableList()
    var placedDog: PlacedDog? = null
    fun standDog() {
        // Without room it isn't drawn until something makes way.
        val at = deskSpot(dog.first / 1000f, dog.second / 1000f, DOG_SIZE.x, DOG_SIZE.z, taken) ?: return
        taken += Footprint(at.x, at.y, DOG_SIZE.x / 2, DOG_SIZE.z / 2)
        placedDog = PlacedDog(mm(at.x), mm(at.y))
    }
    if (!dogSettling) standDog()
    put.filter { it.slot.shelf == Shelf.Desk }
        .sortedWith(compareBy({ it.row.id in settling || it.row.id in packing }, { it.row.id }))
        .forEach { on ->
            val size = standingSize(on.item)
            val at = deskSpot(on.slot.spot / 1000f, on.slot.position / 1000f, size.x, size.z, taken, rows = on.row.id in packing)
            if (at == null) {
                overflow += on.row.id
            } else {
                taken += Footprint(at.x, at.y, size.x / 2, size.z / 2)
                placed += onDeskTop(on.item, at.x, at.y, size)
                desk[on.row.id] = mm(at.x) to mm(at.y)
            }
        }
    if (dogSettling) standDog()
    val placedDecor = mutableListOf<PlacedDecor>()
    val decorAngles = mutableMapOf<Long, Float>()
    val corner = put.filter { it.slot.shelf == Shelf.Corner }.groupBy { it.slot.spot }
    for (tier in TIER_TOPS.indices) {
        val standing = corner[tier].orEmpty()
        val figures = decor.filter { it.tier == tier }
        if (standing.isEmpty() && figures.isEmpty()) continue
        val sizes = standing.map { standingSize(it.item) }
        val spots = tierLayout(
            tier,
            standing.mapIndexed { i, it -> OnTier(sizes[i].x, sizes[i].z, it.slot.position / 10f) },
            standing.map { atBack(it.item) },
            figures.map { OnTier(it.size.x, it.size.z, it.position / 10f) },
        )
        spots.diecasts.forEachIndexed { i, spot ->
            val id = standing[i].row.id
            if (spot == null) {
                overflow += id
            } else {
                placed += onTier(standing[i].item, spot, sizes[i])
                angles[id] = spot.angle
            }
        }
        // A decoration with no room left isn't drawn until something makes way.
        spots.decor.forEachIndexed { i, spot ->
            if (spot != null) {
                placedDecor += standUp(figures[i], spot)
                decorAngles[figures[i].id] = spot.angle
            }
        }
    }
    return Layout(placed, angles, overflow, placedDecor, decorAngles, desk, placedDog)
}

/** On its tier, as far out as it fits. */
private fun standUp(item: DecorItem, spot: ShelfSpot): PlacedDecor {
    val angle = Math.toRadians(spot.angle.toDouble())
    val centre = onShelf(spot.radius * cos(angle).toFloat(), TIER_TOPS[spot.tier] + item.size.y / 2, spot.radius * sin(angle).toFloat())
    return PlacedDecor(item, centre, spot.angle)
}

/** Its row with [slot] instead of the one it has. */
internal fun ShelfRow.placed(slot: ShelfSlotEntity?) = copy(shelfKey = slot?.shelf?.key, spot = slot?.spot, position = slot?.position)

/**
 * Places for whatever waits in the tray, the way the shelves filled before the collector chose: cards row by row
 * across the lanes, loose cars into the case from the top, small boxes from its bottom row as in the photo, then
 * small loose cars onto the lifts, the rest round the black shelf's tiers, and what's still left onto the desk in
 * rows from the back. What has no room left stays out.
 */
internal fun arrange(
    rows: List<ShelfRow>,
    waiting: List<ShelfRow>,
    items: Map<Long, ShelfItem>,
    decor: List<DecorItem> = emptyList(),
    dog: Pair<Int, Int> = DOG_HOME,
    laneCards: Int = ShelfLayout.LANE_CARDS,
): Map<Long, ShelfSlotEntity> {
    val waitingIds = waiting.map { it.id }.toSet()
    val current = rows.associateBy({ it.id }, { if (it.id in waitingIds) it.placed(null) else it }).toMutableMap()
    val moves = linkedMapOf<Long, ShelfSlotEntity>()
    fun at(shelf: Shelf, spot: Int) = current.values.mapNotNull { row -> row.slot?.takeIf { it.shelf == shelf && it.spot == spot } }
    val fromTop = (0 until ShelfLayout.spots(Shelf.Case)).toList()
    val fromBottom = (CASE_ROWS - 1 downTo 0).flatMap { row -> (0 until CASE_COLUMNS).map { row * CASE_COLUMNS + it } }

    for (row in waiting) {
        val slot = when {
            row.packaging == Packaging.Carded -> (0 until ShelfLayout.LANES)
                .map { it to at(Shelf.Boards, it) }
                .filter { (_, cards) -> cards.size < laneCards }
                .minByOrNull { (_, cards) -> cards.size }
                ?.let { (lane, cards) -> ShelfSlotEntity(row.id, Shelf.Boards, lane, (cards.maxOfOrNull { it.position } ?: -1) + 1) }
            ShelfLayout.fits(Shelf.Case, row.packaging, row.scale) ->
                (if (row.packaging == Packaging.Loose) fromTop else fromBottom)
                    .firstOrNull { at(Shelf.Case, it).isEmpty() }
                    ?.let { ShelfSlotEntity(row.id, Shelf.Case, it) }
            else -> null
        } ?: LIFT_ORDER.takeIf { ShelfLayout.fits(Shelf.Lifts, row.packaging, row.scale) }
            ?.firstOrNull { at(Shelf.Lifts, it).isEmpty() }
            ?.let { ShelfSlotEntity(row.id, Shelf.Lifts, it) }
            ?: (if (ShelfLayout.fits(Shelf.Corner, row.packaging, row.scale)) tierFor(current.values.toList(), row, items, decor, dog, laneCards) else null)
            ?: (if (ShelfLayout.fits(Shelf.Desk, row.packaging, row.scale)) deskFor(current.values.toList(), row, items, decor, dog, laneCards) else null)
        if (slot != null) {
            moves[row.id] = slot
            current[row.id] = row.placed(slot)
        }
    }
    return moves
}

/** The lifts' levels as they fill: under both decks first. */
private val LIFT_ORDER = listOf(0, 2, 1, 3)

/** A tier of the black shelf with room for [row] as things stand, and the angle it gets there. */
private fun tierFor(
    rows: List<ShelfRow>,
    row: ShelfRow,
    items: Map<Long, ShelfItem>,
    decor: List<DecorItem>,
    dog: Pair<Int, Int>,
    laneCards: Int,
): ShelfSlotEntity? {
    val before = layOut(rows, items, decor, dog, laneCards = laneCards).overflow
    return FILL_ORDER.firstNotNullOfOrNull { tier ->
        // From the left wall round, as the tiers filled before.
        val slot = ShelfSlotEntity(row.id, Shelf.Corner, tier, 900)
        val after = layOut(rows.filter { it.id != row.id } + row.placed(slot), items, decor, dog, laneCards = laneCards)
        val angle = after.angles[row.id]?.takeIf { (after.overflow - before).isEmpty() }
        angle?.let { slot.copy(position = (it * 10).roundToInt()) }
    }
}

/** The first free point on the desk for [row], in rows from the back as things stand. */
private fun deskFor(
    rows: List<ShelfRow>,
    row: ShelfRow,
    items: Map<Long, ShelfItem>,
    decor: List<DecorItem>,
    dog: Pair<Int, Int>,
    laneCards: Int,
): ShelfSlotEntity? {
    val before = layOut(rows, items, decor, dog, laneCards = laneCards).overflow
    val slot = ShelfSlotEntity(row.id, Shelf.Desk, 0, 0)
    val after = layOut(rows.filter { it.id != row.id } + row.placed(slot), items, decor, dog, packing = setOf(row.id), laneCards = laneCards)
    val (x, depth) = after.desk[row.id]?.takeIf { (after.overflow - before).isEmpty() } ?: return null
    return slot.copy(spot = x, position = depth)
}

/**
 * On the boards: up from the bottom of its lane, each a little in front of the one below; out up the groove. A lane
 * that takes more cards than show a whole blister each below the rails' top hangs them closer.
 */
private fun onLane(item: ShelfItem, lane: Int, index: Int, laneCards: Int): Placed {
    val board = lane / COLUMNS
    val x = columnX(lane % COLUMNS)
    val z = FACE_Z + 0.002f + index * min(0.001f, 0.012f / laneCards)
    val rest = onBoard(board, x, BOTTOM + index * laneStep(laneCards) + CARD_H / 2, z)
    val clear = onBoard(board, x, RAIL_TOP + CARD_H / 2 + 0.012f, z)
    return Placed(item, Pose(rest, 0f, -LEAN), Pose(clear, 0f, -LEAN), Size(CARD_W, CARD_H))
}

/** How far apart the cards of a lane hang when it takes [laneCards]: the top one ends at the rails' top at most. */
internal fun laneStep(laneCards: Int) = if (laneCards <= 1) STEP else min(STEP, (RAIL_TOP - BOTTOM - CARD_H) / (laneCards - 1))

/** In the case, standing on the compartment's floor: a car halfway in, a box filling it; out straight forward. */
private fun inCompartment(item: ShelfItem, cell: Int): Placed {
    val size = fit(item.image, CELL_FIT_W, CELL_FIT_H)
    val x = cellX(cell % CASE_COLUMNS)
    val y = cellFloor(cell / CASE_COLUMNS) + size.y / 2
    return if (item.packaging == Packaging.Boxed) {
        val clear = inCase(x, y + 0.004f, CASE_DEPTH + CELL_BOX_D / 2 + 0.02f)
        Placed(item, Pose(inCase(x, y, CELL_BOX_Z), 0f, -CASE_LEAN), Pose(clear, 0f, -CASE_LEAN), Size(size.x, size.y, CELL_BOX_D))
    } else {
        Placed(item, Pose(inCase(x, y, CELL_Z), 0f, -CASE_LEAN), Pose(inCase(x, y + 0.004f, CASE_DEPTH + 0.05f), 0f, -CASE_LEAN), size)
    }
}

/** On a lift: on the ramps under the deck, or on the deck; out forward between the posts, or up off the deck. */
private fun onLift(item: ShelfItem, level: Int): Placed {
    val size = looseSize(item)
    val centre = LIFT_CENTRES[level / ShelfLayout.LIFT_LEVELS]
    val onDeck = level % ShelfLayout.LIFT_LEVELS == 1
    val y = (if (onDeck) LIFT_DECK + RAMP_T else RAMP_T) + size.y / 2
    val rest = onDesk(centre.x, centre.y, y)
    val clear = if (onDeck) onDesk(centre.x, centre.y + 0.02f, y + 0.06f) else onDesk(centre.x, centre.y + 0.085f, y + 0.004f)
    return Placed(item, Pose(rest, 0f, 0f), Pose(clear, 0f, 0f), size)
}

/** On the desk at ([x], [z]), its front to the room; up off the desk as it comes away. */
private fun onDeskTop(item: ShelfItem, x: Float, z: Float, size: Size): Placed {
    val rest = onDesk(x, z, size.y / 2)
    val clear = onDesk(x, z + 0.03f, size.y / 2 + 0.07f)
    return Placed(item, Pose(rest, 0f, 0f), Pose(clear, 0f, 0f), size)
}

/** On a tier of the black shelf, its front facing out (a card tipped back a little); tipped forward as it comes off. */
private fun onTier(item: ShelfItem, spot: ShelfSpot, size: Size): Placed {
    val angle = Math.toRadians(spot.angle.toDouble())
    val facing = Math.toRadians(spot.facing.toDouble())
    val outX = cos(facing).toFloat()
    val outZ = sin(facing).toFloat()
    val centre = onShelf(spot.radius * cos(angle).toFloat(), TIER_TOPS[spot.tier] + size.y / 2, spot.radius * sin(angle).toFloat())
    // From the back row up over the front one and out past the edge.
    val pull = size.z / 2 + 0.07f + if (spot.back) SHELF_R - spot.radius else 0f
    val clear = Position(centre.x + outX * pull, centre.y + if (spot.back) 0.06f else 0.015f, centre.z + outZ * pull)
    val tilt = if (item.packaging == Packaging.Carded) -CARD_TILT else 0f
    return Placed(item, Pose(centre, 90f - spot.facing, tilt), Pose(clear, 90f - spot.facing, 12f), size)
}

/** Cards and the big scales stand behind the rest, so the front edge stays free for the small ones. */
private fun atBack(item: ShelfItem) = item.packaging == Packaging.Carded || scaleRatio(item.scale) < BIG_SCALE

/** What it takes up standing on a tier or the desk: width, height and depth. */
private fun standingSize(item: ShelfItem) = when (item.packaging) {
    Packaging.Carded -> Size(CARD_W, CARD_H, CARD_D)
    Packaging.Boxed -> boxSize(item)
    Packaging.Loose -> looseSize(item)
}

/**
 * As long as its scale makes the car plus packing, as tall as its photo says (boxes from 1:43 up lie long), a little
 * less deep than tall.
 */
private fun boxSize(item: ShelfItem): Size {
    val ratio = scaleRatio(item.scale)
    val width = (5f / ratio + 0.023f).coerceIn(0.07f, 0.34f)
    val height = width * (item.image.height.toFloat() / item.image.width).coerceIn(0.3f, if (ratio < 50) 0.75f else 1.4f)
    return Size(width, height, (height * 0.85f).coerceIn(0.035f, 0.14f))
}

/** As long as its scale makes the car (a 1:64 one about 7.5 cm), as tall as its photo says, about half as wide. */
private fun looseSize(item: ShelfItem): Size {
    val length = (4.8f / scaleRatio(item.scale)).coerceIn(0.05f, 0.3f)
    val height = length * (item.image.height.toFloat() / item.image.width).coerceIn(0.2f, 1f)
    return Size(length, height, length * 0.45f)
}

private fun fit(image: Bitmap, width: Float, height: Float): Size {
    val aspect = image.width.toFloat() / image.height
    return if (aspect > width / height) Size(width, width / aspect) else Size(height * aspect, height)
}

/**
 * Where [ray] meets the plane z = [planeZ] of a frame at [origin] leaning back [lean] degrees about x, as that frame's
 * x and y; null when it runs away from it. (Plain arithmetic, like [held].)
 */
internal fun hitTipped(ray: Ray, origin: Position, lean: Float, planeZ: Float): Pair<Float, Float>? {
    val angle = Math.toRadians(-lean.toDouble())
    val c = cos(angle).toFloat()
    val s = sin(angle).toFloat()
    // Into the frame: the turn undone.
    val oy = ray.origin.y - origin.y
    val oz = ray.origin.z - origin.z
    val y = oy * c + oz * s
    val z = -oy * s + oz * c
    val dy = ray.direction.y * c + ray.direction.z * s
    val dz = -ray.direction.y * s + ray.direction.z * c
    if (abs(dz) < 1e-6f) return null
    val t = (planeZ - z) / dz
    if (t <= 0) return null
    return (ray.origin.x - origin.x + t * ray.direction.x) to (y + t * dy)
}
