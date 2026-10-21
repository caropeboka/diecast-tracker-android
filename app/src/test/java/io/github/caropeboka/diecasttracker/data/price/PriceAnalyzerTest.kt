package io.github.caropeboka.diecasttracker.data.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Web: `tests/Unit/PriceAnalyzerTest.php`. */
class PriceAnalyzerTest {
    private var n = 0

    private fun listing(price: Long, match: ListingMatch = ListingMatch.Match, sold: Int? = null): PriceListing {
        n++
        return PriceListing(title = "Hot Wheels Dodge Dart $n", price = price, shop = "Toko $n", match = match, sold = sold)
    }

    @Test
    fun `median counts only matching listings`() {
        val result = PriceAnalyzer.analyze(
            listOf(
                listing(30_000),
                listing(40_000),
                listing(50_000),
                listing(900_000, ListingMatch.DifferentVariant),
                listing(10_000, ListingMatch.DifferentCondition),
            ),
        )

        val stats = result.stats!!
        assertEquals(3, stats.count)
        assertEquals(40_000L, stats.median)
        assertEquals(30_000L, stats.min)
        assertEquals(50_000L, stats.max)
        assertEquals("Varian berbeda", result.listings.first { it.price == 900_000L }.excludedReason)
        // Counted first, cheapest first; then the rest.
        assertEquals(listOf(30_000L, 40_000L, 50_000L, 10_000L, 900_000L), result.listings.map { it.price })
    }

    @Test
    fun `even count median and suggested rounding`() {
        val stats = PriceAnalyzer.analyze(listOf(listing(35_000), listing(36_500), listing(38_000), listing(40_000))).stats!!

        assertEquals(37_250L, stats.median)
        assertEquals(37_500L, stats.suggested)
    }

    @Test
    fun `extreme prices are marked as outliers`() {
        val result = PriceAnalyzer.analyze(
            listOf(listing(40_000), listing(42_000), listing(45_000), listing(47_000), listing(50_000), listing(450_000)),
        )

        val outlier = result.listings.first { it.price == 450_000L }
        assertEquals(ListingStatus.Outlier, outlier.status)
        assertEquals("Harga jauh di atas yang lain", outlier.excludedReason)
        assertEquals(5, result.stats!!.count)
        assertEquals(45_000L, result.stats!!.median)
    }

    @Test
    fun `duplicates from overlapping screenshots count once`() {
        val same = PriceListing(title = "Hot Wheels  Dodge Dart HW Flames", price = 40_000, shop = "Garasi Kecil", match = ListingMatch.Match)

        val result = PriceAnalyzer.analyze(listOf(same, same.copy(title = "hot wheels dodge dart hw flames"), listing(45_000)))

        assertEquals(2, result.listings.size)
    }

    @Test
    fun `many consistent sold listings give high accuracy`() {
        val listings = listOf(40_000L, 41_000L, 42_000L, 42_500L, 43_000L, 44_000L, 45_000L, 46_000L).map { listing(it, sold = 25) }

        assertEquals(PriceAccuracy.High, PriceAnalyzer.analyze(listings).accuracy)
    }

    @Test
    fun `few scattered listings give low accuracy with tips`() {
        val result = PriceAnalyzer.analyze(listOf(listing(20_000), listing(90_000)))

        assertEquals(PriceAccuracy.Low, result.accuracy)
        assertTrue(result.tips.isNotEmpty())
        assertTrue(result.reasons.first().startsWith("2 listing cocok dihitung"))
    }

    @Test
    fun `single or no match is insufficient`() {
        assertEquals(PriceAccuracy.Insufficient, PriceAnalyzer.analyze(listOf(listing(40_000))).accuracy)

        val none = PriceAnalyzer.analyze(listOf(listing(40_000, ListingMatch.DifferentItem)))
        assertEquals(PriceAccuracy.Insufficient, none.accuracy)
        assertNull(none.stats)
        assertEquals(listOf("Tidak ada listing yang cocok dengan diecast ini."), none.reasons)
    }
}
