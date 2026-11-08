package io.github.caropeboka.diecasttracker.ui.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The user's low white desk under the boards, from their photo: a thin top with rounded corners on four round legs,
// and on its back corners the two 3D-printed car lifts (red posts, black ramps) that hold a loose car on the deck and
// one under it. A point on the desk: x from its left edge, z from its back edge, y up from its top; sizes in metres.

internal const val DESK_W = ShelfLayout.DESK_W_MM / 1000f
internal const val DESK_D = ShelfLayout.DESK_D_MM / 1000f
internal const val DESK_TOP = ShelfLayout.DESK_H_MM / 1000f
private const val DESK_T = 0.018f
private const val DESK_CORNER = 0.022f

/** Under the boards, its back edge a finger from the wall. */
internal const val DESK_X = BOARDS_X
internal const val DESK_LEFT = DESK_X - DESK_W / 2
internal const val DESK_BACK = WALL_Z + 0.012f

private const val LEG_R = 0.011f
private const val LEG_IN_X = 0.04f
private const val LEG_IN_Z = 0.035f

/** Nothing stands closer than this to the desk's edge, or to anything else on it. */
private const val DESK_EDGE = 0.003f
private const val DESK_GAP = 0.004f

/** How finely a free spot is looked for. */
private const val SEARCH_STEP = 0.005f

/** A point kept in millimetres is up to a millimetre off from where it was found: still free there. */
private const val ROUNDING = 0.0011f

private val DESK_WHITE = Color(0xFFF3F2EF)
private val LEG_WHITE = Color(0xFFF7F7F5)
private val FOOT_GREY = Color(0xFF8E8C88)

/** A point on the desk in the room. */
internal fun onDesk(x: Float, z: Float, y: Float = 0f) = Position(DESK_LEFT + x, DESK_TOP + y, DESK_BACK + z)

// A lift's own axes: x along the wall, z out from it, y up from the desk; its middle at the origin.

/** From the outside of one post to the other's, along the wall and across; how tall the posts are. */
private const val LIFT_L = 0.100f
private const val LIFT_W = 0.060f
private const val LIFT_H = 0.070f
private const val POST = 0.008f

/** The deck's underside: the car under it has this much head room. */
internal const val LIFT_DECK = 0.040f

/** The ramps the wheels run on, on the desk and on the deck: a little longer than the posts are apart. */
private const val RAMP_L = 0.124f
private const val RAMP_W = 0.013f
internal const val RAMP_T = 0.003f
private const val TRACK = 0.0135f

/** Where the lifts' middles are on the desk: in its back corners, as in the photo. */
internal val LIFT_CENTRES = listOf(Float2(0.075f, 0.046f), Float2(DESK_W - 0.075f, 0.046f))

private val LIFT_RED = Color(0xFFD3232A)
private val LIFT_BLACK = Color(0xFF1D1D1F)

/** Something standing on the desk: its middle (desk x and z) and half its width and depth. */
internal class Footprint(val x: Float, val z: Float, val halfWidth: Float, val halfDepth: Float) {
    fun overlaps(x: Float, z: Float, halfWidth: Float, halfDepth: Float, slack: Float = 0f) =
        abs(this.x - x) < this.halfWidth + halfWidth + DESK_GAP - slack &&
            abs(this.z - z) < this.halfDepth + halfDepth + DESK_GAP - slack
}

/** What the lifts take up of the desk, ramps and all. */
internal val LIFT_FOOTPRINTS = LIFT_CENTRES.map { Footprint(it.x, it.y, RAMP_L / 2, LIFT_W / 2) }

/**
 * Where something [width] × [depth] stands on the desk, clear of the edges and of [taken]: at ([x], [z]) if that's
 * free, else the free point nearest it, or with [rows] the first free one in rows from the back, left to right (as
 * "Place all automatically" fills it); null when there's none.
 */
