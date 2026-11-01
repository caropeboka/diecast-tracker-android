package io.github.caropeboka.diecasttracker.ui.garage

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GarageGeometryTest {
    /** From [eye] through [point]. */
    private fun ray(eye: Position, point: Position) = Ray(Float3(eye.x, eye.y, eye.z), Float3(point.x - eye.x, point.y - eye.y, point.z - eye.z))

    private fun row(id: Long, packaging: Packaging, scale: String = "1:64", slot: ShelfSlotEntity? = null) =
        ShelfRow(id, "Diecast $id", packaging, scale, "cover-$id.jpg", null, null, null).placed(slot)

    private fun item(row: ShelfRow) = ShelfItem(row.id, row.packaging, row.scale, Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888), Color.White)

    @Test
    fun `a tap finds the compartment, the lane and the tier under it`() {
        val cell = 3 * CASE_COLUMNS + 4
        assertEquals(cell, cellAt(ray(Focus.Case.eye, inCase(cellX(4), cellFloor(3) + 0.02f, CASE_DEPTH))))
        assertNull(cellAt(ray(Focus.Case.eye, inCase(cellX(0) - 0.06f, cellFloor(3) + 0.02f, CASE_DEPTH))))

        assertEquals(COLUMNS + 2, laneAt(ray(Focus.Board.eye, onBoard(1, columnX(2) + 0.03f, 0.3f, FACE_Z))))
        assertEquals(0, laneAt(ray(Focus.Board.eye, onBoard(0, columnX(0), 0.05f, FACE_Z))))
        assertNull(laneAt(ray(Focus.Board.eye, onBoard(0, columnX(0), RAIL_TOP + 0.05f, FACE_Z))))

        fun onTop(tier: Int, radius: Float, degrees: Float): Position {
            val angle = Math.toRadians(degrees.toDouble())
            return onShelf(radius * cos(angle).toFloat(), TIER_TOPS[tier], radius * sin(angle).toFloat())
        }
        // The middle tier shows under the top one from the corner of the room.
        val (middle, angle) = tierAt(ray(Focus.Shelf.eye, onTop(1, 0.3f, 45f)))!!
        assertEquals(1, middle)
        assertEquals(45f, angle, 0.5f)
        assertEquals(2, tierAt(ray(Focus.Shelf.eye, onTop(2, 0.4f, 30f)))?.first)
    }

    @Test
    fun `along a tier each stays where it was put unless that's taken or past the posts`() {
        val free = tierSpots(1, listOf(OnTier(0.1f, 0.06f, 60f), OnTier(0.1f, 0.06f, 30f)))
        assertEquals(listOf(60f, 30f), free.map { it!!.angle })

        // On top of each other: pushed apart, still between the end posts.
        val crowded = tierSpots(1, listOf(OnTier(0.1f, 0.06f, 45f), OnTier(0.1f, 0.06f, 45f), OnTier(0.1f, 0.06f, 90f)))
        val angles = crowded.map { it!!.angle }
        assertTrue(angles.all { it in 13f..77f })
        assertTrue(angles.sorted().zipWithNext().all { (a, b) -> b - a > 10f })

        // A full tier leaves out the one nearest the left wall.
        val full = tierSpots(0, List(12) { OnTier(0.12f, 0.08f, it * 5f + 10f) })
        assertNull(full.last())
        assertTrue(full.first() != null)
    }

    @Test
    fun `cards stack in the order they were put, and what no longer fits goes back to the tray`() {
        val rows = listOf(
            row(1, Packaging.Carded, slot = ShelfSlotEntity(1, Shelf.Boards, 2, 5)),
            row(2, Packaging.Carded, slot = ShelfSlotEntity(2, Shelf.Boards, 2, 2)),
            row(3, Packaging.Carded, slot = ShelfSlotEntity(3, Shelf.Case, 0)),
            row(4, Packaging.Loose, slot = ShelfSlotEntity(4, Shelf.Case, 7)),
            row(5, Packaging.Boxed, slot = ShelfSlotEntity(5, Shelf.Case, 7)),
        )
        val layout = layOut(rows, rows.associate { it.id to item(it) })

        val y = layout.placed.associate { it.item.id to it.rest.position.y }
        assertTrue(y.getValue(2) < y.getValue(1))
        assertEquals(setOf(3L, 5L), layout.overflow)
    }

    @Test
    fun `a pinch zooms towards the fingers, a drag carries the view along, and the walls stop the camera`() {
        fun assertSame(expected: Position, actual: Position) {
            assertEquals(expected.x, actual.x, 1e-3f)
            assertEquals(expected.y, actual.y, 1e-3f)
            assertEquals(expected.z, actual.z, 1e-3f)
        }
        fun distance(sight: Sight) = sight.eye.let { e ->
            val t = sight.target
            sqrt((e.x - t.x) * (e.x - t.x) + (e.y - t.y) * (e.y - t.y) + (e.z - t.z) * (e.z - t.z))
        }
        val camera = ShelfCamera(Focus.Board.sight) { 45f }.apply { setViewport(1080, 1800) }

        // Fingers spread apart over the right-hand board's lower lanes: that spot stays under them.
        val under = camera.pointAt(850, 800)
        camera.scrollBegin(850, 800, 200f)
        camera.scrollUpdate(850, 800, 200f, 400f)
        assertSame(under, camera.pointAt(850, 800))
        assertEquals(distance(Focus.Board.sight) / 2, distance(camera.sight), 1e-3f)

        val before = camera.pointAt(500, 900)
        camera.grabBegin(500, 900, true)
        camera.grabUpdate(620, 840)
        assertSame(before, camera.pointAt(620, 840))

        repeat(40) { camera.scrollUpdate(850, 800, 100f, 400f) }
        assertEquals(0.15f, distance(camera.sight), 1e-3f)
        camera.grabBegin(0, 0, false)
        camera.grabUpdate(-20_000, -20_000)
        val eye = camera.sight.eye
        assertTrue(eye.z > WALL_Z && eye.x > LEFT_WALL_X && eye.y > 0)
    }

    @Test
    fun `a point ahead of the eye under a pixel shows at that pixel, and nothing behind the eye shows`() {
        val sight = Focus.Shelf.sight
        val ahead = sight.ahead(140f, 210f, 1080f, 1800f, 45f, 0.3f)
        val (x, y) = sight.onScreen(ahead, 1080f, 1800f, 45f)!!
        assertEquals(140f, x, 0.5f)
        assertEquals(210f, y, 0.5f)
        // The middle of the screen is what the camera looks at.
        val (cx, cy) = sight.onScreen(sight.target, 1080f, 1800f, 45f)!!
        assertEquals(540f, cx, 0.5f)
        assertEquals(900f, cy, 0.5f)
        val behind = Position(2 * sight.eye.x - sight.target.x, 2 * sight.eye.y - sight.target.y, 2 * sight.eye.z - sight.target.z)
        assertNull(sight.onScreen(behind, 1080f, 1800f, 45f))
    }

    @Test
    fun `everything in the tray goes where the shelves used to put it`() {
        val rows = listOf(
            row(1, Packaging.Carded),
            row(2, Packaging.Carded),
            row(3, Packaging.Loose),
            row(4, Packaging.Boxed),
            row(5, Packaging.Boxed, "1:18"),
            row(6, Packaging.Carded, slot = ShelfSlotEntity(6, Shelf.Boards, 0, 0)),
        )
        val moves = arrange(rows, rows.take(5), rows.associate { it.id to item(it) })

        assertEquals(ShelfSlotEntity(1, Shelf.Boards, 1, 0), moves[1])
        assertEquals(ShelfSlotEntity(2, Shelf.Boards, 2, 0), moves[2])
        assertEquals(ShelfSlotEntity(3, Shelf.Case, 0), moves[3])
        assertEquals(ShelfSlotEntity(4, Shelf.Case, (CASE_ROWS - 1) * CASE_COLUMNS), moves[4])
        val big = moves.getValue(5)
        assertEquals(Shelf.Corner to FILL_ORDER.first(), big.shelf to big.spot)
        assertTrue(big.position in 130..770)
    }
}
