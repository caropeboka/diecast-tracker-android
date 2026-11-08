package io.github.caropeboka.diecasttracker.ui.garage

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
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
    fun `a big one stands behind the small ones, so the front edge still takes them all`() {
        val big = OnTier(0.267f, 0.12f, 45f)
        val small = listOf(20f, 35f, 50f, 65f).map { OnTier(0.075f, 0.034f, it) }
        // Along the front edge alone there's no room for all five.
        assertTrue(tierSpots(1, small + big).any { it == null })

        val spots = tierLayout(1, small + big, listOf(false, false, false, false, true), emptyList())
        assertTrue(spots.diecasts.all { it != null })
        val back = spots.diecasts.last()!!
        assertTrue(back.back)
        assertEquals(45f, back.facing)
        spots.diecasts.take(4).forEachIndexed { i, spot ->
            assertTrue(!spot!!.back)
            assertEquals(small[i].angle, spot.angle, 0.01f)
            assertTrue(spot.radius - small[i].depth / 2 > back.radius + big.depth / 2)
        }

        // A full back row sends what's left to the front edge, while it has room.
        val two = tierLayout(0, listOf(big, OnTier(0.2f, 0.09f, 30f)), listOf(true, true), emptyList())
        assertEquals(1, two.diecasts.count { it?.back == true })
        assertEquals(1, two.diecasts.count { it?.back == false })
    }

    @Test
    fun `cards stand on the black shelf too, at the back`() {
        val rows = listOf(
            row(1, Packaging.Carded, slot = ShelfSlotEntity(1, Shelf.Corner, 1, 450)),
            row(2, Packaging.Loose, "1:18", slot = ShelfSlotEntity(2, Shelf.Corner, 1, 300)),
            row(3, Packaging.Loose, slot = ShelfSlotEntity(3, Shelf.Corner, 1, 600)),
        )
        val layout = layOut(rows, rows.associate { it.id to item(it) })
        assertTrue(layout.overflow.isEmpty())
        assertEquals(setOf(1L, 2L, 3L), layout.placed.map { it.item.id }.toSet())
        // Facing down the diagonal, the card tipped back; the small car faces out from where it was put.
        val card = layout.placed.first { it.item.id == 1L }
        assertEquals(45f, card.rest.yaw, 0.01f)
        assertTrue(card.rest.pitch < 0)
        assertEquals(30f, layout.placed.first { it.item.id == 3L }.rest.yaw, 0.01f)
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

    @Test
    fun `a tap on the desk finds the point under it, and on a lift the level it comes in at`() {
        val eye = Focus.Desk.eye
        assertEquals(350 to 250, deskAt(ray(eye, onDesk(0.35f, 0.25f))))
        assertEquals(10 to 390, deskAt(ray(eye, onDesk(0.01f, 0.39f))))
        assertNull(deskAt(ray(eye, onDesk(-0.03f, 0.2f))))
        assertNull(deskAt(ray(eye, onDesk(0.35f, DESK_D + 0.02f))))

        val (left, right) = LIFT_CENTRES
        assertEquals(0, liftAt(ray(eye, onDesk(left.x, left.y, 0.01f))))
        assertEquals(1, liftAt(ray(eye, onDesk(left.x, left.y, LIFT_DECK + 0.01f))))
        assertEquals(2, liftAt(ray(eye, onDesk(right.x, right.y, 0.01f))))
        assertEquals(3, liftAt(ray(eye, onDesk(right.x, right.y, LIFT_DECK + 0.01f))))
        assertNull(liftAt(ray(eye, onDesk(0.35f, 0.25f))))
    }

    @Test
    fun `on the desk a thing stays where it was put, or goes to the free point nearest it`() {
        assertEquals(Float2(0.35f, 0.2f), deskSpot(0.35f, 0.2f, 0.1f, 0.05f, emptyList()))
        // Past the edge it stays on the desk.
        val corner = deskSpot(-0.2f, 0.9f, 0.1f, 0.05f, emptyList())!!
        assertEquals(0.053f, corner.x, 1e-4f)
        assertEquals(DESK_D - 0.028f, corner.y, 1e-4f)
        assertNull(deskSpot(0.3f, 0.2f, DESK_W, 0.05f, emptyList()))

        val there = Footprint(0.35f, 0.2f, 0.05f, 0.025f)
        val next = deskSpot(0.36f, 0.2f, 0.1f, 0.05f, listOf(there))!!
        assertTrue(!there.overlaps(next.x, next.y, 0.05f, 0.025f))
        assertTrue(abs(next.x - 0.36f) + abs(next.y - 0.2f) < 0.07f)

        // In rows from the back, left to right: beside the left lift.
        val first = deskSpot(0.6f, 0.3f, 0.1f, 0.05f, LIFT_FOOTPRINTS, rows = true)!!
        assertEquals(0.028f, first.y, 1e-4f)
        assertTrue(first.x in 0.19f..0.2f)
    }

    @Test
    fun `the desk makes room for what's put there now, and a lift's level takes one car`() {
        val rows = listOf(
            row(1, Packaging.Boxed, "1:43", slot = ShelfSlotEntity(1, Shelf.Desk, 350, 200)),
            row(2, Packaging.Loose, slot = ShelfSlotEntity(2, Shelf.Desk, 350, 200)),
            row(3, Packaging.Loose, slot = ShelfSlotEntity(3, Shelf.Lifts, 1)),
            row(4, Packaging.Loose, slot = ShelfSlotEntity(4, Shelf.Lifts, 1)),
            row(5, Packaging.Loose, "1:43", slot = ShelfSlotEntity(5, Shelf.Lifts, 0)),
            row(6, Packaging.Carded, slot = ShelfSlotEntity(6, Shelf.Desk, 100, 300)),
            row(7, Packaging.Loose, slot = ShelfSlotEntity(7, Shelf.Desk, DOG_HOME.first, DOG_HOME.second)),
        )
        val items = rows.associate { it.id to item(it) }
        val layout = layOut(rows, items)
        assertEquals(setOf(4L, 5L, 6L), layout.overflow)
        assertEquals(350 to 200, layout.desk[1])
        assertNotEquals(350 to 200, layout.desk[2])
        // The dog stands where it was; the car put on it stands beside it.
        assertEquals(DOG_HOME, layout.dog!!.x to layout.dog.depth)
        assertNotEquals(DOG_HOME, layout.desk[7])
        val onLift = layout.placed.first { it.item.id == 3L }.rest.position
        assertTrue(onLift.y > DESK_TOP + LIFT_DECK)

        // Put down just now, the box is the one that moves aside.
        val settled = layOut(rows, items, settling = setOf(1))
        assertEquals(350 to 200, settled.desk[2])
        assertNotEquals(350 to 200, settled.desk[1])
        // And the dog, put on the box, stands beside it.
        val dog = layOut(rows, items, dog = 350 to 200, dogSettling = true).dog
        assertNotNull(dog)
        assertNotEquals(350 to 200, dog!!.x to dog.depth)
    }

    @Test
    fun `with the case full, small loose cars go onto the lifts, and big boxes onto the desk when the tiers are full`() {
        val inCase = (1L..60L).map { row(it, Packaging.Loose, slot = ShelfSlotEntity(it, Shelf.Case, (it - 1).toInt())) }
        val cars = (101L..103L).map { row(it, Packaging.Loose) }
        val boxes = (201L..220L).map { row(it, Packaging.Boxed, "1:18") }
        val rows = inCase + cars + boxes
        val items = rows.associate { it.id to item(it) }
        val moves = arrange(rows, cars + boxes, items)

        assertEquals(ShelfSlotEntity(101, Shelf.Lifts, 0), moves[101])
        assertEquals(ShelfSlotEntity(102, Shelf.Lifts, 2), moves[102])
        assertEquals(ShelfSlotEntity(103, Shelf.Lifts, 1), moves[103])
        val onDesk = moves.values.filter { it.shelf == Shelf.Desk }
        assertTrue(onDesk.isNotEmpty())
        assertTrue(moves.values.any { it.shelf == Shelf.Corner })
        // Each where it was stored, none on another.
        val after = layOut(rows.map { row -> moves[row.id]?.let(row::placed) ?: row }, items)
        assertTrue(moves.keys.none { it in after.overflow })
        onDesk.forEach { assertEquals(it.spot to it.position, after.desk[it.diecastId]) }
        assertTrue(onDesk.first().position <= onDesk.last().position)
    }

    @Test
    fun `a lane that takes more cards hangs them closer, still under the rails' top`() {
        val rows = (1L..16L).map { row(it, Packaging.Carded, slot = ShelfSlotEntity(it, Shelf.Boards, 6, it.toInt())) }
        val items = rows.associate { it.id to item(it) }

        assertEquals(4, layOut(rows, items).overflow.size)
        val roomy = layOut(rows, items, laneCards = 16)
        assertTrue(roomy.overflow.isEmpty())
        val heights = roomy.placed.sortedBy { it.item.id }.map { it.rest.position.y }
        assertTrue(heights.zipWithNext().all { (a, b) -> b > a })
        // The top card's top: the rails' top at most.
        val top = onBoard(1, 0f, RAIL_TOP, FACE_Z).y
        assertTrue(heights.last() + 0.165f / 2 <= top + 1e-4f)

        assertEquals(laneStep(4), laneStep(ShelfLayout.LANE_CARDS), 1e-6f)
        assertTrue(laneStep(16) < laneStep(ShelfLayout.LANE_CARDS))
        // Fewer than are there: the rest wait in the tray again, the ones put there first stay.
        assertEquals((11L..16L).toSet(), layOut(rows, items, laneCards = 10).overflow)
    }

    @Test
    fun `a flipped loose car is its picture as in a mirror`() {
        val red = android.graphics.Color.RED
        val blue = android.graphics.Color.BLUE
        val picture = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until 2) {
                setPixel(0, y, red)
                setPixel(1, y, android.graphics.Color.TRANSPARENT)
                setPixel(2, y, blue)
            }
        }
        val flipped = mirrored(picture)
        assertEquals(3 to 2, flipped.width to flipped.height)
        assertEquals(blue, flipped.getPixel(0, 1))
        assertEquals(android.graphics.Color.TRANSPARENT, flipped.getPixel(1, 0))
        assertEquals(red, flipped.getPixel(2, 0))
    }
}
