package io.github.caropeboka.diecasttracker.ui.garage

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Ray
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.texture.ImageTexture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// The user's black carved corner shelf (their "laci"), from their photo: three quarter-round tiers on turned posts,
// a pierced, carved gallery along both walls on each tier, a crest and a knob on top. Its own axes: the corner at
// the origin, x along the back wall, z out along the left wall, y up; sizes in metres.

internal const val SHELF_R = 0.48f
private const val TIER_T = 0.028f

/** The tops of the tiers, lowest first. */
internal val TIER_TOPS = floatArrayOf(0.14f, 0.46f, 0.78f)

/** Between the posts at the ends of each tier, clear of the walls and the carving. */
private const val FIRST_ANGLE = 13f
private const val LAST_ANGLE = 77f

/** The front posts stand just inside the edge at the ends of the arc; one more stands in the corner. */
private const val POST_R = SHELF_R - 0.034f
private val POST_ANGLES = listOf(6f, 84f)
private const val CORNER_POST = 0.026f

/** The carved galleries run between the corner post and the end posts, a little off the wall. */
private const val GALLERY_START = 0.048f
private const val GALLERY_LENGTH = SHELF_R - 0.075f - GALLERY_START
private const val GALLERY_D = 0.012f
private const val GALLERY_Z = 0.006f + GALLERY_D / 2

private val CORNER = Position(LEFT_WALL_X, 0f, WALL_Z)

/** A point on the shelf in the room. */
internal fun onShelf(x: Float, y: Float, z: Float) = Position(CORNER.x + x, CORNER.y + y, CORNER.z + z)

/**
 * Where a diecast stands: on [tier], its centre [radius] out from the corner at [angle] degrees; along the front edge
 * its front faces out from the corner, in the [back] row it faces down the corner's diagonal.
 */
internal class ShelfSpot(val tier: Int, val angle: Float, val radius: Float, val back: Boolean = false) {
    /** Which way its front faces, in degrees from the back wall. */
    val facing get() = if (back) 45f else angle
}

/** Like the photo: the middle tier first, then the bottom one, the top one (under the crest) last. */
internal val FILL_ORDER = listOf(1, 0, 2)

/** A diecast to stand on a tier: its width and depth, and the angle it was put at. */
internal class OnTier(val width: Float, val depth: Float, val angle: Float)

/**
 * Where diecasts stand on [tier]: each as far out as its front corners allow, and as near the angle it was put at as
 * the others leave room for, pushed along the arc to clear them and the posts. Null for any the tier has no room
 * left for, those nearest the left wall first.
 */
internal fun tierSpots(tier: Int, items: List<OnTier>): List<ShelfSpot?> {
    class Standing(val index: Int, val radius: Float, val span: Float, var angle: Float)

    val edge = SHELF_R - 0.014f
    val standing = items.mapIndexedNotNull { index, item ->
        val radius = sqrt(edge * edge - item.width * item.width / 4) - item.depth / 2
        val back = radius - item.depth / 2
        // Measured at the back, where it takes the widest angle; clear of the corner post.
        if (back < 0.07f) return@mapIndexedNotNull null
        Standing(index, radius, 2 * Math.toDegrees(atan((item.width / 2 + 0.006f) / back).toDouble()).toFloat(), item.angle)
    }.sortedByDescending { it.angle }.toMutableList()
    while (standing.sumOf { it.span.toDouble() } > LAST_ANGLE - FIRST_ANGLE) standing.removeAt(0)

    // Down from the left wall, then up from the back one: none overlaps, all between the end posts.
    var cursor = LAST_ANGLE
    for (item in standing) {
        item.angle = minOf(item.angle, cursor - item.span / 2)
        cursor = item.angle - item.span / 2
    }
    var floor = FIRST_ANGLE
    for (item in standing.asReversed()) {
        item.angle = maxOf(item.angle, floor + item.span / 2)
        floor = item.angle + item.span / 2
    }
    val spots = arrayOfNulls<ShelfSpot>(items.size)
    standing.forEach { spots[it.index] = ShelfSpot(tier, it.angle, it.radius) }
    return spots.toList()
}

/** How far the back row's fronts may come out along the corner's diagonal, clear of most of the front row. */
private const val BACK_FRONT = 0.34f

/** Between the front row and the back one, and between the back row and the walls' carving. */
private const val ROW_GAP = 0.008f
private const val WALL_GAP = 0.02f
private val SQRT2 = sqrt(2f)

/** Where everything on a tier stands (see [tierLayout]): a spot per diecast and per decoration, null without room. */
internal class TierSpots(val diecasts: List<ShelfSpot?>, val decor: List<ShelfSpot?>)

