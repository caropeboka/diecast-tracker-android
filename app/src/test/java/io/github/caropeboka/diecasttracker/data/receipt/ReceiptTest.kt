package io.github.caropeboka.diecasttracker.data.receipt

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.vision
import io.github.caropeboka.diecasttracker.data.backup.BackupExporter
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.ReceiptEntity
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceResult
import io.github.caropeboka.diecasttracker.data.places.PlaceSource
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import io.github.caropeboka.diecasttracker.ui.edit.DiecastForm
import io.github.caropeboka.diecasttracker.ui.edit.Field
import io.github.caropeboka.diecasttracker.ui.scan.QueueDefaults
import io.github.caropeboka.diecasttracker.ui.scan.QuickEdits
import io.github.caropeboka.diecasttracker.ui.scan.queuedForm
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptTest {
    private val now = System.currentTimeMillis()
    private val env = TestEnvironment(now = { now })
    private val fake = FakeGemini()
    private var rates: ExchangeRates? = ExchangeRates(16_000.0, 2_200.0, "2026-11-13", "test", 0, others = mapOf("JPY" to 105.0))
    private val searched = mutableListOf<Pair<String?, List<String>>>()
    private val shops = mutableListOf<String?>()
    private val reader = ReceiptReader(fake.vision(), env.texts, rates = { rates }, today = { TODAY }) { name, queries, shop ->
        searched += name to queries
        shops += shop
        STREET.takeIf { "Jalan Tegar Beriman, Tengah, Cibinong" in queries } ?: SHOP.takeIf { shop == "Indomaret, Pangkalan, Teluknaga" }
    }
    private val store = ReceiptStore(env.root)
    private val receipts = ReceiptRepository(env.db, store, clock = { now })

    @After
    fun tearDown() = env.close()

    private fun answer(json: String) = fake.on("models", 200, FakeGemini.models()).otherwise(200, FakeGemini.answer(json))

    private fun receipt(id: Long = 0, store: String? = "Indomaret Cibinong Raya", date: LocalDate? = LocalDate.of(2026, 11, 12)) = ReceiptEntity(
        id = id,
        photo = "receipt.jpg",
        store = store,
        boughtOn = date,
        total = 67_800,
        lines = listOf(ReceiptLine("HW BASIC CAR ASST", 2, 33_900), ReceiptLine("KANTONG PLASTIK", 1, 200, diecast = false)),
        createdAt = now,
    )

    @Test
    fun `the reader takes the lines, plain or written as text, and each diecast a line with room left`() = runBlocking {
        answer(
            """{"is_receipt": true, "store": " Indomaret Cibinong Raya ", "date": "2026-11-12", "currency": "IDR", "total": "101.700",
                "lines": [
                    {"name": "HW BASIC CAR ASST", "quantity": 2, "unit_price": 33900, "is_diecast": true},
                    {"name": "MBX BASIC", "quantity": 1, "unit_price": "33.900", "is_diecast": true},
                    {"name": "KANTONG PLASTIK", "quantity": 1, "unit_price": 200, "is_diecast": false},
                    {"name": "  ", "quantity": 1, "unit_price": 1000, "is_diecast": false}
                ],
                "matches": [{"item": 0, "line": 0}, {"item": 1, "line": 0}, {"item": 2, "line": 0}, {"item": 3, "line": 7}, {"item": 9, "line": 1}],
                "note": null}""",
        )

        val read = reader.read(TestEnvironment.fixtureJpeg, List(4) { receiptItem("Car $it", "Hot Wheels", null, null) })

        assertEquals("Indomaret Cibinong Raya", read.store)
        assertEquals(LocalDate.of(2026, 11, 12), read.boughtOn)
        assertEquals(101_700L, read.total)
        assertNull(read.currency)
        assertEquals(listOf("HW BASIC CAR ASST", "MBX BASIC", "KANTONG PLASTIK"), read.lines.map { it.name })
        assertEquals(listOf<Long?>(33_900, 33_900, 200), read.lines.map { it.price })
        assertEquals(listOf(true, true, false), read.lines.map { it.diecast })
        // The line of two takes two diecasts; the third, a missing line and a diecast nobody asked about are left out.
        assertEquals(mapOf(0 to 0, 1 to 0), read.matches)
        assertNull(read.note)

        val instruction = Json.parseToJsonElement(fake.bodies.last()).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            .last().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(instruction.contains("0. name: Car 0, brand: Hot Wheels"))
        assertTrue(instruction.contains("3. name: Car 3"))
    }

    @Test
    fun `the branch address finds the store on the map, and an online order looks for nothing`() = runBlocking {
        answer(
            """{"is_receipt": true, "store": "Indomaret Tegar Beriman", "address": " Jl. Tegar Beriman No. 5, Tengah, Cibinong ",
                "map_queries": ["Jalan Tegar Beriman, Tengah, Cibinong", " ", "Jalan Tegar Beriman, Tengah, Cibinong", "Tengah, Cibinong", "Cibinong", "Bogor"],
                "date": "2026-11-12", "currency": "IDR", "total": 33900,
                "lines": [{"name": "HW BASIC CAR ASST", "quantity": 1, "unit_price": 33900, "is_diecast": true}], "matches": [], "note": null}""",
        )
        val read = reader.read(TestEnvironment.fixtureJpeg, emptyList())
        assertEquals("Jl. Tegar Beriman No. 5, Tengah, Cibinong", read.address)
        // Blanks and repeats dropped, three at most; nothing looked up while reading.
        assertEquals(listOf("Jalan Tegar Beriman, Tengah, Cibinong", "Tengah, Cibinong", "Cibinong"), read.mapQueries)
        assertNull(read.latitude)
        assertTrue(searched.isEmpty())

        val located = reader.locate(read)
        assertEquals("Indomaret Tegar Beriman" to read.mapQueries, searched.single())
        assertEquals(STREET.lat, located.latitude!!, 0.0)
        assertEquals(STREET.lng, located.longitude!!, 0.0)
        val kept = receipts.add(store.save(TestEnvironment.fixtureJpeg), located)
        assertEquals("Jl. Tegar Beriman No. 5, Tengah, Cibinong" to STREET.lat, kept.address to kept.latitude)

        // Not found: the reading stays as it was.
        val elsewhere = read.copy(mapQueries = listOf("Jalan Entah, Bogor"))
        assertEquals(elsewhere, reader.locate(elsewhere))

        // An online order has no shop to go to, whatever searches come with it.
        answer(
            """{"is_receipt": true, "store": "Diecast Corner · Tokopedia", "address": null, "map_queries": ["Jakarta Barat"],
                "date": "2026-11-12", "currency": "IDR", "total": 45000,
                "lines": [{"name": "Hot Wheels Skyline", "quantity": 1, "unit_price": 45000, "is_diecast": true}], "matches": [], "note": null}""",
        )
        val online = reader.read(TestEnvironment.fixtureJpeg, emptyList())
        assertTrue(online.mapQueries.isEmpty())
        assertEquals(online, reader.locate(online))
        assertEquals(2, searched.size)
    }

    @Test
    fun `a receipt in yen is converted at the day's rate, and without a rate it says so`() = runBlocking {
        val yen = """{"is_receipt": true, "store": "Tomica Shop Tokyo", "date": "2026-11-02", "currency": "jpy", "total": 1100,
            "lines": [{"name": "トミカ No.23", "quantity": 2, "unit_price": 550, "is_diecast": true}], "matches": [], "note": null}"""
        answer(yen)
        val read = reader.read(TestEnvironment.fixtureJpeg, emptyList())
        assertEquals("JPY", read.currency)
        assertEquals(57_750L, read.lines.single().price)
        assertEquals(550.0, read.lines.single().original!!, 0.0)
        assertEquals(115_500L, read.total)

        rates = null
        answer(yen)
        val unconverted = reader.read(TestEnvironment.fixtureJpeg, emptyList())
        assertNull(unconverted.lines.single().price)
        assertNull(unconverted.total)
        assertEquals(env.texts(R.string.receipt_no_rate, "JPY"), unconverted.note)
    }

    @Test
    fun `not a receipt, or nothing on it, is an error`() = runBlocking {
        answer("""{"is_receipt": false, "store": null, "date": null, "currency": "IDR", "total": null, "lines": [], "matches": [], "note": null}""")
        try {
            reader.read(TestEnvironment.fixtureJpeg, emptyList())
            fail("a photo of a car is not a receipt")
        } catch (e: ReceiptException) {
            assertEquals(env.texts(R.string.receipt_not_receipt), e.message)
        }

        answer("""{"is_receipt": true, "store": "Alfamart", "date": "2027-01-01", "currency": "IDR", "total": null, "lines": [], "matches": [], "note": null}""")
        try {
            reader.read(TestEnvironment.fixtureJpeg, emptyList())
            fail("nothing could be read")
        } catch (e: ReceiptException) {
            assertEquals(env.texts(R.string.receipt_no_lines), e.message)
        }
    }

    @Test
    fun `the form takes the line's price, and the date and store only where it has none`() {
        val today = LocalDate.of(2026, 11, 14)
        val receipt = receipt(id = 5)

        val (filled, fields) = DiecastForm.new(DiecastStatus.Owned, today).withReceipt(receipt, 0, dateIsDefault = true)
        assertEquals("33900", filled.purchasePrice)
        assertEquals("2026-11-12", filled.purchaseDate)
        assertEquals("Indomaret Cibinong Raya", filled.purchasePlace)
        assertNull(filled.purchaseLatitude)
        assertEquals(setOf(Field.PurchasePrice, Field.PurchaseDate, Field.PurchasePlace), fields)
        assertEquals(5L, filled.toEntity(null).receiptId)
        assertEquals(0, filled.toEntity(null).receiptLine)

        val typed = DiecastForm.new(DiecastStatus.Owned, today).copy(purchaseDate = "2026-11-01", purchasePlace = "Toys Kingdom", purchasePrice = "50000")
        val (kept, keptFields) = typed.withReceipt(receipt, 0, dateIsDefault = false)
        assertEquals("2026-11-01", kept.purchaseDate)
        assertEquals("Toys Kingdom", kept.purchasePlace)
        assertEquals("33900", kept.purchasePrice)
        assertEquals(setOf(Field.PurchasePrice), keptFields)

        // Kept as proof only: the price stays as typed.
        val (proof, proofFields) = typed.withReceipt(receipt, null, dateIsDefault = false)
        assertEquals("50000", proof.purchasePrice)
        assertEquals(5L, proof.receiptId)
        assertNull(proof.receiptLine)
        assertTrue(proofFields.isEmpty())

        // Not bought (any more): no receipt either.
        assertNull(filled.copy(status = DiecastStatus.Wishlist).toEntity(null).receiptId)
    }

    @Test
    fun `the store found on the map comes with its point, also for the same store typed by hand`() {
        val today = LocalDate.of(2026, 11, 14)
        val located = receipt(id = 5).copy(address = "Jl. Tegar Beriman No. 5", latitude = STREET.lat, longitude = STREET.lng)

        val (filled, fields) = DiecastForm.new(DiecastStatus.Owned, today).withReceipt(located, 0, dateIsDefault = true)
        assertEquals("Indomaret Cibinong Raya", filled.purchasePlace)
        assertEquals(STREET.lat to STREET.lng, filled.purchaseLatitude to filled.purchaseLongitude)
        assertTrue(Field.PurchasePlace in fields)

        val typed = DiecastForm.new(DiecastStatus.Owned, today).copy(purchasePlace = " indomaret cibinong raya ")
        val (pointed, pointedFields) = typed.withReceipt(located, 0, dateIsDefault = false)
        assertEquals(STREET.lat, pointed.purchaseLatitude!!, 0.0)
        assertEquals("Indomaret Cibinong Raya", pointed.purchasePlace)
        assertTrue(Field.PurchasePlace in pointedFields)

        // Another place typed, or one with its own point: kept.
        val other = DiecastForm.new(DiecastStatus.Owned, today).copy(purchasePlace = "Toys Kingdom")
        assertEquals(other.purchaseLocation, other.withReceipt(located, 0, dateIsDefault = false).first.purchaseLocation)
        val pinned = typed.copy(purchaseLatitude = -6.2, purchaseLongitude = 106.8)
        assertEquals(pinned.purchaseLocation, pinned.withReceipt(located, 0, dateIsDefault = false).first.purchaseLocation)

        // No name printed: the address stands for the store.
        val nameless = located.copy(store = null)
        assertEquals("Jl. Tegar Beriman No. 5", DiecastForm.new(DiecastStatus.Owned, today).withReceipt(nameless, 0, dateIsDefault = true).first.purchasePlace)
    }

    @Test
    fun `a receipt read again finds its store by the shop's own search, and Bought at keeps its name`() = runBlocking {
        // Read before addresses were: no address, no point.
        val old = receipts.add(store.save(TestEnvironment.fixtureJpeg), reading().copy(store = "Indomaret Teluk Naga II"))
        assertNull(old.address)
        answer(
            """{"is_receipt": true, "store": "INDOMARET TELUK NAGA II", "address": "Jl. Raya Teluk Naga No. 8, Pangkalan, Teluknaga",
                "map_queries": ["Jalan Raya Teluk Naga, Pangkalan, Teluknaga"], "shop_query": " Indomaret, Pangkalan, Teluknaga ",
                "date": "2026-11-14", "currency": "IDR", "total": 71100,
                "lines": [{"name": "HW FAST FURIOUS 2027", "quantity": 1, "unit_price": 71100, "is_diecast": true}], "matches": [], "note": null}""",
        )
        val again = reader.locate(reader.read(TestEnvironment.fixtureJpeg, emptyList()))
        assertEquals("Indomaret, Pangkalan, Teluknaga", shops.last())
        assertEquals(SHOP.lat, again.latitude!!, 0.0)

        val placed = receipts.setPlace(old, again)
        assertEquals("Jl. Raya Teluk Naga No. 8, Pangkalan, Teluknaga", placed.address)
        assertEquals(SHOP.lat to SHOP.lng, placed.latitude to placed.longitude)
        assertEquals(placed, receipts.get(old.id))
        // The receipt's own store name stays as first read.
        assertEquals("Indomaret Teluk Naga II", placed.store)

        // Not found again: what was found before stays.
        assertEquals(placed, receipts.setPlace(placed, again.copy(latitude = null, longitude = null, address = null)))

        // Bought at typed by hand takes the point under its own name; one with a point already stays; empty takes the store.
        val today = LocalDate.of(2026, 11, 15)
        val typed = DiecastForm.new(DiecastStatus.Owned, today).copy(purchasePlace = "Indomaret Teluk Naga II ")
        val pointed = typed.withReceiptPoint(placed.place!!)!!
        assertEquals("Indomaret Teluk Naga II", pointed.purchasePlace)
        assertEquals(SHOP.lat to SHOP.lng, pointed.purchaseLatitude to pointed.purchaseLongitude)
        assertNull(pointed.withReceiptPoint(placed.place!!))
        assertEquals("Indomaret Teluk Naga II", DiecastForm.new(DiecastStatus.Owned, today).withReceiptPoint(placed.place!!)!!.purchasePlace)
        assertNull(typed.withReceiptPoint(old.place!!))
    }

    @Test
    fun `the shop's own search comes only with an address`() = runBlocking {
        answer(
            """{"is_receipt": true, "store": "Diecast Corner · Tokopedia", "address": null, "shop_query": "Diecast Corner, Jakarta",
                "date": "2026-11-12", "currency": "IDR", "total": 45000,
                "lines": [{"name": "Hot Wheels Skyline", "quantity": 1, "unit_price": 45000, "is_diecast": true}], "matches": [], "note": null}""",
        )
        val online = reader.read(TestEnvironment.fixtureJpeg, emptyList())
        assertNull(online.shopQuery)
        assertEquals(online, reader.locate(online))
        assertTrue(shops.isEmpty())
    }

    @Test
    fun `a diecast keeps its receipt and line, unless the receipt went meanwhile`() = runBlocking {
        val kept = receipts.add(store.save(TestEnvironment.fixtureJpeg), reading())
        val id = env.repository.save(DiecastEntity(name = "Datsun 510", receiptId = kept.id, receiptLine = 1))
        assertEquals(kept.id, env.db.diecasts().get(id)?.receiptId)
        assertEquals(1, env.db.diecasts().get(id)?.receiptLine)
        assertEquals(listOf("Datsun 510"), receipts.observeUses(kept.id).first().map { it.name })

        val gone = env.repository.save(DiecastEntity(name = "Skyline", receiptId = 999, receiptLine = 0))
        assertNull(env.db.diecasts().get(gone)?.receiptId)
        assertNull(env.db.diecasts().get(gone)?.receiptLine)
    }

    @Test
    fun `start-up clears receipts nothing was bought with for a day, and stray photos`() = runBlocking {
        val day = ReceiptRepository.DAY_MS
        val old = ReceiptRepository(env.db, store, clock = { now - 2 * day })
        val used = old.add(store.save(TestEnvironment.fixtureJpeg), reading())
        val abandoned = old.add(store.save(TestEnvironment.fixtureJpeg), reading())
        val fresh = receipts.add(store.save(TestEnvironment.fixtureJpeg), reading())
        env.repository.save(DiecastEntity(name = "Datsun 510", receiptId = used.id))
        val stray = store.file(store.save(TestEnvironment.fixtureJpeg)).apply { setLastModified(now - 2 * day) }
        listOf(used, abandoned).forEach { store.file(it.photo).setLastModified(now - 2 * day) }

        receipts.housekeeping()

        assertNotNull(receipts.get(used.id))
        assertNull(receipts.get(abandoned.id))
        assertNotNull(receipts.get(fresh.id))
        assertTrue(store.file(used.photo).exists())
        assertFalse(store.file(abandoned.photo).exists())
        assertTrue(store.file(fresh.photo).exists())
        assertFalse(stray.exists())
        assertEquals(listOf(fresh.id, used.id), receipts.observeRecent().first().map { it.id })
    }

    @Test
    fun `a phone backup brings a shared receipt back once, with its photo, for both diecasts`() = runBlocking {
        val kept = receipts.add(store.save(TestEnvironment.fixtureJpeg), reading().copy(address = "Jl. Tegar Beriman No. 5", latitude = STREET.lat, longitude = STREET.lng))
        env.repository.save(DiecastEntity(name = "Datsun 510", receiptId = kept.id, receiptLine = 0))
        env.repository.save(DiecastEntity(name = "Skyline GT-R", receiptId = kept.id, receiptLine = 0))
        env.repository.save(DiecastEntity(name = "Without one"))

        val zip = File(env.root, "backup.zip")
        val exporter = BackupExporter(env.context, env.db, env.photos, CoroutineScope(Dispatchers.Unconfined), root = env.root)
        zip.outputStream().use { exporter.writeBackup(it) }

        val other = TestEnvironment()
        try {
            val importer = BackupImporter(
                other.context, other.db, other.repository, other.photos, CoroutineScope(Dispatchers.Unconfined),
                today = { TODAY }, root = other.root,
            )
            importer.import(zip, replace = true)

            val diecasts = other.db.diecasts().allDetails().associateBy { it.diecast.name }
            val receiptId = diecasts.getValue("Datsun 510").diecast.receiptId
            assertNotNull(receiptId)
            assertEquals(receiptId, diecasts.getValue("Skyline GT-R").diecast.receiptId)
            assertEquals(0, diecasts.getValue("Skyline GT-R").diecast.receiptLine)
            assertNull(diecasts.getValue("Without one").diecast.receiptId)

            val restored = other.db.receipts().get(receiptId!!)!!
            assertEquals("Indomaret Cibinong Raya", restored.store)
            assertEquals(LocalDate.of(2026, 11, 12), restored.boughtOn)
            assertEquals(Triple("Jl. Tegar Beriman No. 5", STREET.lat, STREET.lng), Triple(restored.address, restored.latitude, restored.longitude))
            assertEquals(reading().lines, restored.lines)
            assertEquals(1, other.db.receipts().allPhotos().size)
            assertTrue(ReceiptStore(other.root).file(restored.photo).length() > 0)
        } finally {
            other.close()
        }
    }

    @Test
    fun `a queued photo bought with a receipt keeps it, and only when owned`() {
        val item = ScanItemEntity(id = 3, photoFile = "a.jpg", status = ScanItemStatus.Done, createdAt = 1)
        val edits = QuickEdits(price = "33900", receiptId = 5, receiptLine = 1)
        val today = LocalDate.of(2026, 11, 14)

        val owned = queuedForm(item, edits, QueueDefaults(status = DiecastStatus.Owned), today)
        assertEquals(5L, owned.toEntity(null).receiptId)
        assertEquals(1, owned.toEntity(null).receiptLine)
        assertEquals(33_900L, owned.toEntity(null).purchasePrice)

        assertNull(queuedForm(item, edits, QueueDefaults(status = DiecastStatus.Wishlist), today).toEntity(null).receiptId)
    }

    private fun reading() = ReceiptReading(
        store = "Indomaret Cibinong Raya",
        boughtOn = LocalDate.of(2026, 11, 12),
        total = 67_800,
        currency = null,
        lines = receipt().lines,
        matches = emptyMap(),
        note = null,
        readBy = null,
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 11, 14)
        val STREET = PlaceResult("Jalan Tegar Beriman", null, -6.4812501, 106.8235227, PlaceSource.Osm)
        val SHOP = PlaceResult("Indomaret", null, -6.0649631, 106.6438872, PlaceSource.Osm)
    }
}
