package io.github.caropeboka.diecasttracker.data.price

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.ai.AiSetup
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.ProviderSettings
import io.github.caropeboka.diecasttracker.data.ai.WebSource
import io.github.caropeboka.diecasttracker.data.ai.vision
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** For diecasts the usual Tokopedia/Shopee search misses: eBay in dollars, other keywords, the web, own records. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class PriceAlternativesTest {
    private val env = TestEnvironment(now = { 1_000L })
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private var rateCalls = 0
    private val rates = ExchangeRates(usd = 16_000.0, cny = 2_250.0, date = "2026-11-06", source = "test", fetchedAt = 0, others = mapOf("GBP" to 21_000.0))
    private fun checker(setup: AiSetup = AiSetup.gemini("test-key")) = PriceChecker(
        fake.vision(MutableStateFlow(setup)),
        env.db,
        env.texts,
        rates = {
            rateCalls++
            rates
        },
        clock = { 5_000L },
    )

    @After
    fun tearDown() = env.close()

    private suspend fun diecast(name: String = "Nissan Skyline GT-R (BNR34)", brand: String = "Mini GT", number: String? = "MGT00045", status: DiecastStatus = DiecastStatus.Owned, price: Long? = null, bought: LocalDate? = null) =
        env.db.diecasts().get(env.repository.save(DiecastEntity(name = name, brand = brand, make = "Nissan", collectionNumber = number, status = status, purchasePrice = price, purchaseDate = bought, purchasePlace = bought?.let { "Toys Kingdom" })))!!

    @Test
    fun `eBay prices are converted at today's rate, one without a rate doesn't count`() = runBlocking {
        fake.otherwise(
            200,
            FakeGemini.answer(
                """{"listings": [
                    {"title": "Mini GT Skyline R34", "price": 12.5, "currency": "USD", "marketplace": "ebay", "match": "match", "reason": "ok", "is_price_range": false, "is_ad": false},
                    {"title": "Mini GT R34 Bayside Blue", "price": 10, "currency": "GBP", "marketplace": "ebay", "match": "match", "reason": "ok", "is_price_range": false, "is_ad": false},
                    {"title": "Mini GT R34", "price": 9, "currency": "CHF", "marketplace": "ebay", "match": "match", "reason": "ok", "is_price_range": false, "is_ad": false},
                    {"title": "MGT R34 Tokopedia", "price": 150000, "currency": "IDR", "marketplace": "tokopedia", "match": "match", "reason": "ok", "is_price_range": false, "is_ad": false}
                ], "note": null, "search_hint": null}""",
            ),
        )

        val check = checker().check(diecast(), listOf(TestEnvironment.fixtureJpeg))

        val byTitle = check.listings.associateBy { it.title }
        assertEquals(200_000L, byTitle.getValue("Mini GT Skyline R34").price)
        assertEquals("USD", byTitle.getValue("Mini GT Skyline R34").currency)
        assertEquals(12.5, byTitle.getValue("Mini GT Skyline R34").originalPrice!!, 0.001)
        assertEquals(210_000L, byTitle.getValue("Mini GT R34 Bayside Blue").price)
        assertEquals(ListingStatus.Excluded, byTitle.getValue("Mini GT R34").status)
        assertTrue("CHF" in byTitle.getValue("Mini GT R34").reason!!)
        assertNull(byTitle.getValue("MGT R34 Tokopedia").currency)
        assertEquals(3, check.stats!!.count)
        assertTrue(check.tips.any { "eBay" in it })
        assertEquals(1, rateCalls)
    }

    @Test
    fun `Rupiah only needs no rates`() = runBlocking {
        fake.otherwise(200, FakeGemini.answer("""{"listings": [{"title": "R34", "price_idr": 150000, "marketplace": "tokopedia", "match": "match", "reason": "ok", "is_price_range": false, "is_ad": false}], "note": null, "search_hint": null}"""))
        assertEquals(150_000L, checker().check(diecast(), listOf(TestEnvironment.fixtureJpeg)).stats!!.median)
        assertEquals(0, rateCalls)
    }

    @Test
    fun `other keywords - the obvious ones at once, more from the AI without photos`() = runBlocking {
        val r34 = diecast()
        assertEquals(PriceChecker.searchQuery(r34), "Mini GT Nissan Skyline GT-R (BNR34)")
        assertEquals(listOf("Nissan Skyline GT-R (BNR34)", "MGT00045"), PriceChecker.keywordVariants(r34))

        fake.otherwise(200, FakeGemini.answer("""{"keywords": ["mini gt r34", "MGT00045", "Mini GT Nissan Skyline GT-R (BNR34)", "mini gt bnr34 bayside blue", "mini gt r34"]}"""))
        assertEquals(listOf("mini gt r34", "MGT00045", "mini gt bnr34 bayside blue"), checker().keywords(r34))
        assertFalse("inline_data" in fake.bodies.last())

        assertEquals("https://www.ebay.com/sch/i.html?_nkw=mini+gt+r34&LH_Sold=1&LH_Complete=1", Marketplace.EbaySold.search(" mini gt r34 "))
        assertEquals("https://www.lazada.co.id/catalog/?q=R34+%26+AE86", Marketplace.Lazada.search("R34 & AE86"))
        assertEquals("https://www.google.com/search?udm=28&gl=id&q=mini+gt+r34", Marketplace.GoogleShopping.search("mini gt r34"))
        // OLX wants its words in the path, lower case and joined by dashes, inside the hobby category.
        assertEquals("https://www.olx.co.id/koleksi-mainan-hobi_c221/q-hot-wheels-gt-r", Marketplace.Olx.search(" Hot Wheels  GT-R "))
        assertEquals("https://id.carousell.com/search/mini%20gt%20r34", Marketplace.Carousell.search("mini gt r34"))
        assertEquals(
            "https://auctions.yahoo.co.jp/closedsearch/closedsearch?p=%E3%83%88%E3%83%9F%E3%82%AB+R34",
            Marketplace.YahooAuctionsSold.search("トミカ R34"),
        )
        assertEquals("https://jp.mercari.com/search?keyword=tomica+r34&status=sold_out", Marketplace.MercariSold.search("tomica r34"))
        assertEquals(listOf(MarketGroup.Shops, MarketGroup.Preloved, MarketGroup.SoldAbroad), Marketplace.entries.map { it.group }.distinct())
    }

    @Test
    fun `a rough estimate from the web, with its sources, can become the market value`() = runBlocking {
        val r34 = diecast()
        fake.otherwise(
            200,
            buildJsonObject {
                putJsonArray("candidates") {
                    addJsonObject {
                        putJsonObject("content") {
                            putJsonArray("parts") {
                                addJsonObject { put("text", "Di Tokopedia sekitar Rp150.000–Rp200.000.\n\n```estimate\n{\"low\": 150000, \"typical\": 175000.4, \"high\": 200000}\n```") }
                            }
                        }
                        putJsonObject("groundingMetadata") {
                            putJsonArray("groundingChunks") {
                                addJsonObject { putJsonObject("web") { put("uri", "https://example.test/r34"); put("title", "tokopedia.com") } }
                            }
                        }
                    }
                }
            }.toString(),
        )

        val estimate = checker().estimate(r34)

        assertEquals(Triple(150_000L, 175_000L, 200_000L), Triple(estimate.low, estimate.typical, estimate.high))
        assertEquals("Di Tokopedia sekitar Rp150.000–Rp200.000.", estimate.text)
        assertEquals(listOf(WebSource("tokopedia.com", "https://example.test/r34")), estimate.sources)
        assertTrue(estimate.searched)
        assertTrue("google_search" in fake.bodies.last())

        val updated = checker().applyEstimate(r34.id, estimate.typical!!)
        assertEquals(175_000L, updated.marketValue)
        assertEquals(ValueSource.AiEstimate, updated.marketValueSource)
    }

    @Test
    fun `an estimate without anything to go on, and one from a provider that can't search`() = runBlocking {
        assertEquals("Nothing reliable." to null, PriceChecker.splitEstimate("Nothing reliable.\n```estimate\n{\"low\": null, \"typical\": null, \"high\": null}\n```"))
        assertEquals(Triple(40_000L, 40_000L, 40_000L), PriceChecker.splitEstimate("```json\n{\"low\": 90000, \"typical\": 40000}\n```").second)

        val groq = AiSetup(listOf(ProviderSettings(AiProvider.Groq, "gsk-test")))
        fake.on("api.groq.com/openai/v1/chat/completions", 200, """{"choices":[{"message":{"role":"assistant","content":"Sekitar Rp100.000.\n```estimate\n{\"low\": 80000, \"typical\": 100000, \"high\": 120000}\n```"}}]}""")
        val estimate = checker(groq).estimate(diecast())
        assertEquals(100_000L, estimate.typical)
        assertFalse(estimate.searched)
        assertEquals("Groq", estimate.by)
    }

    @Test
    fun `own records - what was paid and seen on shelves, for this one and its look-alikes`() = runBlocking {
        val r34 = diecast(price = 150_000, bought = LocalDate.of(2026, 9, 1))
        val twin = diecast(name = "Nissan Skyline GT-R R34", number = null, status = DiecastStatus.Spotted)
        val other = diecast(name = "Toyota Supra", number = "MGT00100", price = 999_000, bought = LocalDate.of(2026, 9, 2))
        env.repository.addSighting(SightingEntity(diecastId = twin.id, place = "Pasar Baru", seenOn = LocalDate.of(2026, 10, 5), price = 180_000))
        env.repository.addSighting(SightingEntity(diecastId = r34.id, place = "Toys Kingdom", seenOn = LocalDate.of(2026, 10, 20), price = 165_000))
        env.repository.addSighting(SightingEntity(diecastId = other.id, seenOn = LocalDate.of(2026, 10, 21), price = 500_000))

        val own = OwnPriceFinder(env.db, CollectionMatcher(env.repository)).find(r34)

        assertEquals(listOf(165_000L, 180_000L, 150_000L), own.prices.map { it.price })
        assertEquals(listOf(false, false, true), own.prices.map { it.bought })
        assertEquals(listOf(null, twin.name, null), own.prices.map { it.other })
        assertEquals(165_000L, own.median)
        assertEquals(165_000L, OwnPriceFinder.median(listOf(150_000, 180_000, 165_000)))
        assertEquals(157_500L, OwnPriceFinder.median(listOf(150_000, 165_000)))
    }
}