/**
 * Where everything on [tier] stands. [decor] and the diecasts not marked [back] go along the front edge
 * ([tierSpots]); the [back] ones (cards and big scales, so a 1:18 doesn't take the whole edge) stand in a row across
 * the corner behind them ([backRowSpots]), as far out as the front row leaves room for. What finds no room in its
 * own row tries the other one.
 */
internal fun tierLayout(tier: Int, diecasts: List<OnTier>, back: List<Boolean>, decor: List<OnTier>): TierSpots {
    fun attempt(front: Set<Int>): TierSpots {
        val inFront = diecasts.indices.filter { it in front }
        val frontSpots = tierSpots(tier, inFront.map { diecasts[it] } + decor)
        val spots = arrayOfNulls<ShelfSpot>(diecasts.size)
        inFront.forEachIndexed { i, index -> spots[index] = frontSpots[i] }
        // The back row's fronts stop short of the nearest corner of anything in front.
        val sizes = inFront.map { diecasts[it] } + decor
        val limit = frontSpots.withIndex().fold(BACK_FRONT) { limit, (i, spot) ->
            if (spot == null) return@fold limit
            val off = Math.toRadians(spot.angle - 45.0)
            val nearest = (spot.radius - sizes[i].depth / 2) * cos(off).toFloat() - sizes[i].width / 2 * abs(sin(off).toFloat())
            minOf(limit, nearest - ROW_GAP)
        }
        val behind = diecasts.indices.filter { spots[it] == null }
        backRowSpots(tier, behind.map { diecasts[it] }, limit).forEachIndexed { i, spot -> spots[behind[i]] = spot }
        return TierSpots(spots.toList(), frontSpots.drop(inFront.size))
    }

    fun placed(spots: TierSpots) = spots.diecasts.count { it != null } + spots.decor.count { it != null }
    val first = attempt(diecasts.indices.filterNot { back[it] }.toSet())
    val left = diecasts.indices.filter { back[it] && first.diecasts[it] == null }
    if (left.isEmpty()) return first
    val second = attempt(diecasts.indices.filterNot { back[it] }.toSet() + left)
    return if (placed(second) > placed(first)) second else first
}

/**
 * Where diecasts stand in the back row of [tier]: side by side across the corner, their backs on one line square to
 * its diagonal and their fronts no further out than [front] (from the corner, along the diagonal), each as near the
 * angle it was put at as the others leave room for. Null for any with no room, those nearest the left wall first.
 */
internal fun backRowSpots(tier: Int, items: List<OnTier>, front: Float): List<ShelfSpot?> {
    class Standing(val index: Int, val half: Float, val depth: Float, var along: Float = 0f)

    val standing = items.withIndex()
        .sortedByDescending { it.value.angle }
        .map { (index, item) -> Standing(index, item.width / 2 + 0.006f, item.depth) }
        .toMutableList()
    // The line the backs stand on, and how far along it either way they may reach without touching the carving.
    fun line() = front - (standing.maxOfOrNull { it.depth } ?: 0f)
    fun reach() = line() - WALL_GAP * SQRT2
    while (standing.isNotEmpty() && (line() < 0.06f || standing.sumOf { 2.0 * it.half } > 2 * reach())) standing.removeAt(0)
    val line = line()
    val reach = reach()

    // Along the line towards the left wall; then pushed apart as on the front edge.
    standing.forEach {
        val centre = line + it.depth / 2
        it.along = (centre * tan(Math.toRadians(items[it.index].angle - 45.0).coerceIn(-1.4, 1.4)).toFloat()).coerceIn(-reach, reach)
    }
    var cursor = reach
    for (item in standing) {
        item.along = minOf(item.along, cursor - item.half)
        cursor = item.along - item.half
    }
    var floor = -reach
    for (item in standing.asReversed()) {
        item.along = maxOf(item.along, floor + item.half)
        floor = item.along + item.half
    }
    val spots = arrayOfNulls<ShelfSpot>(items.size)
    standing.forEach {
        val centre = line + it.depth / 2
        val x = (centre - it.along) / SQRT2
        val z = (centre + it.along) / SQRT2
        spots[it.index] = ShelfSpot(tier, Math.toDegrees(atan2(z, x).toDouble()).toFloat(), sqrt(x * x + z * z), back = true)
    }
    return spots.toList()
}

/** The tier and the angle (degrees from the back wall) under a tap: the first tier top [ray] comes down on. */
internal fun tierAt(ray: Ray): Pair<Int, Float>? {
    val origin = ray.origin
    val direction = ray.direction
    if (direction.y >= 0) return null
    var nearest = Float.MAX_VALUE
    var hit: Pair<Int, Float>? = null
    TIER_TOPS.forEachIndexed { tier, top ->
        val t = (CORNER.y + top - origin.y) / direction.y
        val x = origin.x + t * direction.x - CORNER.x
        val z = origin.z + t * direction.z - CORNER.z
        if (t > 0 && t < nearest && x >= 0 && z >= 0 && x * x + z * z <= SHELF_R * SHELF_R) {
            nearest = t
            hit = tier to Math.toDegrees(atan2(z, x).toDouble()).toFloat()
        }
    }
    return hit
}

