package io.github.caropeboka.diecasttracker.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
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
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiecastRepositoryTest {
    private var clock = 1_000L
    private val env = TestEnvironment { clock }
    private val repository = env.repository

    @After
    fun tearDown() = env.close()

    private fun diecast(name: String, change: (DiecastEntity) -> DiecastEntity = { it }) = change(DiecastEntity(name = name))

    private fun list(filter: CollectionFilter = CollectionFilter()) =
        runBlocking { repository.observeCollection(filter).first() }.map { it.name }

    @Test
    fun `saving keeps photos in order, moves them out of pending and dedupes tags`() = runBlocking {
        val front = env.pendingPhoto()
        val back = env.pendingPhoto()

        val id = repository.save(diecast("'71 Datsun 510"), listOf(back, front), listOf("JDM", "jdm", " Favorit "))

        val details = repository.details(id)!!
        assertEquals(listOf(back, front), details.orderedPhotos.map { it.fileName })
        assertEquals(setOf(back, front), env.savedFiles)
        assertTrue(env.pendingFiles.isEmpty())
        assertEquals(listOf("Favorit", "JDM"), details.orderedTags.map { it.name })
    }

    @Test
    fun `updating drops removed photos and tags nobody uses`() = runBlocking {
        val keep = env.pendingPhoto()
        val drop = env.pendingPhoto()
        val id = repository.save(diecast("Datsun"), listOf(keep, drop), listOf("Lama"))

        repository.save(repository.details(id)!!.diecast, listOf(keep), listOf("Baru"))

        assertEquals(listOf(keep), repository.details(id)!!.orderedPhotos.map { it.fileName })
        assertEquals(setOf(keep), env.savedFiles)
        assertEquals(listOf("Baru"), repository.observeTagNames().first())
    }

    @Test
    fun `sightings are added with a photo, listed newest first and removed with it`() = runBlocking {
        val id = repository.save(diecast("RX-7") { it.copy(status = DiecastStatus.Wishlist) })
        val photo = env.pendingPhoto()

        val first = repository.addSighting(
            SightingEntity(diecastId = id, place = "Toys Kingdom GI", seenOn = LocalDate.of(2026, 9, 1), price = 45_000, photoFile = photo),
        )
        repository.addSighting(SightingEntity(diecastId = id, place = "Pasar Gembrong", seenOn = LocalDate.of(2026, 10, 1)))

        assertEquals(listOf("Pasar Gembrong", "Toys Kingdom GI"), repository.observeSightings(id).first().map { it.place })
        assertEquals(setOf(photo), env.savedFiles)
        assertTrue(env.pendingFiles.isEmpty())

        repository.deleteSighting(first)

        assertEquals(listOf("Pasar Gembrong"), repository.observeSightings(id).first().map { it.place })
        assertTrue(env.savedFiles.isEmpty())
    }

    @Test
    fun `made-up photo names are ignored`() = runBlocking {
        val id = repository.save(diecast("Datsun"), listOf("../../evil.jpg", "${"a".repeat(36)}.jpg"), emptyList())

        assertTrue(repository.details(id)!!.photos.isEmpty())
    }

    @Test
    fun `a typed market value is marked manual and keeps its date until it changes`() = runBlocking {
        val id = repository.save(diecast("Datsun") { it.copy(marketValue = 150_000) })
        val saved = repository.details(id)!!.diecast
        assertEquals(ValueSource.Manual, saved.marketValueSource)
        assertEquals(1_000L, saved.marketValueUpdatedAt)

        clock = 2_000L
        repository.save(saved.copy(notes = "Kartu mulus"))
        assertEquals(1_000L, repository.details(id)!!.diecast.marketValueUpdatedAt)

        repository.save(saved.copy(marketValue = null))
        val cleared = repository.details(id)!!.diecast
        assertNull(cleared.marketValueSource)
        assertNull(cleared.marketValueUpdatedAt)
        assertEquals(1_000L, cleared.createdAt)
        assertEquals(2_000L, cleared.updatedAt)
    }

    @Test
    fun `a new spotted diecast gets its first sighting`() = runBlocking {
        val sighting = SightingEntity(diecastId = 0, place = "Toys Kingdom", seenOn = LocalDate.of(2026, 10, 1), price = 45_000)
        val id = repository.save(diecast("Porsche") { it.copy(status = DiecastStatus.Spotted) }, firstSighting = sighting)

        val item = repository.observeCollection(CollectionFilter(status = DiecastStatus.Spotted)).first().single()
        assertEquals(id, item.id)
        assertEquals(1, item.sightingsCount)
        assertEquals(45_000L, item.sightingsMinPrice)
    }

    @Test
    fun `the collection leaves out spotted entries unless asked`() = runBlocking {
        repository.save(diecast("Datsun"))
        repository.save(diecast("Porsche") { it.copy(status = DiecastStatus.Spotted) })
        repository.save(diecast("Skyline") { it.copy(status = DiecastStatus.Wishlist) })

        assertEquals(setOf("Datsun", "Skyline"), list().toSet())
        assertEquals(listOf("Porsche"), list(CollectionFilter(status = DiecastStatus.Spotted)))
    }

    @Test
    fun `search covers text fields and tags, and treats wildcards literally`() = runBlocking {
        repository.save(diecast("Datsun 510") { it.copy(storageLocation = "Rak A") }, tagNames = listOf("JDM"))
        repository.save(diecast("Toyota AE86") { it.copy(series = "HW J-Imports") }, tagNames = listOf("Drift"))
        repository.save(diecast("Ford Mustang"))

        assertEquals(listOf("Toyota AE86"), list(CollectionFilter(search = "ae86")))
        assertEquals(listOf("Datsun 510"), list(CollectionFilter(search = "jdm")))
        assertEquals(listOf("Datsun 510"), list(CollectionFilter(search = "rak a")))
        assertEquals(listOf("Toyota AE86"), list(CollectionFilter(search = "j-imp")))
        assertTrue(list(CollectionFilter(search = "%")).isEmpty())
        assertTrue(list(CollectionFilter(search = "_")).isEmpty())
    }

    @Test
    fun `brand, rarity and tag filters`() = runBlocking {
        repository.save(diecast("Datsun") { it.copy(brand = "Hot Wheels", rarity = Rarity.TreasureHunt) }, tagNames = listOf("JDM"))
        repository.save(diecast("Supra") { it.copy(brand = "hot wheels") }, tagNames = listOf("JDM", "Favorit"))
        repository.save(diecast("RX-7") { it.copy(brand = "Tomica") })

        assertEquals(setOf("Datsun", "Supra"), list(CollectionFilter(brand = "Hot Wheels")).toSet())
        assertEquals(listOf("Datsun"), list(CollectionFilter(rarity = Rarity.TreasureHunt)))
        assertEquals(listOf("Supra"), list(CollectionFilter(tag = "Favorit")))
        // One spelling per brand in the filter list.
        assertEquals(2, repository.observeBrands().first().size)
    }

    @Test
    fun `sorting`() = runBlocking {
        repository.save(diecast("Charger") { it.copy(marketValue = 50_000, year = 2020, brand = "Matchbox") })
        clock = 2_000L
        repository.save(diecast("Beetle") { it.copy(year = null, brand = null) })
        clock = 3_000L
        repository.save(diecast("Aventador") { it.copy(marketValue = 900_000, year = 2026, brand = "Hot Wheels") })

        assertEquals(listOf("Aventador", "Beetle", "Charger"), list(CollectionFilter(sort = CollectionSort.Newest)))
        assertEquals(listOf("Charger", "Beetle", "Aventador"), list(CollectionFilter(sort = CollectionSort.Oldest)))
        assertEquals(listOf("Aventador", "Beetle", "Charger"), list(CollectionFilter(sort = CollectionSort.Name)))
        assertEquals(listOf("Aventador", "Charger", "Beetle"), list(CollectionFilter(sort = CollectionSort.Value)))
        assertEquals(listOf("Aventador", "Charger", "Beetle"), list(CollectionFilter(sort = CollectionSort.Year)))
        assertEquals(listOf("Aventador", "Charger", "Beetle"), list(CollectionFilter(sort = CollectionSort.Brand)))
    }

    @Test
    fun `deleting removes rows, photo files and unused tags`() = runBlocking {
        val photo = env.pendingPhoto()
        val id = repository.save(diecast("Datsun"), listOf(photo), listOf("JDM"))
        repository.save(diecast("Supra"), tagNames = listOf("Favorit"))

        repository.delete(id)

        assertNull(repository.details(id))
        assertFalse(photo in env.savedFiles)
        assertEquals(listOf("Favorit"), repository.observeTagNames().first())
    }

    @Test
    fun `housekeeping finishes interrupted promotions and keeps fresh pending photos`() = runBlocking {
        val photo = env.pendingPhoto()
        val unsaved = env.pendingPhoto()
        // A save that committed but died before moving its file.
        env.db.diecasts().insert(DiecastEntity(id = 1, name = "Datsun"))
        env.db.diecasts().insertPhoto(io.github.caropeboka.diecasttracker.data.db.PhotoEntity(diecastId = 1, fileName = photo, position = 0))

        repository.housekeeping()

        assertEquals(setOf(photo), env.savedFiles)
        assertEquals(setOf(unsaved), env.pendingFiles)
    }
}
