package io.github.caropeboka.diecasttracker.data.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import io.github.caropeboka.diecasttracker.data.PhotoStore
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A card or box squared up from the corners placed on a photo, with the background that still shows erased: the
 * corners rounded (a card's are), the hang hole found by itself, and whatever is tapped away or back by hand ([tap]).
 * [image] is how it looks now; [compose] puts it on the studio backdrop. One call at a time.
 */
class CardCutout private constructor(private val pixels: IntArray, val width: Int, val height: Int, rounded: Boolean) {
    private val erased = BooleanArray(pixels.size)
    private val history = ArrayDeque<Edit>()
    private val radius = if (rounded) CORNER_RADIUS * min(width, height) else 0f

    /** The card with what's erased transparent. */
    lateinit var image: Bitmap
        private set

    /** A hang hole was found and erased by itself. */
    var holeFound = false
        private set

    val canUndo get() = history.isNotEmpty()

    enum class Tap { Erased, Restored, TooBig }

    /**
     * At [x], [y] (the card's pixels): an erased part comes back, or the background there is erased: the patch of
     * about its colour, as long as it stays patch-sized ([Tap.TooBig] when it runs into the card's own picture).
     */
    fun tap(x: Int, y: Int): Tap? {
        if (x !in 0 until width || y !in 0 until height) return null
        val start = y * width + x
        val result = if (erased[start]) {
            val region = region(start, Int.MAX_VALUE) { erased[it] }!!
            region.forEach { erased[it] = false }
            history += Edit(region, erased = false)
            Tap.Restored
        } else {
            val reference = colourAround(x, y)
            val limit = (MAX_PATCH * pixels.size).toInt()
            val patch = WAND_TOLERANCES.firstNotNullOfOrNull { tolerance ->
                region(start, limit) { !erased[it] && distance(pixels[it], reference) <= tolerance * tolerance }
            } ?: return Tap.TooBig
            // With its rim: the photo blends the edge of a patch into what's around it.
            val grown = grow(patch, 1).filter { !erased[it] }.toIntArray()
            grown.forEach { erased[it] = true }
            history += Edit(grown, erased = true)
            Tap.Erased
        }
        image = render()
        return result
    }

    fun undo() {
        val edit = history.removeLastOrNull() ?: return
        edit.indices.forEach { erased[it] = !edit.erased }
        image = render()
    }

    fun compose(maxEdge: Int = PhotoStore.MAX_EDGE): Bitmap = StudioComposer.placeCard(image, maxEdge)

    private class Edit(val indices: IntArray, val erased: Boolean)

    /**
     * The hang hole: in the middle at the top, colours seen [behind] the card far more than on it, closed in by the card
     * (or open to its top edge), hole-sized. Erased with its rim; false when there's none.
     */
    private fun eraseHangHole(behind: List<Int>): Boolean {
        if (behind.size < MIN_BEHIND) return false
        val left = (width * HOLE_LEFT).toInt()
        val right = (width * (1 - HOLE_LEFT)).toInt()
        val bottom = (height * HOLE_BOTTOM).toInt()
        val windowWidth = right - left
        if (windowWidth < 3 || bottom < 3) return false

        // How common each colour is behind the card, and on the card's top part around the search window.
        val behindShare = shares(behind.map(::bin))
        val onCard = ArrayList<Int>()
        for (y in 0 until (height * CARD_TOP).toInt()) {
            for (x in 0 until width) if (x !in left until right || y >= bottom) onCard += bin(pixels[y * width + x])
        }
        val cardShare = shares(onCard)
        val candidate = BooleanArray(windowWidth * bottom) { i ->
            val b = bin(pixels[i / windowWidth * width + left + i % windowWidth])
            behindShare[b] >= MIN_SHARE && behindShare[b] > HOLE_ODDS * cardShare[b]
        }

        // Its patches that are shaped like a hole.
        val hole = BooleanArray(candidate.size)
        val seen = BooleanArray(candidate.size)
        val queue = IntArray(candidate.size)
        val area = width * height
        for (start in candidate.indices) {
            if (!candidate[start] || seen[start]) continue
            var head = 0
            var tail = 0
            var open = false
            var minX = windowWidth
            var maxX = -1
            var minY = bottom
            var maxY = -1
            seen[start] = true
            queue[tail++] = start
            while (head < tail) {
                val i = queue[head++]
                val x = i % windowWidth
                val y = i / windowWidth
                if (x == 0 || x == windowWidth - 1 || y == bottom - 1) open = true
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < windowWidth - 1) i + 1 else -1, i - windowWidth, i + windowWidth)) {
                    if (n in candidate.indices && candidate[n] && !seen[n]) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
            }
            val span = MIN_HOLE_SPAN * width
            if (!open && tail >= MIN_HOLE * area && tail <= MAX_HOLE * area && maxX - minX + 1 >= span && maxY - minY + 1 >= span) {
                for (k in 0 until tail) hole[queue[k]] = true
            }
        }
        if (hole.none { it }) return false

        // Specks inside it (a glint) go too: whatever the window's edge can't reach without crossing the hole.
        val reached = BooleanArray(hole.size)
        var head = 0
        var tail = 0
        for (i in hole.indices) {
            val x = i % windowWidth
            val y = i / windowWidth
            if ((x == 0 || x == windowWidth - 1 || y == 0 || y == bottom - 1) && !hole[i]) {
                reached[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % windowWidth
            for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < windowWidth - 1) i + 1 else -1, i - windowWidth, i + windowWidth)) {
                if (n in hole.indices && !hole[n] && !reached[n]) {
                    reached[n] = true
                    queue[tail++] = n
                }
            }
        }
        val found = hole.indices.filter { hole[it] || !reached[it] }.map { it / windowWidth * width + left + it % windowWidth }
        grow(found.toIntArray(), max(1, (RIM * width).roundToInt())).forEach { erased[it] = true }
        return true
    }

    /** The pixels around [start] that pass [accept], side by side; null when they're more than [limit]. */
    private inline fun region(start: Int, limit: Int, accept: (Int) -> Boolean): IntArray? {
        val inside = BooleanArray(pixels.size)
        val queue = IntArray(min(pixels.size, limit))
        var head = 0
        var tail = 0
        inside[start] = true
        queue[tail++] = start
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < width - 1) i + 1 else -1, i - width, i + width)) {
                if (n in pixels.indices && !inside[n] && accept(n)) {
                    if (tail == queue.size) return null
                    inside[n] = true
                    queue[tail++] = n
                }
            }
        }
        return queue.copyOf(tail)
    }

    /** [region] and every pixel within [by] of it. */
    private fun grow(region: IntArray, by: Int): IntArray {
        val inside = BooleanArray(pixels.size)
        for (i in region) {
            val x = i % width
            val y = i / width
            for (ny in max(0, y - by)..min(height - 1, y + by)) {
                for (nx in max(0, x - by)..min(width - 1, x + by)) inside[ny * width + nx] = true
            }
        }
        return inside.indices.filter { inside[it] }.toIntArray()
    }

    /** The colour around [x], [y] on average (5 x 5), steadier than one pixel. */
    private fun colourAround(x: Int, y: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (ny in max(0, y - 2)..min(height - 1, y + 2)) {
            for (nx in max(0, x - 2)..min(width - 1, x + 2)) {
                val c = pixels[ny * width + nx]
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
        }
        return Color.rgb(r / n, g / n, b / n)
    }

    private fun render(): Bitmap {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (erased[i]) continue
                var alpha = 255 * coverage(x, y)
                // A softer edge where something was erased.
                if ((x > 0 && erased[i - 1]) || (x < width - 1 && erased[i + 1]) || (y > 0 && erased[i - width]) || (y < height - 1 && erased[i + width])) {
                    alpha /= 2
                }
                out[i] = (alpha.roundToInt() shl 24) or (pixels[i] and 0xFFFFFF)
            }
        }
        return createBitmap(width, height).apply { setPixels(out, 0, width, 0, 0, width, height) }
    }

    /** How much of the pixel at [x], [y] is inside the rounded corners. */
    private fun coverage(x: Int, y: Int): Float {
        val px = x + 0.5f
        val py = y + 0.5f
        val cx = when {
            px < radius -> radius
            px > width - radius -> width - radius
            else -> return 1f
        }
        val cy = when {
            py < radius -> radius
            py > height - radius -> height - radius
            else -> return 1f
        }
        return (radius - hypot(px - cx, py - cy) + 0.5f).coerceIn(0f, 1f)
    }

    companion object {
        /** A card's corner radius as a share of its width (about 4 mm on a Hot Wheels or Matchbox card). */
        private const val CORNER_RADIUS = 0.035f

        /** Where the hang hole is looked for: between these shares of the width, in this top share of the height. */
        private const val HOLE_LEFT = 0.3f
        private const val HOLE_BOTTOM = 0.25f

        /** The card's colours are learnt from this top share of it, the search window left out. */
        private const val CARD_TOP = 0.3f

        /** A hole's size as a share of the card: its area, and its least width and height (of the card's width). */
        private const val MIN_HOLE = 0.0008
        private const val MAX_HOLE = 0.04
        private const val MIN_HOLE_SPAN = 0.02f

        /** A colour counts as seen behind the card at this share of the samples, this many times more than on the card. */
        private const val MIN_SHARE = 0.002f
        private const val HOLE_ODDS = 3f

        /** Samples behind the card needed to look for the hole: a card filling the photo leaves none. */
        private const val MIN_BEHIND = 200

        /** Where the samples behind the card are taken: this far outside each side (of the card's shorter side). */
        private val BEHIND = floatArrayOf(0.02f, 0.035f, 0.05f, 0.065f)
        private const val BEHIND_STEPS = 100

        /** The rim taken along with a hole, as a share of the width. */
        private const val RIM = 0.003f

        /** A tapped patch: the colour distances tried, widest first, and its largest share of the card. */
        private val WAND_TOLERANCES = listOf(48, 36, 26, 18)
        private const val MAX_PATCH = 0.08

        /**
         * The card within [corners] (four points on [photo], see [StudioComposer.cardQuad]) as an upright rectangle,
         * corners [rounded], its hang hole erased if one is found. Null when the corners make no card.
         */
        fun from(photo: Bitmap, corners: FloatArray, rounded: Boolean): CardCutout? {
            val quad = StudioComposer.cardQuad(corners, photo.width, photo.height) ?: return null
            val rectangle = StudioComposer.rectangle(quad)
            val width = max(1, rectangle[2].roundToInt())
            val height = max(1, rectangle[5].roundToInt())
            val target = floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat())
            val matrix = Matrix()
            if (!matrix.setPolyToPoly(quad, 0, target, 0, 4)) return null

            val card = createBitmap(width, height)
            Canvas(card).apply {
                drawColor(Color.WHITE)
                drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val pixels = IntArray(width * height).also { card.getPixels(it, 0, width, 0, 0, width, height) }
            card.recycle()

            // What's behind the card: the photo just outside its sides.
            val back = Matrix().also { matrix.invert(it) }
            val behind = ArrayList<Int>()
            val point = FloatArray(2)
            val unit = min(width, height)
            for (side in 0 until 4) {
                for (step in 0 until BEHIND_STEPS) {
                    val along = (step + 0.5f) / BEHIND_STEPS
                    for (distance in BEHIND) {
                        val out = distance * unit
                        when (side) {
                            0 -> point.set(along * width, -out)
                            1 -> point.set(width + out, along * height)
                            2 -> point.set(along * width, height + out)
                            else -> point.set(-out, along * height)
                        }
                        back.mapPoints(point)
                        if (point[0] >= 0 && point[0] < photo.width && point[1] >= 0 && point[1] < photo.height) {
                            behind += photo.getPixel(point[0].toInt(), point[1].toInt())
                        }
                    }
                }
            }

            return CardCutout(pixels, width, height, rounded).apply {
                holeFound = eraseHangHole(behind)
                image = render()
            }
        }

        private fun FloatArray.set(x: Float, y: Float) {
            this[0] = x
            this[1] = y
        }

        /** Colours by their top 4 bits per channel. */
        private fun bin(colour: Int) = (colour shr 20 and 0xF shl 8) or (colour shr 12 and 0xF shl 4) or (colour shr 4 and 0xF)

        /** Each colour bin's share of [bins], counting its neighbours, so nearly the same colour counts too. */
        private fun shares(bins: List<Int>): FloatArray {
            val counts = IntArray(4096)
            bins.forEach { counts[it]++ }
            val total = max(1, bins.size).toFloat()
            return FloatArray(4096) { b ->
                val r = b shr 8
                val g = b shr 4 and 0xF
                val bl = b and 0xF
                var sum = 0
                for (nr in max(0, r - 1)..min(15, r + 1)) {
                    for (ng in max(0, g - 1)..min(15, g + 1)) {
                        for (nb in max(0, bl - 1)..min(15, bl + 1)) sum += counts[nr shl 8 or (ng shl 4) or nb]
                    }
                }
                sum / total
            }
        }

        private fun distance(a: Int, b: Int): Int {
            val r = Color.red(a) - Color.red(b)
            val g = Color.green(a) - Color.green(b)
            val bl = Color.blue(a) - Color.blue(b)
            return r * r + g * g + bl * bl
        }
    }
}