/** Old black-stained wood: a faint grain along u, grey dust caught in it. Tiles both ways. */
internal fun blackWood(size: Int = 256, random: Random = Random(11)): Bitmap {
    val bands = FloatArray(32) { random.nextFloat() * 2 - 1 }
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        // Two waves across the tile, so the grain meets itself at the edges.
        val wobble = 3f * sin(4 * PI * x / size).toFloat()
        val g = ((y + wobble + size) % size) / size * bands.size
        val i = g.toInt() % bands.size
        val f = g - g.toInt()
        val grain = bands[i] * (1 - f) + bands[(i + 1) % bands.size] * f
        var shade = 1 + 0.12f * grain + 0.06f * (random.nextFloat() * 2 - 1)
        if (random.nextInt(70) == 0) shade *= 1.45f
        val red = (86 * shade).roundToInt().coerceIn(0, 255)
        val green = (80 * shade).roundToInt().coerceIn(0, 255)
        val blue = (74 * shade).roundToInt().coerceIn(0, 255)
        pixels[y * size + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
}

/** The shelf in the room's corner; tapping it brings the camera round to face it. */
@Composable
fun SceneScope.CornerShelf(wood: Bitmap) {
    val material = remember(wood) {
        materialLoader.createTextureInstance(ImageTexture.Builder().bitmap(wood).build(engine), roughness = 0.62f)
    }
    // Freed with the engine when the screen goes.
    val tier = remember(engine) { lathe(tierProfile(), 0f, 90f, segments = 28).build(engine) }
    val post = remember(engine) { lathe(spindle(TIER_TOPS[1] - TIER_TOPS[0] - TIER_T), segments = 14).build(engine) }
    val foot = remember(engine) { lathe(foot(TIER_TOPS[0] - TIER_T), segments = 14).build(engine) }
    val crown = remember(engine) { lathe(crownPost(), segments = 14).build(engine) }
    val galleries = remember(engine) {
        listOf(
            fretwork(band = 0.045f, lobe = 0.024f, rise = 0.012f),
            fretwork(band = 0.06f, lobe = 0.03f, rise = 0.07f),
        ).map { (outline, holes) ->
            // Along the back wall from the corner, and the same mirrored along the left wall (see LeftGallery).
            extrude(outline, holes, GALLERY_D).build(engine) to
                extrude(outline.map { Float2(GALLERY_LENGTH - it.x, it.y) }, holes.map { h -> h.map { Float2(GALLERY_LENGTH - it.x, it.y) } }, GALLERY_D).build(engine)
        }
    }

    Node(position = CORNER) {
        TIER_TOPS.forEachIndexed { index, top ->
            Mesh(tier, material, Position(0f, top - TIER_T, 0f), name = FOCUS + Focus.Shelf.name)
            val (back, left) = galleries[if (index == TIER_TOPS.lastIndex) 1 else 0]
            Mesh(back, material, Position(GALLERY_START, top, GALLERY_Z))
            // Turned to face out of the left wall: its x runs back towards the corner.
            Mesh(left, material, Position(GALLERY_Z, top, GALLERY_START + GALLERY_LENGTH), Rotation(x = 0f, y = 90f, z = 0f))
        }
        val spots = POST_ANGLES.map { onArc(it) } + Position(CORNER_POST, 0f, CORNER_POST)
        for (spot in spots) {
            Mesh(foot, material, spot)
            for (level in 0 until TIER_TOPS.lastIndex) Mesh(post, material, Position(spot.x, TIER_TOPS[level], spot.z))
        }
        Mesh(crown, material, Position(CORNER_POST, 0f, CORNER_POST))
    }
}

private fun onArc(angle: Float): Position {
    val radians = Math.toRadians(angle.toDouble())
    return Position(POST_R * cos(radians).toFloat(), 0f, POST_R * sin(radians).toFloat())
}

/** A tier's cross-section, turned round the corner: flat top, a bead just inside the edge, a rounded nose under it. */
private fun tierProfile(): List<Float2> {
    val r = SHELF_R
    val t = TIER_T
    return listOf(
        Float2(0f, t), Float2(r - 0.026f, t),
        Float2(r - 0.023f, t + 0.004f), Float2(r - 0.018f, t + 0.005f), Float2(r - 0.014f, t + 0.002f), Float2(r - 0.013f, t),
        Float2(r - 0.006f, t - 0.001f), Float2(r - 0.001f, t - 0.005f), Float2(r + 0.002f, t - 0.011f), Float2(r + 0.001f, t - 0.017f),
        Float2(r - 0.003f, t - 0.021f), Float2(r - 0.008f, t - 0.023f), Float2(r - 0.009f, 0f),
        Float2(0f, 0f),
    )
}

/** A turned post [height] high: a block at each end, a vase between rings. */
private fun spindle(height: Float): List<Float2> = listOf(
    0f to 1f, 0.017f to 1f, 0.017f to 0.95f, 0.020f to 0.935f, 0.020f to 0.915f, 0.012f to 0.9f,
    0.015f to 0.82f, 0.019f to 0.68f, 0.021f to 0.55f, 0.019f to 0.42f, 0.013f to 0.3f, 0.011f to 0.26f,
    0.017f to 0.24f, 0.017f to 0.2f, 0.012f to 0.18f, 0.015f to 0.09f, 0.019f to 0.06f, 0.019f to 0f, 0f to 0f,
).map { (radius, fraction) -> Float2(radius, fraction * height) }

/** A bun foot under the lowest tier, [height] high. */
private fun foot(height: Float): List<Float2> = listOf(
    0f to 1f, 0.016f to 1f, 0.016f to 0.9f, 0.021f to 0.76f, 0.024f to 0.54f, 0.022f to 0.31f, 0.016f to 0.13f,
    0.012f to 0f, 0f to 0f,
).map { (radius, fraction) -> Float2(radius, fraction * height) }

/** The corner post above the top tier, through the crest, up to its knob. */
private fun crownPost(): List<Float2> {
    val top = TIER_TOPS.last()
    return listOf(
        0f to 1.035f, 0.006f to 1.032f, 0.012f to 1.02f, 0.015f to 1.0f, 0.013f to 0.985f, 0.007f to 0.975f,
        0.006f to 0.968f, 0.012f to 0.962f, 0.012f to 0.952f, 0.016f to 0.947f, 0.016f to top + 0.02f,
        0.019f to top + 0.01f, 0.019f to top, 0f to top,
    ).map { (radius, y) -> Float2(radius, y) }
}

/**
 * A carved gallery [GALLERY_LENGTH] long, starting at the corner end (u = 0): a solid band [band] high, rising by
 * [rise] towards the corner, topped by leafy lobes [lobe] high, each pierced by a curled opening, with a round one
 * under the notch between two lobes.
 */
private fun fretwork(band: Float, lobe: Float, rise: Float): Pair<List<Float2>, List<List<Float2>>> {
    val length = GALLERY_LENGTH
    val count = (length / 0.075f).roundToInt().coerceAtLeast(1)
    val period = length / count
    fun base(u: Float) = band + rise * (1 - u / length).pow(2)
    // Each lobe swells from a notch at either end, its tip leaning towards the corner like a curled leaf.
    fun top(u: Float): Float {
        val f = (u / period).coerceIn(0f, count.toFloat()).let { it - it.toInt().coerceAtMost(count - 1) }
        return base(u) + lobe * (sin(PI * f).pow(0.6) * (1 + 0.25 * sin(2 * PI * f))).toFloat()
    }
    val steps = 14

    val outline = mutableListOf(Float2(0f, 0f), Float2(length, 0f))
    for (j in count - 1 downTo 0) {
        for (k in steps downTo (if (j == 0) 0 else 1)) {
            val u = (j + k / steps.toFloat()) * period
            outline += Float2(u, top(u))
        }
    }

    val holes = mutableListOf<List<Float2>>()
    for (j in 0 until count) {
        val u = (j + 0.5f) * period
        val a = period * 0.2f
        val b = band * 0.26f
        val centre = Float2(u, base(u) * 0.5f + lobe * 0.35f)
        val tilt = Math.toRadians(if (j % 2 == 0) 32.0 else -32.0)
        holes += (0 until 14).map { k ->
            val t = 2 * PI * k / 14
            val y = b * sin(t)
            // Pinched at one end into a curl.
            val x = a * cos(t) + 0.35 * a * (y / b).pow(2)
            Float2(
                centre.x + (x * cos(tilt) - y * sin(tilt)).toFloat(),
                centre.y + (x * sin(tilt) + y * cos(tilt)).toFloat(),
            )
        }
        if (j > 0) {
            val notch = Float2(j * period, base(j * period) * 0.36f)
            holes += (0 until 10).map { k ->
                val t = 2 * PI * k / 10
                Float2(notch.x + 0.0055f * cos(t).toFloat(), notch.y + 0.0055f * sin(t).toFloat())
            }
        }
    }
    // Only openings that stay inside the wood.
    return outline to holes.filter { hole -> hole.all { it.y > 0.008f && it.y < top(it.x) - 0.006f && abs(it.x - length / 2) < length / 2 - 0.006f } }
}
