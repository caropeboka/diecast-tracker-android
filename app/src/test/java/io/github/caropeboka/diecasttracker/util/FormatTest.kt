package io.github.caropeboka.diecasttracker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test
    fun `rupiah has dots and no decimals`() {
        assertEquals("Rp 35.000", formatRupiah(35_000))
        assertEquals("Rp 1.250.000", formatRupiah(1_250_000))
        assertEquals("Rp 0", formatRupiah(0))
        assertEquals("-", formatRupiah(null))
    }

    @Test
    fun `compact rupiah matches the web's id-ID compact notation`() {
        assertEquals("Rp 500", formatRupiahCompact(500))
        assertEquals("Rp 35 rb", formatRupiahCompact(35_000))
        assertEquals("Rp 54,5 rb", formatRupiahCompact(54_500))
        assertEquals("Rp 1,2 jt", formatRupiahCompact(1_200_000))
        assertEquals("Rp 1 jt", formatRupiahCompact(999_950))
        assertEquals("Rp 1,5 M", formatRupiahCompact(1_500_000_000))
    }

    @Test
    fun `dates use Indonesian month names`() {
        assertEquals("18 Okt 2026", formatDate(LocalDate.of(2026, 10, 18)))
        assertEquals("1 Sep 2026", formatDate(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `places sort naturally and ignore case`() {
        assertEquals(
            listOf("Alfamart", "Toko 2", "toko 10", "Toys Kingdom"),
            listOf("toko 10", "Toys Kingdom", "Toko 2", "Alfamart").sortedWith(naturalCaseInsensitiveOrder),
        )
    }
}
