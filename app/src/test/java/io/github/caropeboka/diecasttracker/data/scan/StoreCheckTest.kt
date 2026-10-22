package io.github.caropeboka.diecasttracker.data.scan

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.ai.MemoryModelCache
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.UnknownHostException
import java.time.LocalDate

/** Web: `tests/Feature/ScanQueueTest.php` (the store check part) and `CheckController`. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreCheckTest {
    private val env = TestEnvironment(now = { 1_000L })
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val scanner = DiecastScanner(
        MutableStateFlow(GeminiSettings(key = "test-key")),
        GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta"),
        env.photos,
        currentYear = { 2026 },
    )
    private val check = StoreCheck(env.db, env.photos, scanner, CollectionMatcher(env.repository), env.repository, clock = { NOW })
    private val queue = ScanQueue(env.db, env.photos, scanner)
    private val today = LocalDate.of(2026, 10, 22)
    private val store = PlaceValue("Toys Kingdom GI", -6.19, 106.82, accuracy = 12, at = NOW - 60_000)

    @After
    fun tearDown() = env.close()

    private suspend fun diecast(name: String, brand: String? = null, status: DiecastStatus = DiecastStatus.Owned, place: String? = null) =
        env.repository.save(DiecastEntity(name = name, brand = brand, status = status, purchasePlace = place))

    @Test
    fun `a check finds what is owned, and +1 counts another`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Porsche 911 GT3", "brand": "Mini GT", "confidence": "medium"}"""))
        val owned = diecast("Porsche 911 GT3", "Mini GT")

        val item = check.check(env.pendingPhoto())

        assertEquals(ScanItemStatus.Done, item.status)
        assertEquals("Porsche 911 GT3", item.fields!!.name)
        val matches = check.matches(item.fields!!)
        assertEquals(listOf(owned), matches.map { it.id })
        assertEquals(Verdict.Owned, verdictOf(failed = false, matches))

        assertEquals(2, env.repository.addOne(owned)!!.quantity)
    }

    @Test
    fun `owned beats wishlist beats seen`() = runBlocking {
        val spotted = diecast("RX-7", status = DiecastStatus.Spotted)
        val wish = diecast("RX-7 FD", status = DiecastStatus.Wishlist)
        val owned = diecast("RX-7 FC")
        val items = env.repository.listItems(listOf(spotted, wish, owned)).sortedBy { it.id }

        assertEquals(Verdict.Owned, verdictOf(false, items))
        assertEquals(Verdict.Wishlist, verdictOf(false, items.take(2)))
        assertEquals(Verdict.Spotted, verdictOf(false, items.take(1)))
        assertEquals(Verdict.New, verdictOf(false, emptyList()))
        assertEquals(Verdict.Failed, verdictOf(true, items))
    }

    @Test
    fun `acquiring a wishlist item moves it to the collection, bought here today`() = runBlocking {
        val wish = diecast("RX-7", status = DiecastStatus.Wishlist)

        val got = env.repository.acquire(wish, store, today, now = NOW)!!

        assertEquals(DiecastStatus.Owned, got.status)
        assertEquals(today, got.purchaseDate)
        assertEquals("Toys Kingdom GI", got.purchasePlace)
        assertEquals(-6.19, got.purchaseLatitude!!, 0.0)
    }

    @Test
    fun `acquiring keeps a purchase place it already had, and a stale point stays out`() = runBlocking {
        val known = diecast("Supra", status = DiecastStatus.Spotted, place = "Lotte Mart")
        val other = diecast("Silvia", status = DiecastStatus.Spotted)

        assertEquals("Lotte Mart", env.repository.acquire(known, store, today, now = NOW)!!.purchasePlace)
        val later = env.repository.acquire(other, store, today, now = NOW + 4 * 60 * 60 * 1000L)!!
        assertEquals("Toys Kingdom GI", later.purchasePlace)
        assertNull(later.purchaseLatitude)
    }

    @Test
    fun `seen on an existing entry — a sighting here with the check's photo`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Datsun 510", "brand": "Hot Wheels"}"""))
        val spotted = diecast("Datsun 510", "Hot Wheels", DiecastStatus.Spotted)
        val item = check.check(env.pendingPhoto())

        check.spotExisting(spotted, store, 35_000, today, item.id, item.photoFile)

        val sighting = env.repository.observeSightings(spotted).first().single()
        assertEquals("Toys Kingdom GI", sighting.place)
        assertEquals(35_000L, sighting.price)
        assertEquals(today, sighting.seenOn)
        assertEquals(-6.19, sighting.latitude!!, 0.0)
        assertEquals(item.photoFile, sighting.photoFile)
        assertTrue(item.photoFile in env.savedFiles)
        assertFalse(check.isOpen(item.id))
    }

    @Test
    fun `seen and new — a Ditemui diecast from what was read, with its photo and first sighting`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Toyota AE86", "brand": "Tomica", "rarity": "chase"}"""))
        val item = check.check(env.pendingPhoto())
        assertTrue(check.matches(item.fields!!).isEmpty())

        val id = check.spotNew(item.fields!!, aiScanned = true, store, null, today, item.id, item.photoFile)

        val saved = env.repository.details(id)!!
        assertEquals("Toyota AE86", saved.diecast.name)
        assertEquals("Tomica", saved.diecast.brand)
        assertEquals(DiecastStatus.Spotted, saved.diecast.status)
        assertTrue(saved.diecast.aiScanned)
        assertNull(saved.diecast.purchaseDate)
        assertEquals(listOf(item.photoFile), saved.orderedPhotos.map { it.fileName })
        assertEquals("Toys Kingdom GI", saved.orderedSightings.single().place)
        assertNull(saved.orderedSightings.single().photoFile)
        assertFalse(check.isOpen(item.id))
    }

    @Test
    fun `without a name there is nothing to log`() {
        assertThrows(StoreCheckException::class.java) {
            runBlocking { check.spotNew(ScanFields(brand = "Hot Wheels"), aiScanned = true, store, null, today) }
        }
    }

    @Test
    fun `a failed check says why, and can be tried again`() = runBlocking {
        fake.otherwise(FakeGemini.Reply.Fail(UnknownHostException("gemini.test")))
        val item = check.check(env.pendingPhoto())
        assertEquals(ScanItemStatus.Failed, item.status)
        assertTrue(item.error!!.isNotBlank())

        fake.otherwise(200, FakeGemini.answer("""{"name": "Mazda RX-7"}"""))
        val again = check.retry(item.id)!!
        assertEquals(ScanItemStatus.Done, again.status)
        assertNull(again.error)
        assertEquals("Mazda RX-7", again.fields!!.name)
    }

    @Test
    fun `typed instead of photographed — every status, owned first, spotted ones too`() = runBlocking {
        val spotted = diecast("Nissan Skyline R32", status = DiecastStatus.Spotted)
        val owned = diecast("Nissan Skyline R34")
        diecast("Honda Civic")

        assertEquals(listOf(owned, spotted), check.search("skyline").map { it.id })
        assertTrue(check.search("s").isEmpty())

        val id = check.spotNew(ScanFields(name = "Nissan Silvia S15"), aiScanned = false, store, 25_000, today)
        assertFalse(env.repository.details(id)!!.diecast.aiScanned)
    }

    @Test
    fun `checks are not in the bulk queue, and old ones go with their photos`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Datsun 510"}"""))
        val old = check.check(env.pendingPhoto())
        assertEquals(0, queue.observeCount().first())
        assertNull(queue.next())
        // Its photo stays pending through the housekeeping while the check is kept.
        assertTrue(old.photoFile in queue.photoFiles())

        check.prune(before = NOW + 1)

        assertFalse(check.isOpen(old.id))
        assertFalse(old.photoFile in env.pendingFiles)
    }

    private companion object {
        const val NOW = 10_000_000L
    }
}
