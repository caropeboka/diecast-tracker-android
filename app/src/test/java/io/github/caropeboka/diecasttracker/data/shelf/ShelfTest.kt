package io.github.caropeboka.diecasttracker.data.shelf

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class ShelfTest {
    private var clock = 1_000L
    private val env = TestEnvironment { clock }
    private val shelves = ShelfRepository(env.db, env.texts) { clock }

    @After
    fun tearDown() = env.close()

    private fun card(id: Long, slot: ShelfSlotEntity? = null) = Shelved(id, Packaging.Carded, "1:64", slot)

    private fun loose(id: Long, slot: ShelfSlotEntity? = null, scale: String = "1:64") = Shelved(id, Packaging.Loose, scale, slot)

    private fun box(id: Long, slot: ShelfSlotEntity? = null, scale: String = "1:64") = Shelved(id, Packaging.Boxed, scale, slot)

    private fun done(move: ShelfMove) = (move as ShelfMove.Done).moves

    @Test
    fun `cards hang on the boards, compartments take small cars and boxes, the black shelf any but cards`() {
        assertTrue(ShelfLayout.fits(Shelf.Boards, Packaging.Carded, null))
        assertFalse(ShelfLayout.fits(Shelf.Boards, Packaging.Boxed, "1:64"))
        assertTrue(ShelfLayout.fits(Shelf.Case, Packaging.Loose, "1:64"))
        assertTrue(ShelfLayout.fits(Shelf.Case, Packaging.Boxed, "1:87"))
        assertFalse(ShelfLayout.fits(Shelf.Case, Packaging.Boxed, "1:43"))
        assertFalse(ShelfLayout.fits(Shelf.Case, Packaging.Carded, "1:64"))
        assertTrue(ShelfLayout.fits(Shelf.Corner, Packaging.Boxed, "1:18"))
        assertTrue(ShelfLayout.fits(Shelf.Corner, Packaging.Loose, "1:24"))
        assertFalse(ShelfLayout.fits(Shelf.Corner, Packaging.Carded, "1:64"))
    }

    @Test
    fun `every place has a name for its storage location`() {
        assertEquals("Papan 1 · jalur 1", ShelfLayout.label(env.texts, Shelf.Boards, 0))
        assertEquals("Papan 2 · jalur 3", ShelfLayout.label(env.texts, Shelf.Boards, 6))
        assertEquals("Laci hitam · susun bawah", ShelfLayout.label(env.texts, Shelf.Corner, 0))
        assertEquals("Laci hitam · susun atas", ShelfLayout.label(env.texts, Shelf.Corner, 2))
        assertEquals("Rak putih · baris 1 kolom 1", ShelfLayout.label(env.texts, Shelf.Case, 0))
        assertEquals("Rak putih · baris 3 kolom 2", ShelfLayout.label(env.texts, Shelf.Case, 13))
        assertEquals("Rak putih · baris 10 kolom 6", ShelfLayout.label(env.texts, Shelf.Case, 59))
    }

    @Test
    fun `a card slides down its lane onto the cards already there, up to a full lane`() {
        val lane = (1L..3L).map { card(it, ShelfSlotEntity(it, Shelf.Boards, 2, (it * 2).toInt())) }

        assertEquals(mapOf(9L to ShelfSlotEntity(9, Shelf.Boards, 2, 7)), done(ShelfPlanner.place(lane + card(9), 9, Shelf.Boards, 2)))
        assertEquals(mapOf(9L to ShelfSlotEntity(9, Shelf.Boards, 0, 0)), done(ShelfPlanner.place(lane + card(9), 9, Shelf.Boards, 0)))
        // Its own card doesn't count: from the bottom of the lane to the top.
        assertEquals(mapOf(1L to ShelfSlotEntity(1, Shelf.Boards, 2, 7)), done(ShelfPlanner.place(lane, 1, Shelf.Boards, 2)))

        val full = (1L..ShelfLayout.LANE_CARDS).map { card(it, ShelfSlotEntity(it, Shelf.Boards, 4, it.toInt())) }
        assertEquals(ShelfMove.Full, ShelfPlanner.place(full + card(99), 99, Shelf.Boards, 4))
    }

    @Test
    fun `nothing goes where it doesn't fit`() {
        val all = listOf(card(1), box(2, scale = "1:43"), loose(3))
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.place(all, 1, Shelf.Case, 0))
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.place(all, 2, Shelf.Case, 0))
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.place(all, 3, Shelf.Boards, 0))
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.place(all, 3, Shelf.Case, ShelfLayout.CASE_COLUMNS * ShelfLayout.CASE_ROWS))
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.place(all, 42, Shelf.Case, 0))
    }

    @Test
    fun `anywhere along a tier, and any compartment, without filling from the top`() {
        val all = listOf(box(1, scale = "1:24"), loose(2))
        assertEquals(mapOf(1L to ShelfSlotEntity(1, Shelf.Corner, 2, 455)), done(ShelfPlanner.place(all, 1, Shelf.Corner, 2, 455)))
        assertEquals(mapOf(2L to ShelfSlotEntity(2, Shelf.Case, 57)), done(ShelfPlanner.place(all, 2, Shelf.Case, 57)))
    }

    @Test
    fun `a taken compartment swaps, or sends what's in it to the tray when it can't go back`() {
        val inCase = loose(1, ShelfSlotEntity(1, Shelf.Case, 0))
        val other = box(2, ShelfSlotEntity(2, Shelf.Case, 7))
        val fromShelf = box(3, ShelfSlotEntity(3, Shelf.Corner, 1, 300))
        val fromTray = loose(4)

        assertEquals(
            mapOf(2L to ShelfSlotEntity(2, Shelf.Case, 0), 1L to ShelfSlotEntity(1, Shelf.Case, 7)),
            done(ShelfPlanner.place(listOf(inCase, other), 2, Shelf.Case, 0)),
        )
        // A loose car can stand on the black shelf too, where the box was.
        assertEquals(
            mapOf(3L to ShelfSlotEntity(3, Shelf.Case, 0), 1L to ShelfSlotEntity(1, Shelf.Corner, 1, 300)),
            done(ShelfPlanner.place(listOf(inCase, fromShelf), 3, Shelf.Case, 0)),
        )
        assertEquals(mapOf(4L to ShelfSlotEntity(4, Shelf.Case, 0), 1L to null), done(ShelfPlanner.place(listOf(inCase, fromTray), 4, Shelf.Case, 0)))
    }

    @Test
    fun `swapping trades places, and a card can't take a box's`() {
        val low = card(1, ShelfSlotEntity(1, Shelf.Boards, 3, 0))
        val high = card(2, ShelfSlotEntity(2, Shelf.Boards, 6, 4))
        val boxed = box(3, ShelfSlotEntity(3, Shelf.Corner, 0, 500))

        assertEquals(
            mapOf(1L to ShelfSlotEntity(1, Shelf.Boards, 6, 4), 2L to ShelfSlotEntity(2, Shelf.Boards, 3, 0)),
            done(ShelfPlanner.swap(listOf(low, high), 1, 2)),
        )
        assertEquals(ShelfMove.DoesNotFit, ShelfPlanner.swap(listOf(low, boxed), 1, 3))
        assertEquals(mapOf(9L to ShelfSlotEntity(9, Shelf.Boards, 3, 0), 1L to null), done(ShelfPlanner.swap(listOf(low, card(9)), 9, 1)))
    }

    @Test
    fun `placing fills in the storage location, and the tray clears it unless it was changed by hand`() = runBlocking {
        val datsun = env.repository.save(DiecastEntity(name = "Datsun 510", packaging = Packaging.Loose, storageLocation = "Lemari"))
        val skyline = env.repository.save(DiecastEntity(name = "Skyline", packaging = Packaging.Loose))
        clock = 2_000L

        shelves.apply(mapOf(datsun to ShelfSlotEntity(datsun, Shelf.Case, 13), skyline to ShelfSlotEntity(skyline, Shelf.Case, 0)))
        assertEquals("Rak putih · baris 3 kolom 2", env.db.diecasts().get(datsun)!!.storageLocation)
        assertEquals(2_000L, env.db.diecasts().get(datsun)!!.updatedAt)
        assertEquals(ShelfSlotEntity(datsun, Shelf.Case, 13), env.db.diecasts().observeShelf().first().first { it.id == datsun }.slot)

        shelves.release(datsun)
        assertNull(env.db.diecasts().get(datsun)!!.storageLocation)
        assertNull(env.db.shelves().get(datsun))

        env.repository.save(env.db.diecasts().get(skyline)!!.copy(storageLocation = "Kotak sepatu"))
        shelves.release(skyline)
        assertEquals("Kotak sepatu", env.db.diecasts().get(skyline)!!.storageLocation)
    }

    @Test
    fun `sold, it leaves the shelves, and only owned diecasts are shown`() = runBlocking {
        val datsun = env.repository.save(DiecastEntity(name = "Datsun 510"))
        env.repository.save(DiecastEntity(name = "Wanted", status = DiecastStatus.Wishlist))
        shelves.apply(mapOf(datsun to ShelfSlotEntity(datsun, Shelf.Boards, 1)))
        assertEquals(listOf("Datsun 510"), env.db.diecasts().observeShelf().first().map { it.name })

        env.repository.save(env.db.diecasts().get(datsun)!!.copy(status = DiecastStatus.Sold))

        assertNull(env.db.shelves().get(datsun))
        assertNull(env.db.diecasts().get(datsun)!!.storageLocation)
        assertTrue(env.db.diecasts().observeShelf().first().isEmpty())
    }
}
