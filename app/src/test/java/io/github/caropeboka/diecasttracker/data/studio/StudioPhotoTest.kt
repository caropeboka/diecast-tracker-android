package io.github.caropeboka.diecasttracker.data.studio

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioPhotoTest {
    private val env = TestEnvironment()

    @After
    fun tearDown() = env.close()

    /** A transparent photo with a red shape [degrees] turned clockwise around its centre, like a cut-out. */
    private fun cutout(width: Int, height: Int, shapeWidth: Float, shapeHeight: Float, degrees: Float, oval: Boolean = false): Bitmap =
        createBitmap(width, height).also {
            Canvas(it).apply {
                rotate(degrees, width / 2f, height / 2f)
                val rect = RectF((width - shapeWidth) / 2, (height - shapeHeight) / 2, (width + shapeWidth) / 2, (height + shapeHeight) / 2)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
                if (oval) drawOval(rect, paint) else drawRect(rect, paint)
            }
        }

    /** Like [cutout], for a red polygon. */
    private fun polygon(width: Int, height: Int, degrees: Float, vararg points: Pair<Float, Float>): Bitmap =
        createBitmap(width, height).also {
            Canvas(it).apply {
                rotate(degrees, width / 2f, height / 2f)
                val path = Path().apply {
                    moveTo(points[0].first, points[0].second)
                    points.drop(1).forEach { (x, y) -> lineTo(x, y) }
                    close()
                }
                drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
            }
        }

    /** A card 15% wider at the bottom than at the top, as when the phone leans back, and slanted 5°. */
    private fun cardFromBelow() = polygon(900, 900, 5f, *CARD_FROM_BELOW)

    /** [cardFromBelow]'s corners where they are in the photo, top left first. */
    private val cardFromBelowCorners = CARD_FROM_BELOW.flatMap { (x, y) ->
        val angle = Math.toRadians(5.0)
        listOf((450 + (x - 450) * cos(angle) - (y - 450) * sin(angle)).toFloat(), (450 + (x - 450) * sin(angle) + (y - 450) * cos(angle)).toFloat())
    }.toFloatArray()

    /** The red pixels' box: left, top, right, bottom (inclusive) and how many there are. */
    private fun redBox(image: Bitmap): Pair<IntArray, Int> {
        val box = intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (Color.red(pixel) > 200 && Color.green(pixel) < 90 && Color.blue(pixel) < 90) {
                    count++
                    box[0] = minOf(box[0], x)
                    box[1] = minOf(box[1], y)
                    box[2] = maxOf(box[2], x)
                    box[3] = maxOf(box[3], y)
                }
            }
        }
        return box to count
    }

    private fun assertWhiteCorners(image: Bitmap) {
        listOf(0 to 0, image.width - 1 to 0, 0 to image.height - 1, image.width - 1 to image.height - 1).forEach { (x, y) ->
            assertEquals("corner $x,$y", Color.WHITE, image.getPixel(x, y))
        }
    }

    @Test
    fun `a card held at a slant comes out straight, centred on white at 4 to 5`() {
        val studio = StudioComposer.compose(cutout(900, 900, 300f, 400f, degrees = 8f))!!

        assertEquals(0.8f, studio.width.toFloat() / studio.height, 0.01f)
        assertWhiteCorners(studio)

        val (box, count) = redBox(studio)
        val width = box[2] - box[0] + 1
        val height = box[3] - box[1] + 1
        // Turned back: as tall and wide as the card itself (still slanted it would be 353 x 438), and filled.
        assertEquals(300f, width.toFloat(), 4f)
        assertEquals(400f, height.toFloat(), 4f)
        assertTrue("fill ${count.toFloat() / (width * height)}", count >= 0.97f * width * height)
        // Centred, never enlarged.
        assertEquals(studio.width / 2f, (box[0] + box[2]) / 2f, 2f)
        assertEquals(studio.height / 2f, (box[1] + box[3]) / 2f, 2f)
    }

    @Test
    fun `a card shot at an angle comes out as a rectangle`() {
        val studio = StudioComposer.compose(cardFromBelow())!!

        val (box, count) = redBox(studio)
        val width = box[2] - box[0] + 1
        val height = box[3] - box[1] + 1
        // As wide as the card on average, as tall as its sides, and filling its box (turned only, it would fill 93%).
        assertEquals(322.5f, width.toFloat(), 4f)
        assertEquals(400.6f, height.toFloat(), 4f)
        assertTrue("fill ${count.toFloat() / (width * height)}", count >= 0.97f * width * height)
        assertWhiteCorners(studio)
    }

    @Test
    fun `a finger over an edge doesn't throw the card off`() {
        val photo = cardFromBelow().also {
            Canvas(it).apply {
                rotate(5f, 450f, 450f)
                drawRect(RectF(280f, 330f, 340f, 390f), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            }
        }

        val (box, count) = redBox(StudioComposer.compose(photo)!!)
        val width = box[2] - box[0] + 1
        val height = box[3] - box[1] + 1

        // Squared up all the same; the bite stays (turned only, the card would fill 92% of its box).
        assertEquals(322.5f, width.toFloat(), 4f)
        assertEquals(400.6f, height.toFloat(), 4f)
        assertTrue("fill ${count.toFloat() / (width * height)}", count >= 0.95f * width * height)
    }

    @Test
    fun `specks of the old background are dropped`() {
        val photo = cutout(900, 900, 300f, 400f, degrees = 0f).also {
            Canvas(it).drawCircle(60f, 60f, 5f, Paint().apply { color = Color.RED })
        }

        val (box, _) = redBox(StudioComposer.compose(photo)!!)

        assertEquals(300f, (box[2] - box[0] + 1).toFloat(), 4f)
        assertEquals(400f, (box[3] - box[1] + 1).toFloat(), 4f)
    }

    @Test
    fun `a side's line leaves out a bent corner, and a curve has none`() {
        // A left edge at u = 100 + 0.05 v, its bottom fifth bent inwards.
        val edge = (0 until 500).map { v -> StudioComposer.Point(v + 0.5, 100 + 0.05 * (v + 0.5) + if (v >= 400) 30.0 else 0.0) }
        val line = StudioComposer.edgeLine(edge, tolerance = 3.0)!!

        assertEquals(100.0, line.offset, 0.5)
        assertEquals(0.05, line.slope, 0.001)

        val arc = (0 until 500).map { v -> StudioComposer.Point(v + 0.5, sqrt(260.0 * 260.0 - (v - 250.0) * (v - 250.0))) }
        assertNull(StudioComposer.edgeLine(arc, tolerance = 3.0))
    }

    @Test
    fun `a shape that is no card or box keeps its angle`() {
        // A loose car seen from an angle: turning it would put its wheels off the ground.
        val studio = StudioComposer.compose(cutout(900, 900, 400f, 200f, degrees = 15f, oval = true))!!

        val (box, _) = redBox(studio)
        val ratio = (box[3] - box[1] + 1).toFloat() / (box[2] - box[0] + 1)
        // The slanted oval's box: 2 x 109.6 high, 2 x 194.9 wide (upright it would be 0.5).
        assertEquals(0.562f, ratio, 0.02f)
        assertWhiteCorners(studio)
    }

    @Test
    fun `a big subject is scaled down to the photo size`() {
        val studio = StudioComposer.compose(cutout(900, 900, 300f, 400f, degrees = 0f), maxEdge = 200)!!

        assertEquals(200, studio.height)
        assertEquals(160, studio.width)
    }

    @Test
    fun `nothing cut out, no studio photo`() {
        assertNull(StudioComposer.compose(createBitmap(400, 400)))
    }

    @Test
    fun `the tightest rectangle finds the tilt of a square too`() {
        // Moments can't tell a turned square from an upright one; the hull's tightest rectangle can.
        val angle = Math.toRadians(10.0)
        val square = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0).map { (x, y) ->
            StudioComposer.Point(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
        }

        val (tilt, area) = StudioComposer.tightestRectangle(StudioComposer.convexHull(square))

        assertEquals(10.0, tilt, 0.01)
        assertEquals(10_000.0, area, 0.5)
    }

    @Test
    fun `a card cut out by its corners is squared up without the background`() {
        // The card on a blue table; the corners placed in any order.
        val photo = createBitmap(900, 900).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.BLUE)
                drawBitmap(cardFromBelow(), 0f, 0f, null)
            }
        }
        val c = cardFromBelowCorners
        val shuffled = floatArrayOf(c[4], c[5], c[0], c[1], c[6], c[7], c[2], c[3])

        val cutout = CardCutout.from(photo, shuffled, rounded = false)!!
        val studio = cutout.compose()

        // Nothing on the card looks like the table behind it.
        assertFalse(cutout.holeFound)
        val (box, count) = redBox(studio)
        val width = box[2] - box[0] + 1
        val height = box[3] - box[1] + 1
        assertEquals(322.5f, width.toFloat(), 4f)
        assertEquals(400.6f, height.toFloat(), 4f)
        assertTrue("fill ${count.toFloat() / (width * height)}", count >= 0.97f * width * height)
        val blue = (0 until studio.height).sumOf { y ->
            (0 until studio.width).count { x -> studio.getPixel(x, y).let { Color.blue(it) > 200 && Color.red(it) < 90 } }
        }
        assertEquals(0, blue)
        assertEquals(0.8f, studio.width.toFloat() / studio.height, 0.01f)
        assertWhiteCorners(studio)
    }

    @Test
    fun `corners are put in order, and a dent or a speck is no card`() {
        val quad = StudioComposer.cardQuad(floatArrayOf(900f, 100f, 100f, 900f, 100f, 100f, 900f, 900f), 1000, 1000)!!
        assertEquals(listOf(100f, 100f, 900f, 100f, 900f, 900f, 100f, 900f), quad.toList())

        // One point inside the other three: a dent whichever way round.
        assertNull(StudioComposer.cardQuad(floatArrayOf(100f, 100f, 900f, 100f, 400f, 400f, 100f, 900f), 1000, 1000))
        assertNull(StudioComposer.cardQuad(floatArrayOf(100f, 100f, 120f, 100f, 120f, 120f, 100f, 120f), 1000, 1000))
    }

    @Test
    fun `the background remover's cut-out gives the card's corners to start from`() = runBlocking {
        val studio = StudioPhoto(env.photos) { _, _ -> cardFromBelow() }

        val corners = studio.guessCorners(createBitmap(900, 900))!!

        corners.forEachIndexed { i, value -> assertEquals("corner value $i", cardFromBelowCorners[i], value, 3f) }
        assertNull(StudioPhoto(env.photos) { _, _ -> throw StudioException(StudioPhoto.NOT_FOUND) }.guessCorners(createBitmap(900, 900)))
        assertNull(StudioPhoto(env.photos) { _, _ -> null }.guessCorners(createBitmap(900, 900)))
    }

    /**
     * An orange card (photo 200,150 to 700,800: the card's own pixels are the photo's less 200,150) held against a dark
     * room: the hang hole shows the room at the top in the middle, a stripe of navy runs across the top, and a dark
     * patch of the room shows lower down too.
     */
    private fun cardInRoom(): Pair<Bitmap, FloatArray> {
        val photo = createBitmap(900, 900).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(ROOM)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                drawRect(200f, 150f, 700f, 800f, paint.apply { color = Color.rgb(240, 140, 20) })
                drawRect(200f, 270f, 700f, 285f, paint.apply { color = Color.rgb(20, 30, 90) })
                drawOval(RectF(390f, 182f, 510f, 222f), paint.apply { color = ROOM })
                drawRect(600f, 550f, 640f, 590f, paint.apply { color = ROOM })
            }
        }
        return photo to floatArrayOf(200f, 150f, 700f, 150f, 700f, 800f, 200f, 800f)
    }

    @Test
    fun `a card's hang hole is erased by itself, its corners rounded, the rest left`() {
        val (photo, corners) = cardInRoom()

        val cutout = CardCutout.from(photo, corners, rounded = true)!!
        val image = cutout.image

        assertTrue(cutout.holeFound)
        assertEquals(500, image.width)
        assertEquals(650, image.height)
        assertEquals("the hole", 0, Color.alpha(image.getPixel(250, 52)))
        assertEquals("below the hole", 255, Color.alpha(image.getPixel(250, 85)))
        assertEquals("the navy stripe", 255, Color.alpha(image.getPixel(250, 127)))
        assertEquals("the patch lower down", 255, Color.alpha(image.getPixel(420, 420)))
        assertEquals("a rounded corner", 0, Color.alpha(image.getPixel(1, 1)))
        assertEquals("the middle", 255, Color.alpha(image.getPixel(250, 325)))
        assertFalse(cutout.canUndo)

        // Square corners for a box.
        assertEquals(255, Color.alpha(CardCutout.from(photo, corners, rounded = false)!!.image.getPixel(1, 1)))
    }

    @Test
    fun `background tapped away comes back with a tap or undo`() {
        val (photo, corners) = cardInRoom()
        val cutout = CardCutout.from(photo, corners, rounded = true)!!

        assertEquals(CardCutout.Tap.Erased, cutout.tap(420, 420))
        assertEquals(0, Color.alpha(cutout.image.getPixel(420, 420)))
        assertEquals("the card around it", 255, Color.alpha(cutout.image.getPixel(420, 450)))

        assertEquals(CardCutout.Tap.Restored, cutout.tap(250, 52))
        assertEquals(255, Color.alpha(cutout.image.getPixel(250, 52)))
        cutout.undo()
        assertEquals(0, Color.alpha(cutout.image.getPixel(250, 52)))
        cutout.undo()
        assertEquals(255, Color.alpha(cutout.image.getPixel(420, 420)))
        assertFalse(cutout.canUndo)

        // The card's own colour runs all over it: not something to erase.
        assertEquals(CardCutout.Tap.TooBig, cutout.tap(100, 400))
        assertEquals(255, Color.alpha(cutout.image.getPixel(100, 400)))
    }

    @Test
    fun `a card's studio photo is a new pending photo too`() = runBlocking {
        val original = env.pendingPhoto()
        val studio = StudioPhoto(env.photos) { _, _ -> throw AssertionError("The card goes by its corners") }
        val image = studio.load(original)
        val card = studio.cutCard(image, floatArrayOf(0f, 0f, image.width / 2f, 0f, image.width / 2f, image.height / 2f, 0f, image.height / 2f), rounded = true)

        val made = studio.makeCard(card)

        assertEquals(setOf(original, made), env.pendingFiles)
        val decoded = BitmapFactory.decodeFile(env.photos.file(made).path)
        assertEquals(0.8f, decoded.width.toFloat() / decoded.height, 0.01f)
        try {
            studio.cutCard(image, floatArrayOf(0f, 0f, 10f, 0f, 5f, 2f, 0f, 10f), rounded = true)
            fail("Expected a StudioException")
        } catch (e: StudioException) {
            assertEquals(StudioPhoto.NO_CARD, e.message)
        }
    }

    @Test
    fun `the studio photo is a new pending photo and the original stays`() = runBlocking {
        val original = env.pendingPhoto()
        val steps = mutableListOf<StudioStep>()
        val studio = StudioPhoto(env.photos) { image, onStep ->
            onStep(StudioStep.Cutting)
            cutout(image.width, image.height, image.width / 2f, image.height * 0.6f, degrees = 0f)
        }

        val made = studio.make(original, steps::add)

        assertNotEquals(original, made)
        assertEquals(setOf(original, made), env.pendingFiles)
        assertEquals(listOf(StudioStep.Cutting, StudioStep.Composing), steps)
        val decoded = BitmapFactory.decodeFile(env.photos.file(made).path)
        assertEquals(0.8f, decoded.width.toFloat() / decoded.height, 0.01f)
    }

    @Test
    fun `no diecast found says how to take a better photo`() = runBlocking {
        val original = env.pendingPhoto()
        val studio = StudioPhoto(env.photos) { _, _ -> null }

        try {
            studio.make(original)
            fail("Expected a StudioException")
        } catch (e: StudioException) {
            assertEquals(StudioPhoto.NOT_FOUND, e.message)
        }
        assertEquals(setOf(original), env.pendingFiles)
    }

    private companion object {
        val CARD_FROM_BELOW = arrayOf(300f to 250f, 600f to 250f, 622.5f to 650f, 277.5f to 650f)
        val ROOM = Color.rgb(40, 42, 45)
    }
}
