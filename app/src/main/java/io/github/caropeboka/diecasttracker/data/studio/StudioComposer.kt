package io.github.caropeboka.diecasttracker.data.studio

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import io.github.caropeboka.diecasttracker.data.PhotoStore
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puts a cut-out diecast on a studio backdrop: edges firmed up, specks of the old background dropped, a card or box
 * drawn as the upright rectangle it is (straight, and square to the camera even when the phone wasn't), centred on
 * white with a soft shadow, at the collection card's 4:5. Never enlarged: a small subject gives a smaller photo rather
 * than a blurry one. A card or box can also be cut out by its corners, placed by hand ([CardCutout]): the background
 * remover takes a card's printed picture for the subject at times.
 */
object StudioComposer {
    /** The canvas, width to height, like the collection's cards. */
    const val ASPECT = 4f / 5f

    /** White around the subject, per side, as a share of the canvas. */
    private const val MARGIN = 0.08f

    /** Below this a tilt isn't worth the resampling; above it the slant is likely on purpose. */
    private const val MIN_TILT = 0.5
    private const val MAX_TILT = 25.0

    /** How much of its tightest rectangle the subject fills to count as a card or box (a car fills far less). */
    private const val MIN_FILL = 0.85

    /** A subject smaller than this share of the photo is a speck, not a diecast. */
    private const val MIN_AREA = 0.005

    /** A loose bit of the cut-out smaller than this share of the biggest one is a speck of the old background. */
    private const val SPECK = 0.01

    /**
     * A side of a card or box is a straight line along at least this share of it, within this share of the card's
     * size; a corner bent or hidden by a finger is left out.
     */
    private const val MIN_EDGE_SHARE = 0.5
    private const val EDGE_TOLERANCE = 0.008

    /** How far a side may lean once the card is turned straight (tan 12°). */
    private const val MAX_SLOPE = 0.21

    /** Lines tried per side: through two of this many points along it. */
    private const val EDGE_TRIES = 40

    /** A side at least this share of the opposite one; at a steeper angle squaring the card up would stretch it. */
    private const val MIN_SIDE_RATIO = 0.75

    /** Soft edges between these alphas are stretched, so less of the old background shows around the subject. */
    private const val MATTE_LOW = 96
    private const val MATTE_HIGH = 192

    /** The shadow under the subject: its colour, and how far down it falls as a share of the subject's height. */
    private val SHADOW = Color.argb(56, 0, 0, 0)
    private const val SHADOW_DROP = 0.015f

    /**
     * [cutout]: the photo with everything but the subject transparent. Null when there is no subject to speak of.
     * The result is a new bitmap; [cutout] is left as it was.
     */
    fun compose(cutout: Bitmap, maxEdge: Int = PhotoStore.MAX_EDGE): Bitmap? {
        val traced = trace(cutout) ?: return null
        val subject = createBitmap(traced.width, traced.height).apply {
            setPixels(traced.pixels, 0, traced.width, 0, 0, traced.width, traced.height)
        }
        val (tilt, rectArea) = tightestRectangle(traced.hull)
        val boxLike = traced.area / rectArea >= MIN_FILL && abs(tilt) <= MAX_TILT

        // A card or box becomes the upright rectangle it is, or failing that is turned straight; anything else stays
        // as it was. Then cropped to the subject.
        val matrix = Matrix()
        val squared = boxLike && squareUp(traced, tilt)?.let { corners -> matrix.setPolyToPoly(corners, 0, rectangle(corners), 0, 4) } == true
        if (!squared) {
            matrix.reset()
            if (boxLike && abs(tilt) >= MIN_TILT) matrix.setRotate(-tilt.toFloat(), traced.width / 2f, traced.height / 2f)
        }
        val corners = FloatArray(traced.hull.size * 2).also { points ->
            traced.hull.forEachIndexed { i, p ->
                points[i * 2] = p.x.toFloat()
                points[i * 2 + 1] = p.y.toFloat()
            }
            matrix.mapPoints(points)
        }
        val xs = corners.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = corners.filterIndexed { i, _ -> i % 2 == 1 }
        // A pixel more on each side for the soft edge outside the traced outline.
        val left = xs.min() - 1f
        val top = ys.min() - 1f
        val boxWidth = xs.max() + 1f - left
        val boxHeight = ys.max() + 1f - top
        matrix.postTranslate(-left, -top)
        return onBackdrop(subject, matrix, boxWidth, boxHeight, maxEdge).also { subject.recycle() }
    }

    /**
     * [cutout]'s subject alone, edges firmed up and specks dropped, cropped to it and still transparent round it (a
     * decoration for the 3D garage). Null when there is no subject to speak of.
     */
    fun isolate(cutout: Bitmap): Bitmap? {
        val traced = trace(cutout) ?: return null
        val width = traced.width
        var left = width
        var top = traced.height
        var right = -1
        var bottom = -1
        for (i in traced.pixels.indices) {
            if (traced.pixels[i] ushr 24 == 0) continue
            val x = i % width
            val y = i / width
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
        }
        if (right < left) return null
        return createBitmap(right - left + 1, bottom - top + 1).apply {
            setPixels(traced.pixels, top * width + left, width, 0, 0, this.width, this.height)
        }
    }

    /** A card cut out by its corners ([CardCutout]): all of [card] is the subject, transparent where it was erased. */
    internal fun placeCard(card: Bitmap, maxEdge: Int): Bitmap =
        onBackdrop(card, Matrix(), card.width.toFloat(), card.height.toFloat(), maxEdge)

    /**
     * Where the card or box in [cutout] is: its corners in the photo (top left, top right, bottom right, bottom left).
     * Null when the cut-out isn't shaped like one.
     */
    fun cardCorners(cutout: Bitmap): FloatArray? {
        val traced = trace(cutout) ?: return null
        val (tilt, rectArea) = tightestRectangle(traced.hull)
        if (traced.area / rectArea < MIN_FILL || abs(tilt) > MAX_TILT) return null
        return squareUp(traced, tilt)
    }

    /**
     * Four points placed on the corners of a card or box, in order: top left, top right, bottom right, bottom left.
     * Null unless they make a four-sided shape without a dent, at least [MIN_AREA] of a [width] x [height] photo.
     */
    fun cardQuad(points: FloatArray, width: Int, height: Int): FloatArray? {
        val centreX = (0 until 4).sumOf { points[it * 2].toDouble() } / 4
        val centreY = (0 until 4).sumOf { points[it * 2 + 1].toDouble() } / 4
        // Round the centre clockwise on screen, from the top left one.
        val round = (0 until 4).sortedBy { atan2(points[it * 2 + 1] - centreY, points[it * 2] - centreX) }
        val first = round.indexOf(round.minBy { points[it * 2] + points[it * 2 + 1] })
        val quad = FloatArray(8)
        for (i in 0 until 4) {
            val p = round[(first + i) % 4]
            quad[i * 2] = points[p * 2]
            quad[i * 2 + 1] = points[p * 2 + 1]
        }

        // Turning the same way at every corner.
        var area = 0.0
        for (i in 0 until 4) {
            val a = i * 2
            val b = (i + 1) % 4 * 2
            val c = (i + 2) % 4 * 2
            val turn = (quad[b] - quad[a]) * (quad[c + 1] - quad[b + 1]) - (quad[b + 1] - quad[a + 1]) * (quad[c] - quad[b])
            if (turn <= 0) return null
            area += quad[a] * quad[b + 1] - quad[b] * quad[a + 1]
        }
        return quad.takeIf { area / 2 >= MIN_AREA * width * height }
    }

    /** The cut-out's pixels with the matte firmed up and the specks dropped; the subject's area and its outline's hull. */
    private class Traced(val pixels: IntArray, val width: Int, val height: Int, val area: Long, val hull: List<Point>)

    private fun trace(cutout: Bitmap): Traced? {
        val width = cutout.width
        val height = cutout.height
        val pixels = IntArray(width * height)
        cutout.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) pixels[i] = (matte(pixels[i] ushr 24) shl 24) or (pixels[i] and 0xFFFFFF)
        dropSpecks(pixels, width, height)

        // The subject's area and the corners of each row's outermost pixels.
        var area = 0L
        val outline = ArrayList<Point>()
        for (y in 0 until height) {
            var left = -1
            var right = -1
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 >= 128) {
                    area++
                    if (left < 0) left = x
                    right = x
                }
            }
            if (left >= 0) {
                outline += Point(left.toDouble(), y.toDouble())
                outline += Point(right + 1.0, y.toDouble())
                outline += Point(left.toDouble(), y + 1.0)
                outline += Point(right + 1.0, y + 1.0)
            }
        }
        if (area < MIN_AREA * width * height) return null
        return Traced(pixels, width, height, area, convexHull(outline))
    }

    /**
     * A white canvas at [ASPECT] with room for a box of this size (from 0, 0) plus the margin, never enlarged and at
     * most [maxEdge] high. [matrix], which draws into the box, is extended to centre it there. Returns the canvas and
     * where the box is on it.
     */
    private fun backdrop(matrix: Matrix, boxWidth: Float, boxHeight: Float, maxEdge: Int): Pair<Bitmap, RectF> {
        val needed = max(boxHeight, boxWidth / ASPECT) / (1f - 2 * MARGIN)
        val scale = min(1f, maxEdge / needed)
        val canvasHeight = max(1, (needed * scale).roundToInt())
        val canvasWidth = max(1, (canvasHeight * ASPECT).roundToInt())
        val left = (canvasWidth - boxWidth * scale) / 2f
        val top = (canvasHeight - boxHeight * scale) / 2f
        matrix.postScale(scale, scale)
        matrix.postTranslate(left, top)
        val studio = createBitmap(canvasWidth, canvasHeight).apply { eraseColor(Color.WHITE) }
        return studio to RectF(left, top, left + boxWidth * scale, top + boxHeight * scale)
    }

    /** [subject], drawn by [matrix] into a box of this size from 0, 0, on the studio backdrop with a soft shadow. */
    private fun onBackdrop(subject: Bitmap, matrix: Matrix, boxWidth: Float, boxHeight: Float, maxEdge: Int): Bitmap {
        val (studio, box) = backdrop(matrix, boxWidth, boxHeight, maxEdge)
        val canvas = Canvas(studio)

        // A soft shadow, just below the subject.
        val blur = max(2f, boxHeight * 0.02f)
        val offset = IntArray(2)
        val shadow = subject.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }, offset)
        val shadowMatrix = Matrix().apply {
            setTranslate(offset[0].toFloat(), offset[1].toFloat())
            postConcat(matrix)
            postTranslate(0f, box.height() * SHADOW_DROP)
        }
        canvas.drawBitmap(shadow, shadowMatrix, Paint(Paint.FILTER_BITMAP_FLAG).apply { color = SHADOW })
        shadow.recycle()

        canvas.drawBitmap(subject, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return studio
    }

    /** Where [quad]'s corners go: an upright rectangle from 0, 0, as wide and as tall as its opposite sides on average. */
    internal fun rectangle(quad: FloatArray): FloatArray {
        fun side(a: Int, b: Int) = hypot(quad[a * 2] - quad[b * 2], quad[a * 2 + 1] - quad[b * 2 + 1])
        val width = (side(0, 1) + side(3, 2)) / 2
        val height = (side(0, 3) + side(1, 2)) / 2
        return floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
    }

    private fun matte(alpha: Int): Int = when {
        alpha <= MATTE_LOW -> 0
        alpha >= MATTE_HIGH -> 255
        else -> (alpha - MATTE_LOW) * 255 / (MATTE_HIGH - MATTE_LOW)
    }

    /** Clears the loose bits (touching neither by side nor by corner) much smaller than the biggest one. */
    private fun dropSpecks(pixels: IntArray, width: Int, height: Int) {
        val label = IntArray(pixels.size)
        val sizes = ArrayList<Int>()
        val queue = IntArray(pixels.size)
        for (start in pixels.indices) {
            if (label[start] != 0 || pixels[start] ushr 24 == 0) continue
            val id = sizes.size + 1
            var head = 0
            var tail = 0
            label[start] = id
            queue[tail++] = start
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val n = ny * width + nx
                        if (label[n] == 0 && pixels[n] ushr 24 != 0) {
                            label[n] = id
                            queue[tail++] = n
                        }
                    }
                }
            }
            sizes += tail
        }
        val biggest = sizes.maxOrNull() ?: return
        for (i in pixels.indices) {
            if (label[i] != 0 && sizes[label[i] - 1] < SPECK * biggest) pixels[i] = pixels[i] and 0xFFFFFF
        }
    }

    /**
     * The corners of the card or box [traced] is (top left, top right, bottom right, bottom left), seen [tilt] degrees
     * turned. Each side is the straight line along most of it ([edgeLine]). Null when a side has no clear line, the
     * lines don't hold the subject, or the photo was taken at too steep an angle.
     */
    private fun squareUp(traced: Traced, tilt: Double): FloatArray? {
        val pixels = traced.pixels
        val width = traced.width
        val height = traced.height
        val area = traced.area

        // Turned straight, the sides lie along the rows and columns: u across, v down.
        val angle = Math.toRadians(-tilt)
        val c = cos(angle)
        val s = sin(angle)
        val us = listOf(0.0, width * c, -height * s, width * c - height * s)
        val vs = listOf(0.0, width * s, height * c, width * s + height * c)
        val uMin = us.min()
        val vMin = vs.min()
        val rows = ceil(vs.max() - vMin).toInt() + 1
        val columns = ceil(us.max() - uMin).toInt() + 1

        // The outermost subject pixel on each row (left, right) and each column (top, bottom).
        val leftmost = FloatArray(rows) { Float.POSITIVE_INFINITY }
        val rightmost = FloatArray(rows) { Float.NEGATIVE_INFINITY }
        val topmost = FloatArray(columns) { Float.POSITIVE_INFINITY }
        val bottommost = FloatArray(columns) { Float.NEGATIVE_INFINITY }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 < 128) continue
                val u = ((x + 0.5) * c - (y + 0.5) * s - uMin).toFloat()
                val v = ((x + 0.5) * s + (y + 0.5) * c - vMin).toFloat()
                val row = v.toInt().coerceIn(0, rows - 1)
                val column = u.toInt().coerceIn(0, columns - 1)
                leftmost[row] = min(leftmost[row], u)
                rightmost[row] = max(rightmost[row], u)
                topmost[column] = min(topmost[column], v)
                bottommost[column] = max(bottommost[column], v)
            }
        }

        fun samples(edge: FloatArray) = edge.indices.filter { edge[it].isFinite() }.map { Point(it + 0.5, edge[it].toDouble()) }
        val sides = listOf(leftmost, rightmost, topmost, bottommost).map(::samples)
        val tolerance = max(2.0, EDGE_TOLERANCE * max(sides[0].size, sides[2].size))
        // Left and right: u along v; top and bottom: v along u.
        val (left, right, top, bottom) = sides.map { edgeLine(it, tolerance) ?: return null }

        fun corner(side: Line, end: Line): Point {
            val u = (side.offset + side.slope * end.offset) / (1 - side.slope * end.slope)
            return Point(u, end.at(u))
        }
        val quad = listOf(corner(left, top), corner(right, top), corner(right, bottom), corner(left, bottom))

        fun length(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val topLength = length(quad[0], quad[1])
        val bottomLength = length(quad[3], quad[2])
        val leftLength = length(quad[0], quad[3])
        val rightLength = length(quad[1], quad[2])
        if (min(topLength, bottomLength) < MIN_SIDE_RATIO * max(topLength, bottomLength)) return null
        if (min(leftLength, rightLength) < MIN_SIDE_RATIO * max(leftLength, rightLength)) return null
        val quadArea = abs(quad.indices.sumOf { i -> quad[i].x * quad[(i + 1) % 4].y - quad[(i + 1) % 4].x * quad[i].y }) / 2
        if (area / quadArea !in MIN_FILL..1 / MIN_FILL) return null

        // Back into the photo.
        val corners = FloatArray(8)
        quad.forEachIndexed { i, p ->
            val u = p.x + uMin
            val v = p.y + vMin
            corners[i * 2] = (u * c + v * s).toFloat()
            corners[i * 2 + 1] = (-u * s + v * c).toFloat()
        }
        return corners
    }

    internal data class Point(val x: Double, val y: Double)

    /** y = offset + slope * x. */
    internal data class Line(val offset: Double, val slope: Double) {
        fun at(x: Double) = offset + slope * x
    }

    /**
     * The straight line along most of [points] (x along a side, in order; y across it): the line through two of them
     * that passes the most within [tolerance], fitted to those. Null when none passes [MIN_EDGE_SHARE] of them.
     */
    internal fun edgeLine(points: List<Point>, tolerance: Double): Line? {
        if (points.size < 2) return null
        val count = min(points.size, EDGE_TRIES)
        val tries = List(count) { points[it * points.size / count] }
        val span = points.last().x - points.first().x
        fun passes(line: Line, p: Point) = abs(p.y - line.at(p.x)) <= tolerance

        var best: Line? = null
        var bestPassed = 0
        for (i in tries.indices) {
            for (j in i + 1 until tries.size) {
                val p = tries[i]
                val q = tries[j]
                if (q.x - p.x < span / 4) continue
                val slope = (q.y - p.y) / (q.x - p.x)
                if (abs(slope) > MAX_SLOPE) continue
                val line = Line(p.y - slope * p.x, slope)
                val passed = points.count { passes(line, it) }
                if (passed > bestPassed) {
                    best = line
                    bestPassed = passed
                }
            }
        }
        val line = best?.takeIf { bestPassed >= MIN_EDGE_SHARE * points.size } ?: return null

        // Least squares through the points it passes.
        val near = points.filter { passes(line, it) }
        val meanX = near.sumOf { it.x } / near.size
        val meanY = near.sumOf { it.y } / near.size
        val slope = near.sumOf { (it.x - meanX) * (it.y - meanY) } / near.sumOf { (it.x - meanX) * (it.x - meanX) }
        return Line(meanY - slope * meanX, slope)
    }

    /** Andrew's monotone chain, without collinear points. */
    internal fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted

        fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val hull = ArrayList<Point>(sorted.size + 1)
        for (pass in 0..1) {
            val start = hull.size
            for (p in if (pass == 0) sorted else sorted.asReversed()) {
                while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
                hull += p
            }
            hull.removeAt(hull.size - 1)
        }
        return hull
    }

    /**
     * The smallest rectangle around [hull] (one of its sides lies on a hull edge): its tilt in degrees, in (-45, 45],
     * positive turned clockwise on screen, and its area.
     */
    internal fun tightestRectangle(hull: List<Point>): Pair<Double, Double> {
        var bestArea = Double.MAX_VALUE
        var bestAngle = 0.0
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val angle = atan2(b.y - a.y, b.x - a.x)
            val c = cos(angle)
            val s = sin(angle)
            var minU = Double.MAX_VALUE
            var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE
            var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val u = p.x * c + p.y * s
                val v = p.y * c - p.x * s
                minU = min(minU, u)
                maxU = max(maxU, u)
                minV = min(minV, v)
                maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                bestAngle = angle
            }
        }
        var degrees = Math.toDegrees(bestAngle) % 90.0
        if (degrees < 0) degrees += 90.0
        if (degrees > 45.0) degrees -= 90.0
        return degrees to bestArea
    }
}
