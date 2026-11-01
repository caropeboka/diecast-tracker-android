package io.github.caropeboka.diecasttracker.data.decor

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Takes the dust off a cut-out figure, as far as a photo allows. Specks (a few pixels lighter or darker than the calm
 * surface round them) are filled in from that surface; then the grey veil dust lays over everything is lifted: the
 * shadows brought back down, the highlights up, the colours a little fuller. All colours move together, so the
 * figure's paint keeps its hue. Only the subject's pixels count; see-through ones stay as they are. A painted dot as
 * small as a speck on a flat colour could go too, so the original is kept beside it.
 */
object Dusting {
    /** The neighbourhood a speck is judged against: 5 × 5. */
    private const val RADIUS = 2

    /** A speck stands out from the middle of its neighbourhood by at least this much light (0–255)... */
    private const val SPECK = 22

    /** ...on a surface this calm: half its neighbours within this much of the middle (an edge isn't). */
    private const val CALM = 12

    /** Where the shadows and highlights are measured: these shares of the subject's pixels, by light. */
    private const val LOW = 0.01
    private const val HIGH = 0.99

    /** How far the shadows come down (to this share of where they were), and the highlights at least up to. */
    private const val SHADOWS = 0.35f
    private const val HIGHLIGHTS = 240f

    /** No more contrast than this, so a dark, plain figure isn't blown up into noise. */
    private const val MAX_GAIN = 1.7f

    private const val SATURATION = 1.22f

    fun clean(image: Bitmap): Bitmap {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height).also { image.getPixels(it, 0, width, 0, 0, width, height) }
        val inside = BooleanArray(pixels.size) { pixels[it] ushr 24 >= 128 }
        val light = IntArray(pixels.size) { light(pixels[it]) }
        val cleaned = pixels.copyOf()

        val window = IntArray((2 * RADIUS + 1) * (2 * RADIUS + 1))
        val deviations = IntArray(window.size)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (!inside[i]) continue
            var count = 0
            for (ny in max(0, y - RADIUS)..min(height - 1, y + RADIUS)) for (nx in max(0, x - RADIUS)..min(width - 1, x + RADIUS)) {
                val j = ny * width + nx
                if (inside[j]) window[count++] = light[j]
            }
            if (count < 9) continue
            window.sort(0, count)
            val middle = window[count / 2]
            for (k in 0 until count) deviations[k] = abs(window[k] - middle)
            deviations.sort(0, count)
            val spread = deviations[count / 2]
            if (spread > CALM || abs(light[i] - middle) <= max(SPECK, 3 * spread)) continue

            // The calm surface's own colour.
            val near = max(2 * spread, 6)
            var red = 0
            var green = 0
            var blue = 0
            var n = 0
            for (ny in max(0, y - RADIUS)..min(height - 1, y + RADIUS)) for (nx in max(0, x - RADIUS)..min(width - 1, x + RADIUS)) {
                val j = ny * width + nx
                if (inside[j] && abs(light[j] - middle) <= near) {
                    red += pixels[j] shr 16 and 0xff
                    green += pixels[j] shr 8 and 0xff
                    blue += pixels[j] and 0xff
                    n++
                }
            }
            if (n > 0) cleaned[i] = (pixels[i] and 0xFF000000.toInt()) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
        }

        // The veil: where the subject's shadows and highlights are now, and where they go.
        val histogram = IntArray(256)
        var total = 0
        for (i in cleaned.indices) {
            if (inside[i]) {
                histogram[light(cleaned[i])]++
                total++
            }
        }
        if (total == 0) return image.copy(Bitmap.Config.ARGB_8888, false)
        val low = percentile(histogram, total, LOW).toFloat()
        val high = max(percentile(histogram, total, HIGH).toFloat(), low + 1)
        val newLow = low * SHADOWS
        val gain = min((max(high, HIGHLIGHTS) - newLow) / (high - low), MAX_GAIN)

        for (i in cleaned.indices) {
            val p = cleaned[i]
            if (p ushr 24 == 0) continue
            val red = newLow + ((p shr 16 and 0xff) - low) * gain
            val green = newLow + ((p shr 8 and 0xff) - low) * gain
            val blue = newLow + ((p and 0xff) - low) * gain
            val grey = 0.299f * red + 0.587f * green + 0.114f * blue
            cleaned[i] = (p and 0xFF000000.toInt()) or
                (channel(grey + (red - grey) * SATURATION) shl 16) or
                (channel(grey + (green - grey) * SATURATION) shl 8) or
                channel(grey + (blue - grey) * SATURATION)
        }
        return createBitmap(width, height).apply { setPixels(cleaned, 0, width, 0, 0, width, height) }
    }

    private fun light(p: Int) = ((p shr 16 and 0xff) * 299 + (p shr 8 and 0xff) * 587 + (p and 0xff) * 114) / 1000

    private fun percentile(histogram: IntArray, total: Int, share: Double): Int {
        val wanted = (total * share).roundToInt()
        var seen = 0
        histogram.forEachIndexed { value, count ->
            seen += count
            if (seen > wanted) return value
        }
        return 255
    }

    private fun channel(value: Float) = value.roundToInt().coerceIn(0, 255)
}
