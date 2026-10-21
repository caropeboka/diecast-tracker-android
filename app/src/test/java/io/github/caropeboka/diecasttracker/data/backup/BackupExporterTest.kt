package io.github.caropeboka.diecasttracker.data.backup

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.dex.DexCustom
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.DexRow
import io.github.caropeboka.diecasttracker.data.dex.SheetReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupExporterTest {
    /** The phone that makes the backup. */
    private val env = TestEnvironment(now = { CREATED })
    private var backedUpAt: Long? = null
    private val exporter = BackupExporter(
        env.context, env.db, env.photos, CoroutineScope(Dispatchers.Unconfined), root = env.root, clock = { NOW },
        onBackup = { backedUpAt = it },
    )
    private val dao = env.db.dex()

    /** The phone it is restored on. */
    private val other = TestEnvironment()
    private val importer = importer(other)

    @After
    fun tearDown() {
        env.close()
        other.close()
    }

    private fun importer(target: TestEnvironment) = BackupImporter(
        target.context, target.db, target.repository, target.photos, CoroutineScope(Dispatchers.Unconfined),
        today = { LocalDate.of(2026, 10, 21) }, root = target.root,
    )

    private class Seeded(val wikiId: Long, val datsunItem: Long, val ownId: Long, val ownItem: CatalogItemEntity)

    /** A wiki year with a downloaded thumbnail, an own dex with a photo, and diecasts linked to both. */
    private suspend fun seed(): Seeded {
        val wikiId = dao.insertCatalog(
            CatalogEntity(
                brand = "Hot Wheels", name = "Hot Wheels Mainline", year = 2026, source = "hotwheels_wiki",
                sourcePage = "List of 2026 Hot Wheels", syncedAt = SYNCED, createdAt = SYNCED,
            ),
        )
        File(env.root, "dex/$wikiId/thumb.jpg").apply { parentFile!!.mkdirs() }.writeBytes(TestEnvironment.fixtureJpeg)
        val datsunItem = dao.insertItem(
            CatalogItemEntity(
                catalogId = wikiId, number = "061", sort = 61, name = "'71 Datsun 510", series = "HW J-Imports",
                seriesNumber = "2/10", imageFile = "Datsun 510.jpg", imageUrl = "https://example.org/datsun.jpg",
                imagePath = "dex/$wikiId/thumb.jpg",
            ),
        )
        dao.insertItem(CatalogItemEntity(catalogId = wikiId, number = "061", sort = 61, variant = 2, name = "'71 Datsun 510", isTh = true))

        val custom = DexCustom(env.db, DexMatcher(dao), env.photos, env.root)
        val ownId = custom.create("Tomica", "Tomica Premium", "")
        val ownItem = custom.addOne(ownId, DexRow(number = "01", name = "Nissan Skyline GT-R"), env.pendingPhoto())!!

        val datsun = env.repository.save(
            DiecastEntity(
                name = "'71 Datsun 510", brand = "Hot Wheels", series = "HW J-Imports", collectionNumber = "61/250 · 2/10",
                year = 2026, color = "Blue", rarity = Rarity.TreasureHunt, packaging = Packaging.Loose, quantity = 2,
                purchasePrice = 35_000, marketValue = 150_000, marketValueSource = ValueSource.PriceCheck,
                marketValueUpdatedAt = VALUED, purchaseDate = LocalDate.of(2026, 10, 1), purchasePlace = "Toys Kingdom",
                purchaseLatitude = -6.2, purchaseLongitude = 106.8, notes = "Kartu \"mulus\" & <rapi>", aiScanned = true,
            ),
            listOf(env.pendingPhoto(), env.pendingPhoto()),
            listOf("JDM", "Favorit"),
        )
        dao.setLink(datsun, datsunItem, CatalogMatch.Confirmed.key)
        env.repository.addSighting(
            SightingEntity(
                diecastId = datsun, place = "Gramedia", latitude = -6.21, longitude = 106.81,
                seenOn = LocalDate.of(2026, 9, 30), price = 45_000, photoFile = env.pendingPhoto(),
            ),
        )

        val skyline = env.repository.save(DiecastEntity(name = "Nissan Skyline GT-R", brand = "Tomica"), listOf(env.pendingPhoto()))
        dao.setLink(skyline, ownItem.id, CatalogMatch.Confirmed.key)

        val mystery = env.repository.save(DiecastEntity(name = "Mystery car", brand = "Hot Wheels", status = DiecastStatus.Wishlist))
        dao.setLink(mystery, null, CatalogMatch.Ignored.key)

        return Seeded(wikiId, datsunItem, ownId, ownItem)
    }

    private suspend fun backup(): File = File(env.root, "backup.zip").also { file ->
        file.outputStream().use { exporter.writeBackup(it) }
    }

    private suspend fun byName(target: TestEnvironment, name: String) =
        target.db.diecasts().allDetails().single { it.diecast.name == name }

    @Test
    fun `the backup has the web's layout plus the Dex`() = runBlocking {
        seed()
        val zip = ZipFile(backup())

        val names = zip.entries().toList().map { it.name }
        assertEquals(listOf("diecasts.json", "dex.json", "koleksi.xlsx"), names.take(3))
        // 3 diecast photos, 1 sighting photo, the own dex's photo; not the wiki thumbnail, which downloads again.
        assertEquals(4, names.count { it.startsWith("photos/") })
        assertEquals(1, names.count { it.startsWith("dex/") })
        assertFalse(names.any { it.endsWith("thumb.jpg") })

        val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("diecasts.json")).reader().readText()).jsonObject
        assertEquals("diecast-tracker-backup", manifest["format"]!!.jsonPrimitive.content)
        assertEquals(NOW, WebBackupFormat.parseTimestamp(manifest["exported_at"]!!.jsonPrimitive.content))

        val records = manifest["diecasts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, records.size)
        val datsun = records.single { it.string("name") == "'71 Datsun 510" }
        // Every field the web's restore validates, and no catalog_item_id it would check against its own Dex.
        assertTrue(WEB_FIELDS.all { it in datsun })
        assertFalse("catalog_item_id" in datsun)
        assertEquals("treasure_hunt", datsun.string("rarity"))
        assertEquals("35000", datsun.string("purchase_price"))
        assertEquals("2026-10-01", datsun.string("purchase_date"))
        assertEquals("confirmed", datsun.string("dex_match"))
        assertEquals(listOf("Favorit", "JDM"), datsun["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The web's own restore accepts only these photo names.
        val photos = (datsun["photos"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(2, photos.size)
        assertTrue(photos.all { Regex("photos/[A-Za-z0-9._-]+\\.jpe?g").matches(it) })
        assertNotNull(datsun["sightings"]!!.jsonArray.single().jsonObject.string("photo"))
    }

    @Test
    fun `a backup restores everything on another phone`() = runBlocking {
        seed()
        val result = importer.import(backup(), replace = false)

        assertEquals(3, result.imported)
        assertEquals(emptyList<String>(), result.skipped)
        assertEquals(2, result.dexes)

        val datsun = byName(other, "'71 Datsun 510")
        with(datsun.diecast) {
            assertEquals(Rarity.TreasureHunt, rarity)
            assertEquals(Packaging.Loose, packaging)
            assertEquals(2, quantity)
            assertEquals(150_000L, marketValue)
            assertEquals(ValueSource.PriceCheck, marketValueSource)
            assertEquals(VALUED, marketValueUpdatedAt)
            assertEquals(CREATED, createdAt)
            assertEquals(-6.2, purchaseLatitude!!, 0.0)
            assertEquals("Kartu \"mulus\" & <rapi>", notes)
            assertTrue(aiScanned)
            assertEquals(CatalogMatch.Confirmed, catalogMatch)
        }
        assertEquals(listOf("Favorit", "JDM"), datsun.orderedTags.map { it.name })
        assertEquals(2, datsun.photos.size)

        val sighting = datsun.sightings.single()
        assertEquals("Gramedia", sighting.place)
        assertEquals(45_000L, sighting.price)
        assertEquals(CREATED, sighting.createdAt)
        assertNotNull(sighting.photoFile)

        // The link points at the same release in the restored wiki year, whose thumbnail downloads again.
        val item = other.db.dex().itemRow(datsun.diecast.catalogItemId!!)!!
        assertEquals("061", item.item.number)
        assertEquals(1, item.item.variant)
        assertEquals("hotwheels_wiki", item.catalogSource)
        assertNull(item.item.imagePath)
        assertEquals("https://example.org/datsun.jpg", item.item.imageUrl)
        assertEquals(SYNCED, other.db.dex().catalog(item.item.catalogId)!!.syncedAt)
        assertEquals(2, other.db.dex().items(item.item.catalogId).size)

        // The own dex comes back with its photo.
        val skyline = byName(other, "Nissan Skyline GT-R").diecast
        val own = other.db.dex().item(skyline.catalogItemId!!)!!
        assertEquals("Nissan Skyline GT-R", own.name)
        assertTrue(File(other.root, own.imagePath!!).isFile)
        assertTrue(own.imagePath!!.startsWith("dex/${own.catalogId}/"))

        assertEquals(CatalogMatch.Ignored, byName(other, "Mystery car").diecast.catalogMatch)

        assertEquals(4, other.savedFiles.size)
        assertTrue(other.pendingFiles.isEmpty())
    }

    @Test
    fun `merging into a phone with the same lists reuses them`() = runBlocking {
        val seeded = seed()
        val zip = backup()

        importer(env).import(zip, replace = false)

        assertEquals(2, dao.allCatalogs().size)
        assertEquals(2, dao.items(seeded.wikiId).size)
        assertEquals(1, dao.items(seeded.ownId).size)
        val datsuns = env.db.diecasts().allDetails().filter { it.diecast.name == "'71 Datsun 510" }
        assertEquals(listOf(seeded.datsunItem, seeded.datsunItem), datsuns.map { it.diecast.catalogItemId })
        val skylines = env.db.diecasts().allDetails().filter { it.diecast.name == "Nissan Skyline GT-R" }
        assertEquals(listOf(seeded.ownItem.id, seeded.ownItem.id), skylines.map { it.diecast.catalogItemId })
    }

    @Test
    fun `merging adds the entries an own dex of the same name lacks`() = runBlocking {
        seed()
        val zip = backup()
        val custom = DexCustom(other.db, DexMatcher(other.db.dex()), other.photos, other.root)
        val ownId = custom.create("Tomica", "tomica premium", "")
        custom.addOne(ownId, DexRow(number = "01", name = "Honda NSX"), null)

        importer.import(zip, replace = false)

        val customs = other.db.dex().allCatalogs().filter { it.source == DexMatcher.CUSTOM }
        assertEquals(listOf(ownId), customs.map { it.id })
        val items = other.db.dex().items(ownId)
        assertEquals(listOf("Honda NSX" to 1, "Nissan Skyline GT-R" to 2), items.map { it.name to it.variant })
        assertEquals(items[1].id, byName(other, "Nissan Skyline GT-R").diecast.catalogItemId)
    }

    @Test
    fun `replacing swaps the own dexes too, a web backup leaves them`() = runBlocking {
        seed()
        val zip = backup()
        val custom = DexCustom(other.db, DexMatcher(other.db.dex()), other.photos, other.root)
        val oldId = custom.create("Majorette", "Majorette Premium", "")
        custom.addOne(oldId, DexRow(number = "1", name = "Porsche 911"), other.pendingPhoto())
        other.repository.save(DiecastEntity(name = "Will be replaced"))

        importer.import(zip, replace = true)

        assertEquals(listOf("Tomica Premium"), other.db.dex().allCatalogs().filter { it.source == DexMatcher.CUSTOM }.map { it.name })
        assertFalse(File(other.root, "dex/$oldId").exists())
        assertEquals(3, other.db.diecasts().allDetails().size)

        // The web's backup has no Dex: the own dexes stay.
        val web = File(other.root, "web.zip").apply {
            writeBytes(BackupExporterTest::class.java.getResourceAsStream("/web-backup-v1.zip")!!.readBytes())
        }
        importer.import(web, replace = true)
        assertEquals(listOf("Tomica Premium"), other.db.dex().allCatalogs().filter { it.source == DexMatcher.CUSTOM }.map { it.name })
    }

    @Test
    fun `the Excel export has the web's columns`() = runBlocking {
        seed()
        val out = ByteArrayOutputStream()
        val summary = exporter.writeExcel(out)

        assertEquals(3, summary.diecasts)
        val rows = SheetReader.read(out.toByteArray())
        assertEquals(CollectionSheet.HEADERS, rows[0])
        val datsun = rows.single { it[0] == "'71 Datsun 510" }
        assertEquals(
            listOf(
                "'71 Datsun 510", "Hot Wheels", "", "HW J-Imports", "61/250 · 2/10", "2026", "1:64", "Blue", "Treasure Hunt",
                "Loose", "", "Dimiliki", "2", "35000", "150000", "", "2026-10-01", "Toys Kingdom", "", "Favorit, JDM",
                "Kartu \"mulus\" & <rapi>",
            ),
            datsun,
        )
        assertEquals("Wishlist", rows.single { it[0] == "Mystery car" }[11])
    }

    @Test
    fun `saving a backup reports what it wrote and restarts the reminder`() = runBlocking {
        seed()
        val file = File(env.root, "saved.zip")

        exporter.save(ExportKind.Backup, Uri.fromFile(file))
        val state = exporter.state.first { it !is ExportState.Running }

        state as ExportState.Finished
        assertEquals(ExportSummary(diecasts = 3, photos = 3, sightingPhotos = 1, dexes = 2), state.summary)
        assertEquals(file.length(), state.bytes)
        assertEquals("backup-diecast-2026-10-21.zip", state.fileName)
        assertFalse(state.forSharing)
        assertEquals(NOW, backedUpAt)
        assertEquals(3, importer.import(file, replace = false).imported)
    }

    @Test
    fun `xlsx columns are lettered like Excel`() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "ZZ", "AAA"), listOf(0, 25, 26, 51, 701, 702).map(XlsxWriter::column))
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.content

    private companion object {
        val NOW = Instant.parse("2026-10-21T12:00:00Z").toEpochMilli()
        val CREATED = Instant.parse("2026-10-02T08:30:00Z").toEpochMilli()
        val VALUED = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
        val SYNCED = Instant.parse("2026-10-19T09:15:00Z").toEpochMilli()

        /** `Diecast::$fillable` in the web app. */
        val WEB_FIELDS = listOf(
            "name", "brand", "make", "series", "collection_number", "year", "scale", "color", "rarity", "packaging",
            "condition", "status", "quantity", "purchase_price", "market_value", "target_price", "purchase_date",
            "purchase_place", "purchase_latitude", "purchase_longitude", "storage_location", "notes", "ai_scanned",
        )
    }
}
