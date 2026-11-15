package io.github.caropeboka.diecasttracker.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoTurnTest {
    private val env = TestEnvironment()

    @After
    fun tearDown() = env.close()

    /** 200 × 100, white with a red block in the top left corner: easy to follow through a turn. */
    private fun marked(): String {
        val image = createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (x in 0 until 40) for (y in 0 until 40) image[x, y] = Color.RED
        return env.photos.addBitmap(image)
    }

    private fun isRed(color: Int) = Color.red(color) > 200 && Color.green(color) < 80 && Color.blue(color) < 80

    @Test
    fun `a quarter turn to the right stands the photo up, the top left corner going to the top right`() {
        val photo = marked()
        val turned = env.photos.turned(photo, quarters = 1, mirror = false)
        assertNotEquals(photo, turned)
        assertTrue(env.photos.isPending(turned))

        val image = env.photos.bitmap(turned)
        assertEquals(100, image.width)
        assertEquals(200, image.height)
        assertTrue(isRed(image[image.width - 10, 10]))
        assertTrue(!isRed(image[10, 10]))
        // The photo itself stays as it was.
        assertEquals(200, env.photos.bitmap(photo).width)
    }

    @Test
    fun `a flip mirrors the photo left to right and keeps its size`() {
        val image = env.photos.bitmap(env.photos.turned(marked(), quarters = 0, mirror = true))
        assertEquals(200, image.width)
        assertEquals(100, image.height)
        assertTrue(isRed(image[image.width - 10, 10]))
        assertTrue(!isRed(image[10, 10]))
    }
}