internal fun deskSpot(x: Float, z: Float, width: Float, depth: Float, taken: List<Footprint>, rows: Boolean = false): Float2? {
    val halfWidth = width / 2
    val halfDepth = depth / 2
    val minX = halfWidth + DESK_EDGE
    val maxX = DESK_W - halfWidth - DESK_EDGE
    val minZ = halfDepth + DESK_EDGE
    val maxZ = DESK_D - halfDepth - DESK_EDGE
    if (minX > maxX || minZ > maxZ) return null
    fun free(px: Float, pz: Float, slack: Float = 0f) = taken.none { it.overlaps(px, pz, halfWidth, halfDepth, slack) }
    val wantX = x.coerceIn(minX, maxX)
    val wantZ = z.coerceIn(minZ, maxZ)
    if (!rows && free(wantX, wantZ, ROUNDING)) return Float2(wantX, wantZ)

    val columns = ((maxX - minX) / SEARCH_STEP).toInt()
    val lines = ((maxZ - minZ) / SEARCH_STEP).toInt()
    var best: Float2? = null
    var nearest = Float.MAX_VALUE
    for (line in 0..lines) {
        val pz = if (line == lines) maxZ else minZ + line * SEARCH_STEP
        for (column in 0..columns) {
            val px = if (column == columns) maxX else minX + column * SEARCH_STEP
            if (rows) {
                if (free(px, pz)) return Float2(px, pz)
                continue
            }
            val distance = (px - wantX) * (px - wantX) + (pz - wantZ) * (pz - wantZ)
            if (distance < nearest && free(px, pz)) {
                nearest = distance
                best = Float2(px, pz)
            }
        }
    }
    return best
}

/** A desk measurement in millimetres, as a slot keeps it. */
internal fun mm(metres: Float) = (metres * 1000).roundToInt()

/** The point on the desk's top under a tap, in millimetres across and in; null off the desk. */
internal fun deskAt(ray: Ray): Pair<Int, Int>? {
    val direction = ray.direction
    if (direction.y >= 0) return null
    val t = (DESK_TOP - ray.origin.y) / direction.y
    if (t <= 0) return null
    val x = ray.origin.x + t * direction.x - DESK_LEFT
    val z = ray.origin.z + t * direction.z - DESK_BACK
    if (x !in 0f..DESK_W || z !in 0f..DESK_D) return null
    return mm(x) to mm(z)
}

/**
 * The lift's level under a tap (lift × 2 + level): where [ray] first enters a lift, the deck when it comes in above
 * the deck's underside (from the top, or between the posts above it), else the bottom.
 */
internal fun liftAt(ray: Ray): Int? {
    var nearest = Float.MAX_VALUE
    var hit: Int? = null
    LIFT_CENTRES.forEachIndexed { lift, centre ->
        val low = onDesk(centre.x - RAMP_L / 2, centre.y - LIFT_W / 2)
        val high = onDesk(centre.x + RAMP_L / 2, centre.y + LIFT_W / 2, LIFT_H + 0.004f)
        val t = entry(ray, low, high) ?: return@forEachIndexed
        if (t < nearest) {
            nearest = t
            val y = ray.origin.y + t * ray.direction.y - DESK_TOP
            hit = lift * ShelfLayout.LIFT_LEVELS + if (y >= LIFT_DECK) 1 else 0
        }
    }
    return hit
}

/** How far along [ray] it enters the box from [low] to [high]; null when it misses it. */
private fun entry(ray: Ray, low: Position, high: Position): Float? {
    val origin = floatArrayOf(ray.origin.x, ray.origin.y, ray.origin.z)
    val direction = floatArrayOf(ray.direction.x, ray.direction.y, ray.direction.z)
    val from = floatArrayOf(low.x, low.y, low.z)
    val to = floatArrayOf(high.x, high.y, high.z)
    var near = 0f
    var far = Float.MAX_VALUE
    for (i in 0..2) {
        if (abs(direction[i]) < 1e-7f) {
            if (origin[i] < from[i] || origin[i] > to[i]) return null
            continue
        }
        val a = (from[i] - origin[i]) / direction[i]
        val b = (to[i] - origin[i]) / direction[i]
        near = max(near, min(a, b))
        far = min(far, max(a, b))
        if (near > far) return null
    }
    return near
}

/** The desk's top as an outline: a rectangle with rounded corners, centred, counter-clockwise. */
private fun deskOutline(): List<Float2> = buildList {
    val halfW = DESK_W / 2 - DESK_CORNER
    val halfD = DESK_D / 2 - DESK_CORNER
    val corners = listOf(Float2(halfW, -halfD) to -90f, Float2(halfW, halfD) to 0f, Float2(-halfW, halfD) to 90f, Float2(-halfW, -halfD) to 180f)
    for ((centre, start) in corners) {
        for (step in 0..6) {
            val angle = Math.toRadians((start + 15f * step).toDouble())
            add(Float2(centre.x + DESK_CORNER * cos(angle).toFloat(), centre.y + DESK_CORNER * sin(angle).toFloat()))
        }
    }
}

