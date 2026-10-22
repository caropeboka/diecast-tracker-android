package io.github.caropeboka.diecasttracker.data.scan

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ai.AiOfflineException
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import io.github.caropeboka.diecasttracker.data.ai.Confidence
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.ai.MemoryModelCache
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.ui.scan.QueueDefaults
import io.github.caropeboka.diecasttracker.ui.scan.QuickEdits
import io.github.caropeboka.diecasttracker.ui.scan.queuedForm
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

/** Web: `tests/Feature/ScanQueueTest.php` (the bulk part). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanQueueTest {
    private val env = TestEnvironment(now = { 1_000L })
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val scanner = DiecastScanner(
        MutableStateFlow(GeminiSettings(key = "test-key")),
        GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta"),
        env.photos,
        currentYear = { 2026 },
    )
    private val queue = ScanQueue(env.db, env.photos, scanner, clock = { 2_000L })
    private val today = LocalDate.of(2026, 10, 22)

    @After
    fun tearDown() = env.close()

    private suspend fun queued(): Queued {
        val photo = env.pendingPhoto()
        return Queued(queue.add(photo), photo)
    }

    private data class Queued(val id: Long, val photo: String)

    @Test
    fun `bulk upload, process and save`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Nissan Skyline GT-R (BNR34)", "brand": "Hot Wheels", "confidence": "high"}"""))
        val (id, photo) = queued()
        assertEquals(ScanItemStatus.Pending, queue.item(id)!!.status)

        queue.process(queue.next()!!)

        val item = queue.item(id)!!
        assertEquals(ScanItemStatus.Done, item.status)
        assertEquals("Nissan Skyline GT-R (BNR34)", item.fields!!.name)
        assertTrue(item.isSafe(hasMatches = false))

        // Saved with the collector's edits and the shared defaults, like the bulk screen does.
        val form = queuedForm(
            item,
            QuickEdits(brand = "Hot Wheels Premium", price = "45000"),
            QueueDefaults(storageLocation = "Box 1", tags = listOf("JDM"), date = "2026-10-20"),
            today,
        )
        assertTrue(form.validate(today).isEmpty())
        val diecastId = env.repository.save(form.toEntity(null), form.photos, form.tags)
        queue.saved(id)

        val saved = env.repository.details(diecastId)!!
        assertEquals("Nissan Skyline GT-R (BNR34)", saved.diecast.name)
        assertEquals("Hot Wheels Premium", saved.diecast.brand)
        assertEquals(45_000L, saved.diecast.purchasePrice)
        assertEquals("Box 1", saved.diecast.storageLocation)
        assertEquals(LocalDate.of(2026, 10, 20), saved.diecast.purchaseDate)
        assertTrue(saved.diecast.aiScanned)
        assertEquals(listOf("JDM"), saved.orderedTags.map { it.name })
        // The queue's photo became the diecast's, out of the pending folder.
        assertEquals(listOf(photo), saved.orderedPhotos.map { it.fileName })
        assertTrue(photo in env.savedFiles)
        assertTrue(env.pendingFiles.isEmpty())
        assertEquals(0, queue.observeCount().first())
    }

    @Test
    fun `quota hit keeps the item pending`() = runBlocking {
        fake.otherwise(429)
        val (id) = queued()

        assertThrows(AiRetryLaterException::class.java) { runBlocking { queue.process(queue.next()!!) } }

        assertEquals(ScanItemStatus.Pending, queue.item(id)!!.status)
        assertTrue(queue.hasPending())
    }

    @Test
    fun `no connection keeps the item pending`() = runBlocking {
        fake.otherwise(FakeGemini.Reply.Fail(UnknownHostException("gemini.test")))
        val (id) = queued()

        assertThrows(AiOfflineException::class.java) { runBlocking { queue.process(queue.next()!!) } }

        assertEquals(ScanItemStatus.Pending, queue.item(id)!!.status)
    }

    @Test
    fun `a failed scan says why and can be tried again or completed by hand`() = runBlocking {
        fake.otherwise(400)
        val (id, photo) = queued()

        queue.process(queue.next()!!)

        val failed = queue.item(id)!!
        assertEquals(ScanItemStatus.Failed, failed.status)
        assertTrue(failed.error!!.isNotBlank())
        assertNull(queue.next())

        queue.retry(id)
        assertEquals(ScanItemStatus.Pending, queue.item(id)!!.status)

        // By hand: the photo alone, nothing read.
        val form = queuedForm(queue.item(id)!!, QuickEdits(name = "Manual"), QueueDefaults(), today)
        assertEquals(listOf(photo), form.photos)
        assertFalse(form.aiScanned)
    }

    @Test
    fun `discarding deletes the photo`() = runBlocking {
        val (id, photo) = queued()

        queue.discard(id)

        assertNull(queue.item(id))
        assertFalse(photo in env.pendingFiles)
    }

    @Test
    fun `queued photos stay pending through the housekeeping`() = runBlocking {
        val (_, photo) = queued()
        val stray = env.pendingPhoto()

        env.photos.housekeeping(emptySet(), waiting = queue.photoFiles(), now = System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L)

        assertTrue(photo in env.pendingFiles)
        assertFalse(stray in env.pendingFiles)
    }

    @Test
    fun `saved as Ditemui, the shared place date and price make its first sighting`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Porsche 911 GT3", "brand": "Hot Wheels", "confidence": "medium"}"""))
        val (id) = queued()
        queue.process(queue.next()!!)
        val defaults = QueueDefaults(
            status = DiecastStatus.Spotted,
            date = "2026-10-21",
            place = PlaceValue("Toys Kingdom GI", -6.19, 106.82),
            storageLocation = "Rak A",
        )

        val form = queuedForm(queue.item(id)!!, QuickEdits(price = "65000"), defaults, today)
        val entity = form.toEntity(null)
        val sighting = form.firstSighting(today)

        assertEquals(DiecastStatus.Spotted, entity.status)
        assertNull(entity.purchasePlace)
        assertNull(entity.purchasePrice)
        assertNull(entity.storageLocation)
        assertEquals("Toys Kingdom GI", sighting.place)
        assertEquals(LocalDate.of(2026, 10, 21), sighting.seenOn)
        assertEquals(65_000L, sighting.price)
        assertEquals(-6.19, sighting.latitude!!, 0.0)
    }

    @Test
    fun `a wishlist price is the target price`() = runBlocking {
        val (id) = queued()
        val form = queuedForm(queue.item(id)!!, QuickEdits(name = "Supra", price = "250000"), QueueDefaults(status = DiecastStatus.Wishlist), today)

        val entity = form.toEntity(null)

        assertEquals(250_000L, entity.targetPrice)
        assertNull(entity.purchaseDate)
        assertNull(entity.purchasePrice)
    }

    @Test
    fun `only confident, named, new-looking readings are safe to save all at once`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"name": "Supra", "confidence": "low"}"""))
        val (id) = queued()
        queue.process(queue.next()!!)
        val item = queue.item(id)!!

        assertEquals(Confidence.Low, item.fields!!.confidence)
        assertFalse(item.isSafe(hasMatches = false))
        assertFalse(item.copy(fields = item.fields!!.copy(confidence = Confidence.High)).isSafe(hasMatches = true))
        assertFalse(item.copy(fields = item.fields!!.copy(name = null, confidence = Confidence.High)).isSafe(hasMatches = false))
        assertTrue(item.copy(fields = item.fields!!.copy(confidence = Confidence.High)).isSafe(hasMatches = false))
    }
}
