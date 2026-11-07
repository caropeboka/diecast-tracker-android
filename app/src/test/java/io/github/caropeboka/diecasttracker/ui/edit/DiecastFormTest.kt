package io.github.caropeboka.diecasttracker.ui.edit

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.testTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class DiecastFormTest {
    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `a new owned diecast was bought today, a wish was not`() {
        assertEquals("2026-10-18", DiecastForm.new(DiecastStatus.Owned, today).purchaseDate)
        assertNull(DiecastForm.new(DiecastStatus.Wishlist, today).purchaseDate)

        val wish = DiecastForm.new(DiecastStatus.Wishlist, today)
        assertEquals("2026-10-18", wish.withStatus(DiecastStatus.Owned, isNew = true, today).purchaseDate)
        assertNull(wish.withStatus(DiecastStatus.Owned, isNew = false, today).purchaseDate)
    }

    @Test
    fun `entries outside the garage keep no purchase details`() {
        val form = DiecastForm(
            name = " Datsun ",
            purchasePrice = "35000",
            marketValue = "150000",
            targetPrice = "500000",
            purchaseDate = "2026-09-01",
            purchasePlace = "Toys Kingdom",
            purchaseLatitude = -6.2,
            purchaseLongitude = 106.8,
            storageLocation = "Rak A",
        )

        val wish = form.copy(status = DiecastStatus.Wishlist).toEntity(null)
        assertEquals("Datsun", wish.name)
        assertNull(wish.purchasePrice)
        assertNull(wish.purchaseDate)
        assertNull(wish.purchasePlace)
        assertNull(wish.purchaseLatitude)
        assertNull(wish.storageLocation)
        assertEquals(500_000L, wish.targetPrice)
        assertEquals(150_000L, wish.marketValue)

        val owned = form.toEntity(null)
        assertEquals(35_000L, owned.purchasePrice)
        assertEquals(LocalDate.of(2026, 9, 1), owned.purchaseDate)
        assertEquals("Rak A", owned.storageLocation)
        assertNull(owned.targetPrice)
    }

    @Test
    fun `validation mirrors the web form rules`() {
        val errors = DiecastForm(name = " ", year = "1900", quantity = "0", sightingDate = "2026-10-19").validate(today, testTexts())

        assertEquals("Nama model wajib diisi.", errors[Field.Name])
        assertEquals("Tahun antara 1950 dan 2027.", errors[Field.Year])
        assertEquals("Jumlah 1 sampai 9999.", errors[Field.Quantity])
        assertEquals("Tanggal tidak boleh setelah hari ini.", errors[Field.SightingDate])

        // Ditemui entries have no quantity field.
        assertTrue(Field.Quantity !in DiecastForm(name = "X", status = DiecastStatus.Spotted, quantity = "").validate(today, testTexts()))
        assertTrue(DiecastForm(name = "X", year = "2027").validate(today, testTexts()).isEmpty())
    }

    @Test
    fun `a scan fills what it read and marks it, keeping the rest`() {
        val form = DiecastForm(name = "Typed", series = "Mine", status = DiecastStatus.Wishlist, targetPrice = "50000", rarity = Rarity.Chase)
        val fields = ScanFields(
            name = "'71 Datsun 510",
            brand = "Hot Wheels",
            year = 2024,
            rarity = Rarity.Regular,
            packaging = Packaging.Boxed,
            condition = Condition.Mint,
        )

        val (next, filled) = form.withScan(fields)

        assertEquals("'71 Datsun 510", next.name)
        assertEquals("Hot Wheels", next.brand)
        assertEquals("Mine", next.series)
        assertEquals("2024", next.year)
        assertEquals(Rarity.Regular, next.rarity)
        assertEquals(Packaging.Boxed, next.packaging)
        assertEquals(Condition.Mint, next.condition)
        assertEquals("50000", next.targetPrice)
        assertTrue(next.aiScanned)
        // "Regular" is the default, not a finding.
        assertEquals(setOf(Field.Name, Field.Brand, Field.Year, Field.Packaging, Field.Condition), filled)
        assertTrue(Field.Rarity in form.withScan(ScanFields(rarity = Rarity.TreasureHunt)).second)
    }

    @Test
    fun `only a new diecast takes its dex entry from the form`() {
        val form = DiecastForm(name = "Mazda RX-7", catalogItemId = 7)

        val added = form.toEntity(null)
        assertEquals(7L, added.catalogItemId)
        assertEquals(CatalogMatch.Confirmed, added.catalogMatch)
        assertNull(form.copy(catalogItemId = null).toEntity(null).catalogMatch)

        // A saved one's link is changed right away from its Dex panel, never by saving the form.
        val saved = DiecastEntity(id = 3, name = "Mazda RX-7", catalogItemId = 9, catalogMatch = CatalogMatch.Auto)
        assertEquals(9L, form.toEntity(saved).catalogItemId)
        assertEquals(CatalogMatch.Auto, form.toEntity(saved).catalogMatch)
    }

    @Test
    fun `an AI suggestion goes in only when it fits the field and changes something`() {
        val form = DiecastForm(name = "Toyota Supra", year = "2019")

        assertEquals(form.copy(year = "2021") to Field.Year, form.withSuggestion("year", " 2021 ", 2026))
        assertEquals(form.copy(rarity = Rarity.TreasureHunt) to Field.Rarity, form.withSuggestion("rarity", "treasure_hunt", 2026))
        assertEquals(form.copy(scale = "1:18") to Field.Scale, form.withSuggestion("scale", "1 : 18", 2026))
        assertNull(form.withSuggestion("year", "2019", 2026))
        assertNull(form.withSuggestion("year", "2031", 2026))
        assertNull(form.withSuggestion("rarity", "ultra_rare", 2026))
        assertNull(form.withSuggestion("scale", "big", 2026))
        assertNull(form.withSuggestion("purchase_price", "35000", 2026))
        assertEquals("2019", form.chatFields()["year"])
        assertEquals("regular", form.chatFields()["rarity"])
    }

    @Test
    fun `answers keep their bold words, headings and lists without the marks`() {
        val text = chatText("## Rilis\nKartu **2019**, rilis **2021**.\n* Seri: HW J-Imports\n- a lone ** stays")

        assertEquals("Rilis\nKartu 2019, rilis 2021.\n• Seri: HW J-Imports\n• a lone ** stays", text.text)
        val bold = text.spanStyles.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("Rilis", "2019", "2021"), bold)
    }
}
