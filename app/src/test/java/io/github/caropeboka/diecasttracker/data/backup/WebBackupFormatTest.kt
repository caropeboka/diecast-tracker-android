package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.backup.WebBackupFormat.Record
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.zip.ZipInputStream

class WebBackupFormatTest {
    private val today = LocalDate.of(2026, 10, 18)

    /** diecasts.json of the fixture made by the web app's own CollectionBackup (tools/make-web-backup-fixture.php). */
    private val manifest by lazy {
        val zip = ZipInputStream(javaClass.getResourceAsStream("/web-backup-v1.zip")!!)
        generateSequence { zip.nextEntry }.first { it.name == "diecasts.json" }
        WebBackupFormat.parseManifest(zip.readBytes().decodeToString())
    }

    private fun parse(json: String): Record = WebBackupFormat.parseRecord(0, Json.parseToJsonElement(json), today)

    private fun record(extra: String = "") =
        """{"name": "Datsun", "rarity": "regular", "packaging": "carded", "status": "owned", "quantity": 1 $extra}"""

    @Test
    fun `reads a backup made by the web app`() {
        assertEquals(LocalDate.of(2026, 10, 18), manifest.exportedOn)
        assertEquals(9, manifest.records.size)

        val datsun = WebBackupFormat.parseRecord(0, manifest.records[0], today) as Record.Valid
        with(datsun.diecast) {
            assertEquals("'71 Datsun 510", name)
            assertEquals("61/250 · 2/10", collectionNumber)
            assertEquals(Rarity.TreasureHunt, rarity)
            assertEquals(Condition.Mint, condition)
            assertEquals(35_000L, purchasePrice)
            assertEquals(150_000L, marketValue)
            assertEquals(LocalDate.of(2026, 9, 1), purchaseDate)
            assertEquals(-6.1951, purchaseLatitude!!, 0.0)
            assertEquals(106.8209, purchaseLongitude!!, 0.0)
            assertTrue(aiScanned)
        }
        assertEquals(listOf("Favorit", "JDM"), datsun.tags)
        assertEquals(2, datsun.photos.size)

        val porsche = manifest.records.mapIndexed { i, it -> WebBackupFormat.parseRecord(i, it, today) }
            .filterIsInstance<Record.Valid>()
            .single { it.diecast.status == DiecastStatus.Spotted }
        assertEquals(listOf(55_000L, 45_000L), porsche.sightings.map { it.entity.price })
        assertTrue(porsche.sightings.any { it.photo != null })
    }

    @Test
    fun `invalid records are skipped with a reason`() {
        val reasons = manifest.records.mapIndexed { i, it -> WebBackupFormat.parseRecord(i, it, today) }
            .filterIsInstance<Record.Invalid>()
            .map { it.reason }

        assertEquals(
            listOf("Item 7 (?): Nama wajib diisi.", "Item 8 (Ford Model T): Tahun harus antara 1950 dan 2027."),
            reasons,
        )
    }

    @Test
    fun `other files are not backups`() {
        listOf("""{"format": "something-else", "diecasts": []}""", """{"format": "diecast-tracker-backup"}""", "not json")
            .forEach { assertThrows(BackupException::class.java) { WebBackupFormat.parseManifest(it) } }
    }

    @Test
    fun `money is rounded to whole rupiah`() {
        val valid = parse(record(""", "purchase_price": "35000.50", "market_value": 99000""")) as Record.Valid
        assertEquals(35_001L, valid.diecast.purchasePrice)
        assertEquals(99_000L, valid.diecast.marketValue)

        assertEquals(
            Record.Invalid("Item 1 (Datsun): Harga beli harus antara 0 dan 999.999.999.999."),
            parse(record(""", "purchase_price": -5""")),
        )
    }

    @Test
    fun `required fields and value lists are checked`() {
        assertEquals(
            Record.Invalid("Item 1 (Datsun): Jumlah wajib diisi."),
            parse("""{"name": "Datsun", "rarity": "regular", "packaging": "carded", "status": "owned"}"""),
        )
        assertEquals(
            Record.Invalid("Item 1 (Datsun): Kelangkaan tidak dikenal: \"ultra\"."),
            parse(record().replace("\"regular\"", "\"ultra\"")),
        )
        assertEquals(
            Record.Invalid("Item 1 (Datsun): Koordinat tempat beli tidak lengkap."),
            parse(record(""", "purchase_latitude": -6.2""")),
        )
        assertEquals(
            Record.Invalid("Item 1 (Datsun): Tag maksimal 50 karakter."),
            parse(record(""", "tags": ["${"x".repeat(51)}"]""")),
        )
    }

    @Test
    fun `blank text becomes empty and missing scale stays empty`() {
        val valid = parse(record(""", "brand": "  ", "scale": null""")) as Record.Valid
        assertNull(valid.diecast.brand)
        assertNull(valid.diecast.scale)
    }

    @Test
    fun `sightings in the future or without a date are left out`() {
        val sightings: JsonElement = Json.parseToJsonElement(
            """[
                {"place": "Toko", "seen_on": "2026-10-01", "price": "45000.00"},
                {"place": "Besok", "seen_on": "2026-10-19"},
                {"place": "Tanpa tanggal"}
            ]""",
        )
        val valid = parse(record(""", "sightings": $sightings""")) as Record.Valid

        assertEquals(listOf("Toko"), valid.sightings.map { it.entity.place })
        assertEquals(45_000L, valid.sightings.single().entity.price)
    }
}
