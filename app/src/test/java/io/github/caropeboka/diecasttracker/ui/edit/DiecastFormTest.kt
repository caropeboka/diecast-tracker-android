package io.github.caropeboka.diecasttracker.ui.edit

import io.github.caropeboka.diecasttracker.data.DiecastStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
        val errors = DiecastForm(name = " ", year = "1900", quantity = "0", sightingDate = "2026-10-19").validate(today)

        assertEquals("Nama model wajib diisi.", errors[Field.Name])
        assertEquals("Tahun antara 1950 dan 2027.", errors[Field.Year])
        assertEquals("Jumlah 1 sampai 9999.", errors[Field.Quantity])
        assertEquals("Tanggal tidak boleh setelah hari ini.", errors[Field.SightingDate])

        // Ditemui entries have no quantity field.
        assertTrue(Field.Quantity !in DiecastForm(name = "X", status = DiecastStatus.Spotted, quantity = "").validate(today))
        assertTrue(DiecastForm(name = "X", year = "2027").validate(today).isEmpty())
    }
}