/** The desk on its legs; tapping it brings the camera to look down on it. */
@Composable
fun SceneScope.Desk() {
    val white = remember(materialLoader) { materialLoader.createColorInstance(DESK_WHITE, roughness = 0.32f) }
    val legWhite = remember(materialLoader) { materialLoader.createColorInstance(LEG_WHITE, roughness = 0.25f) }
    val grey = remember(materialLoader) { materialLoader.createColorInstance(FOOT_GREY, roughness = 0.8f) }
    // Freed with the engine when the screen goes.
    val top = remember(engine) { extrude(deskOutline(), emptyList(), DESK_T).build(engine) }
    val legLength = DESK_TOP - DESK_T
    val leg = remember(engine) {
        lathe(listOf(Float2(0f, legLength), Float2(LEG_R, legLength), Float2(LEG_R, 0.006f), Float2(0f, 0.006f)), segments = 16).build(engine)
    }
    val foot = remember(engine) {
        lathe(listOf(Float2(0f, 0.006f), Float2(LEG_R + 0.002f, 0.006f), Float2(LEG_R + 0.002f, 0f), Float2(0f, 0f)), segments = 16).build(engine)
    }
    val name = FOCUS + Focus.Desk.name

    // Cut in the outline's plane, then laid flat: the outline's y runs along the room's z.
    Mesh(top, white, position = Position(DESK_X, DESK_TOP - DESK_T / 2, DESK_BACK + DESK_D / 2), rotation = Rotation(x = -90f, y = 0f, z = 0f), name = name)
    for (x in listOf(LEG_IN_X, DESK_W - LEG_IN_X)) for (z in listOf(LEG_IN_Z, DESK_D - LEG_IN_Z)) {
        val floor = onDesk(x, z, -DESK_TOP)
        Mesh(leg, legWhite, position = floor, name = name)
        Mesh(foot, grey, position = floor, name = name)
    }
}

/** The ramp's side: flat on top, sloping down at both ends. */
private fun rampProfile() = listOf(
    Float2(-RAMP_L / 2, 0f),
    Float2(RAMP_L / 2, 0f),
    Float2(RAMP_L / 2 - 0.010f, RAMP_T),
    Float2(-RAMP_L / 2 + 0.010f, RAMP_T),
)

/** The two lifts on the desk's back corners, part of the desk for taps. */
@Composable
fun SceneScope.CarLifts() {
    val red = remember(materialLoader) { materialLoader.createColorInstance(LIFT_RED, roughness = 0.5f) }
    val black = remember(materialLoader) { materialLoader.createColorInstance(LIFT_BLACK, roughness = 0.85f) }
    val ramp = remember(engine) { extrude(rampProfile(), emptyList(), RAMP_W).build(engine) }
    val name = FOCUS + Focus.Desk.name
    val postX = LIFT_L / 2 - POST / 2
    val postZ = LIFT_W / 2 - POST / 2

    LIFT_CENTRES.forEachIndexed { lift, centre ->
        key(lift) {
            Node(position = onDesk(centre.x, centre.y)) {
                for (sx in listOf(-1f, 1f)) {
                    for (sz in listOf(-1f, 1f)) {
                        val x = sx * postX
                        val z = sz * postZ
                        CubeNode(size = Size(0.013f, 0.003f, 0.013f), center = Position(x, 0.0015f, z), materialInstance = red, apply = { this.name = name })
                        CubeNode(size = Size(POST, LIFT_H, POST), center = Position(x, LIFT_H / 2, z), materialInstance = red, apply = { this.name = name })
                        CubeNode(size = Size(POST + 0.002f, 0.003f, POST + 0.002f), center = Position(x, LIFT_H + 0.0015f, z), materialInstance = red, apply = { this.name = name })
                        // The slider the deck hangs from, round the post.
                        CubeNode(size = Size(0.011f, 0.009f, 0.011f), center = Position(x, LIFT_DECK + 0.002f, z), materialInstance = black, apply = { this.name = name })
                    }
                    // The beam across under each end of the deck.
                    CubeNode(size = Size(0.008f, 0.004f, LIFT_W - POST), center = Position(sx * postX, LIFT_DECK - 0.001f, 0f), materialInstance = black, apply = { this.name = name })
                }
                for (sz in listOf(-1f, 1f)) {
                    Mesh(ramp, black, position = Position(0f, 0f, sz * TRACK), name = name)
                    Mesh(ramp, black, position = Position(0f, LIFT_DECK, sz * TRACK), name = name)
                }
                // The tie between the ramps on the desk.
                CubeNode(size = Size(0.012f, 0.0015f, 2 * TRACK), center = Position(0f, 0.00075f, 0f), materialInstance = black, apply = { this.name = name })
            }
        }
    }
}
