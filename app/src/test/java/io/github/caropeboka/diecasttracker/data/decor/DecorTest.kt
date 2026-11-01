package io.github.caropeboka.diecasttracker.data.decor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.backup.BackupExporter
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.backup.DecorBackup
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.studio.StudioComposer
import io.github.caropeboka.diecasttracker.ui.garage.DecorItem
import io.github.caropeboka.diecasttracker.ui.garage.ShelfItem
import io.github.caropeboka.diecasttracker.ui.garage.heightMm
import io.github.caropeboka.diecasttracker.ui.garage.layOut
import io.github.caropeboka.diecasttracker.ui.garage.placed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DecorTest {
    private val env = TestEnvironment()
    private val other = TestEnvironment()

    @After
    fun tearDown() {
        env.close()
        other.close()
    }

    /** A dusty green figure on a transparent background: a calm surface, a hard edge to a red half, specks of dust. */
    private fun figure(): Bitmap {
        val size = 60
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            when {
                x < 10 || x >= 50 || y < 10 || y >= 50 -> Color.TRANSPARENT
                x >= 30 -> Color.rgb(170, 90, 80)
                else -> Color.rgb(120, 140, 100)
            }
        }
        // Dust: a light speck and a dark one on the green.
        for ((x, y) in listOf(20 to 20, 21 to 20, 20 to 21, 21 to 21)) pixels[y * size + x] = Color.rgb(225, 225, 220)
        pixels[40 * size + 18] = Color.rgb(30, 30, 30)
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun distance(a: Int, b: Int) =
        maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    @Test
    fun `cleaning fills in the specks, keeps the edges and the see-through background`() {
        val cleaned = Dusting.clean(figure())

        val surface = cleaned.getPixel(15, 30)
        assertTrue(distance(cleaned.getPixel(20, 20), surface) < 12)
        assertTrue(distance(cleaned.getPixel(18, 40), surface) < 12)
        // Either side of the edge stays its own colour.
        assertTrue(distance(cleaned.getPixel(29, 30), surface) < 12)
        assertTrue(distance(cleaned.getPixel(30, 30), cleaned.getPixel(40, 30)) < 12)
        assertTrue(distance(cleaned.getPixel(30, 30), surface) > 40)
        assertEquals(0, Color.alpha(cleaned.getPixel(5, 5)))
        // The veil lifted: more contrast between the two colours than before.
        val before = figure()
        assertTrue(distance(cleaned.getPixel(15, 30), cleaned.getPixel(40, 30)) > distance(before.getPixel(15, 30), before.getPixel(40, 30)))
    }

    @Test
    fun `the cut-out is cropped to the subject and stays see-through round it`() {
        val isolated = StudioComposer.isolate(figure())!!
        assertEquals(40, isolated.width)
        assertEquals(40, isolated.height)
        assertEquals(255, Color.alpha(isolated.getPixel(0, 0)))
        assertNull(StudioComposer.isolate(Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)))
    }

    @Test
    fun `a height is typed in centimetres, with a comma or a point`() {
        assertEquals(115, heightMm("11,5"))
        assertEquals(200, heightMm("20"))
        assertEquals(85, heightMm("8.5"))
        assertNull(heightMm(""))
        assertNull(heightMm("0"))
        assertNull(heightMm("90"))
    }

    @Test
    fun `decorations and diecasts on the same tier make room for each other`() {
        val image = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val rabbit = DecorItem(1, "Kelinci", image, heightMm = 200, tier = 2, position = 150)
        val row = ShelfRow(7, "Box", Packaging.Boxed, "1:64", "cover.jpg", null, null, null).placed(ShelfSlotEntity(7, Shelf.Corner, 2, 150))
        val items = mapOf(7L to ShelfItem(7, Packaging.Boxed, "1:64", Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888), ComposeColor.White))

        val layout = layOut(listOf(row), items, listOf(rabbit))

        val decor = layout.decor.single()
        assertEquals(0.2f, decor.item.size.y, 1e-4f)
        assertEquals(0.1f, decor.item.size.x, 1e-4f)
        val gap = abs(layout.angles.getValue(7) - layout.decorAngles.getValue(1))
        assertTrue("pushed apart: $gap°", gap > 10f)
        assertTrue(layout.overflow.isEmpty())
        // The tier's top plus half its height: it stands on it.
        assertEquals(0.78f + 0.1f, decor.centre.y, 1e-4f)
    }

    @Test
    fun `decorations come back from a phone backup, and replacing swaps them`() = runBlocking {
        val store = DecorStore(env.root)
        val decorations = DecorRepository(env.db, store) { 5L }
        env.repository.save(DiecastEntity(name = "Datsun 510"), emptyList(), emptyList())
        decorations.add("Figur Risky Ridho", figure(), heightMm = 110, tier = 2, position = 800)

        val zip = File(env.root, "backup.zip")
        val exporter = BackupExporter(env.context, env.db, env.photos, CoroutineScope(Dispatchers.Unconfined), root = env.root, clock = { 9L })
        zip.outputStream().use { exporter.writeBackup(it) }
        ZipFile(zip).use { file ->
            val manifest = DecorBackup.decode(file.getInputStream(file.getEntry(DecorBackup.ENTRY)).bufferedReader().readText())!!
            val item = manifest.decorations.single()
            assertEquals("Figur Risky Ridho", item.name)
            assertNotNull(file.getEntry(item.photo))
        }

        // The other phone already has a decoration: "replace" swaps it for the backup's.
        val otherStore = DecorStore(other.root)
        val old = DecorRepository(other.db, otherStore)
        old.add("Lama", figure(), heightMm = 50, tier = 0, position = 300)
        val oldPhoto = other.db.decorations().all().single().photo
        BackupImporter(other.context, other.db, other.repository, other.photos, CoroutineScope(Dispatchers.Unconfined), root = other.root)
            .import(zip, replace = true)

        val restored = other.db.decorations().observe().first().single()
        assertEquals("Figur Risky Ridho", restored.name)
        assertEquals(110, restored.heightMm)
        assertEquals(2 to 800, restored.tier to restored.position)
        assertTrue(otherStore.file(restored.photo).isFile)
        assertTrue(!otherStore.file(oldPhoto).exists())
        assertEquals(60, otherStore.bitmap(restored.photo).width)
    }
}
