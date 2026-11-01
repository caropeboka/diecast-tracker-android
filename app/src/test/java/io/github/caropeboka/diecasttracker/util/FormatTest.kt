package io.github.caropeboka.diecasttracker.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Indonesian, as on the web, unless a test says otherwise. */
class FormatTest {
    @Before
    fun indonesian() {
        Formats.locale = INDONESIAN
    }

    @After
    fun reset() {
        Formats.locale = Locale.ENGLISH
        Formats.currency = Currency.IDR
        Formats.rates = null
        Formats.dateStyle = DateStyle.Language
    }

    @Test
    fun `rupiah has dots and no decimals`() {
        assertEquals("Rp 35.000", formatMoney(35_000))
        assertEquals("Rp 1.250.000", formatMoney(1_250_000))
        assertEquals("Rp 0", formatMoney(0))
        assertEquals("-", formatMoney(null))
    }

    @Test
    fun `compact rupiah matches the web's id-ID compact notation`() {
        assertEquals("Rp 500", formatMoneyCompact(500))
        assertEquals("Rp 35 rb", formatMoneyCompact(35_000))
        assertEquals("Rp 54,5 rb", formatMoneyCompact(54_500))
        assertEquals("Rp 1,2 jt", formatMoneyCompact(1_200_000))
        assertEquals("Rp 1 jt", formatMoneyCompact(999_950))
        assertEquals("Rp 1,5 M", formatMoneyCompact(1_500_000_000))
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

    @Test
    fun `english and chinese group with commas and have a decimal point`() {
        Formats.locale = Locale.ENGLISH
        assertEquals("Rp 1,250,000", formatMoney(1_250_000))
        assertEquals("Rp 54.5K", formatMoneyCompact(54_500))
        assertEquals("18 Oct 2026", formatDate(LocalDate.of(2026, 10, 18)))
        assertEquals("12.3 MB", formatBytes(12_900_000L))

        Formats.locale = Locale.SIMPLIFIED_CHINESE
        assertEquals("Rp 5.5万", formatMoneyCompact(54_500))
        assertEquals("2026年10月18日", formatDate(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `dollars and yuan are shown at the day's rate, rupiah until there is one`() {
        Formats.locale = Locale.ENGLISH
        Formats.currency = Currency.USD
        assertEquals("Rp 32,000", formatMoney(32_000))

        Formats.rates = ExchangeRates(usd = 16_000.0, cny = 2_250.0, date = "2026-11-01", source = "test", fetchedAt = 0)
        assertEquals("$2.00", formatMoney(32_000))
        assertEquals("$1.2K", formatMoneyCompact(19_200_000))
        assertEquals(40_000L, convertToIdr(2.5))

        Formats.currency = Currency.CNY
        assertEquals("¥2.00", formatMoney(4_500))
        assertEquals(4_500L, convertToIdr(2.0))
    }

    @Test
    fun `a chosen date style replaces the language's`() {
        val date = LocalDate.of(2026, 10, 18)
        assertEquals("2026-10-18", formatDate(date, DateStyle.Iso))
        assertEquals("18-10-2026", formatDate(date, DateStyle.DayMonthYear))
        assertEquals("18/10/2026", formatDate(date, DateStyle.DaySlash))
        assertEquals("10/18/2026", formatDate(date, DateStyle.MonthDayYear))

        Formats.dateStyle = DateStyle.Iso
        assertEquals("10-18", formatDayMonth(date))
    }

    @Test
    fun `file sizes use a decimal comma`() {
        assertEquals(
            listOf("512 B", "1 KB", "850 KB", "12,3 MB", "250 MB", "1,5 GB"),
            listOf(512L, 1024L, 850L * 1024, 12_900_000L, 262_144_000L, 1_610_612_736L).map(::formatBytes),
        )
    }
}
