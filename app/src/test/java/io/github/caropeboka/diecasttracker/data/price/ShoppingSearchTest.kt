package io.github.caropeboka.diecasttracker.data.price

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ai.DEFAULT_GEMINI_MODEL
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.vision
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The automatic price check: Google Shopping through SerpApi, the AI sorting the listings out by their titles. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class ShoppingSearchTest {
    private val env = TestEnvironment(now = { 1_000L })
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val key = MutableStateFlow<String?>("serp-key")
    private val shopping = ShoppingSearch(fake.client, key, env.texts)
    private val rates = ExchangeRates(usd = 16_000.0, cny = 2_250.0, date = "2026-11-15", source = "test", fetchedAt = 0)
    private val checker = PriceChecker(fake.vision(), env.db, env.texts, rates = { rates }, clock = { 5_000L }, shopping = shopping)

    @After
    fun tearDown() = env.close()

    private suspend fun diecast() = env.db.diecasts().get(
        env.repository.save(DiecastEntity(name = "Nissan Skyline GT-R (R34)", brand = "Hot Wheels", series = "HW J-Imports", packaging = Packaging.Carded)),
    )!!

    private fun results(vararg rows: String) = """{"search_metadata": {"status": "Success"}, "shopping_results": [${rows.joinToString(",")}]}"""

    private fun row(title: String, price: String, extracted: Number?, source: String = "Tokopedia", link: String? = "https://www.google.com/shopping/product/1") =
        """{"title": "$title", "price": "$price", ${extracted?.let { "\"extracted_price\": $it," }.orEmpty()} "source": "$source"${link?.let { ", \"product_link\": \"$it\"" }.orEmpty()}}"""

    @Test
    fun `an automatic check searches Google Shopping and counts what the AI says is this diecast`() = runBlocking {
        fake.on(
            "serpapi.com/search.json",
            200,
            results(
                row("Hot Wheels Nissan Skyline GT-R R34 J-Imports", "Rp 45.000", 45000),
                row("Hot Wheels Skyline R34 HW J Imports ori", "Rp 50.000", 50000, source = "Shopee"),
                row("Hot Wheels Skyline R34 Super Treasure Hunt", "Rp 300.000", 300000),
                row("Hot Wheels Nissan Skyline R34 J-Imports", "US$3.50", 3.5, source = "eBay"),
                row("Hot Wheels R34 price on request", "Hubungi penjual", null),
            ),
        )
        fake.on(
            DEFAULT_GEMINI_MODEL,
            200,
            FakeGemini.answer(
                """{"listings": [
                    {"index": 0, "match": "match", "reason": "sama"},
                    {"index": 1, "match": "match", "reason": "sama"},
                    {"index": 2, "match": "different_variant", "reason": "STH"},
                    {"index": 3, "match": "match", "reason": "sama"}
                ], "note": "Kebanyakan kartu J-Imports.", "search_hint": "hot wheels skyline gt-r r34"}""",
            ),
        )
        var found = -1
        val target = diecast()

        val check = checker.checkOnline(target, "Hot Wheels Skyline GT-R R34", onFound = { found = it })

        assertEquals("the one without a price is left out", 4, found)
        assertEquals(0, check.screenshots)
        val stats = check.stats!!
        assertEquals(3, stats.count)
        assertEquals(50_000L, stats.median)
        val byTitle = check.listings.associateBy { it.title }
        val dollars = byTitle.getValue("Hot Wheels Nissan Skyline R34 J-Imports")
        assertEquals(56_000L, dollars.price)
        assertEquals("USD", dollars.currency)
        assertEquals(ListingStatus.Excluded, byTitle.getValue("Hot Wheels Skyline R34 Super Treasure Hunt").status)
        assertTrue(check.listings.all { it.marketplace == "google" && it.link == "https://www.google.com/shopping/product/1" })
        assertEquals("Shopee", byTitle.getValue("Hot Wheels Skyline R34 HW J Imports ori").shop)
        assertTrue(env.texts(R.string.price_tip_asking) in check.tips)
        assertFalse("no screenshots to add", env.texts(R.string.price_tip_more_screenshots) in check.tips)
        assertFalse(env.texts(R.string.price_tip_best_selling) in check.tips)
        assertEquals("Kebanyakan kartu J-Imports.", check.aiNote)
        assertEquals(check, env.db.priceChecks().observeFor(target.id).first().single())

        val search = fake.urls.first { "serpapi.com/search.json" in it }.toHttpUrl()
        assertEquals("google_shopping", search.queryParameter("engine"))
        assertEquals("Hot Wheels Skyline GT-R R34", search.queryParameter("q"))
        assertEquals("id", search.queryParameter("gl"))
        assertEquals("serp-key", search.queryParameter("api_key"))
        // The AI reads the titles only: no photo goes with them.
        val asked = fake.bodies[fake.calls.indexOf(DEFAULT_GEMINI_MODEL)]
        assertFalse("inline_data" in asked || "inlineData" in asked)
        assertTrue("0. Hot Wheels Nissan Skyline GT-R R34 J-Imports" in asked)
        assertTrue("USD 3.5" in asked)
    }

    @Test
    fun `a listing the AI skipped stays unclear and doesn't count`() = runBlocking {
        fake.on("serpapi.com/search.json", 200, results(row("Hot Wheels R34", "Rp 45.000", 45000), row("Hot Wheels Skyline", "Rp 60.000", 60000)))
        fake.on(DEFAULT_GEMINI_MODEL, 200, FakeGemini.answer("""{"listings": [{"index": 1, "match": "match", "reason": "ok"}], "note": null, "search_hint": null}"""))

        val check = checker.checkOnline(diecast(), "hot wheels r34")

        val skipped = check.listings.single { it.title == "Hot Wheels R34" }
        assertEquals(ListingMatch.Unclear, skipped.match)
        assertEquals(ListingStatus.Excluded, skipped.status)
        assertEquals(1, check.stats!!.count)
        assertTrue(env.texts(R.string.price_tip_keywords_auto) in check.tips)
    }

    @Test
    fun `nothing found records nothing and doesn't ask the AI`() = runBlocking {
        fake.on("serpapi.com/search.json", 200, """{"error": "Google hasn't returned any results for this query."}""")
        val target = diecast()

        try {
            checker.checkOnline(target, "  hot wheels zzz ")
            fail("expected nothing found")
        } catch (e: ShoppingSearchException) {
            assertEquals(env.texts(R.string.price_auto_nothing, "hot wheels zzz"), e.message)
        }
        assertTrue(env.db.priceChecks().observeFor(target.id).first().isEmpty())
        assertFalse(DEFAULT_GEMINI_MODEL in fake.calls)
    }

    @Test
    fun `SerpApi's refusals say what to do`() = runBlocking {
        suspend fun failure(code: Int, body: String): String? {
            fake.on("serpapi.com/search.json", code, body)
            return try {
                shopping.search("hot wheels")
                null
            } catch (e: ShoppingSearchException) {
                e.message
            }
        }

        assertEquals(env.texts(R.string.serpapi_key_rejected), failure(401, """{"error": "Invalid API key."}"""))
        assertEquals(env.texts(R.string.serpapi_quota), failure(429, """{"error": "Your account has run out of searches."}"""))
        assertEquals(env.texts(R.string.serpapi_failed, "Missing query `q` parameter."), failure(400, """{"error": "Missing query `q` parameter."}"""))
        assertEquals(env.texts(R.string.serpapi_failed, "HTTP 503"), failure(503, "<html>busy</html>"))

        key.value = null
        assertFalse(shopping.enabled.first())
        assertFalse(checker.onlineEnabled.first())
        assertEquals(env.texts(R.string.serpapi_no_key), failure(200, results()))
    }

    @Test
    fun `the account says how many searches are left, and a typed key can be tested before it is saved`() = runBlocking {
        fake.on(
            "serpapi.com/account.json",
            200,
            """{"account_status": "Active", "plan_name": "Free Plan", "searches_per_month": 250, "plan_searches_left": 243, "extra_credits": 0, "total_searches_left": 243}""",
        )

        assertEquals(SerpApiAccount("Free Plan", 250, 243), shopping.account())
        assertEquals(243, checker.searchesLeft())
        shopping.account("typed-key")
        assertEquals("typed-key", fake.urls.last().toHttpUrl().queryParameter("api_key"))

        fake.on("serpapi.com/account.json", 401, """{"error": "Invalid API key."}""")
        assertNull("not worth an error after a check", checker.searchesLeft())
    }

    @Test
    fun `the currency is read from the price as Google writes it`() {
        assertEquals("IDR", ShoppingSearch.currencyOf("Rp 45.000"))
        assertEquals("IDR", ShoppingSearch.currencyOf("IDR 200,000"))
        assertEquals("IDR", ShoppingSearch.currencyOf(null))
        assertEquals("USD", ShoppingSearch.currencyOf("US$12.50"))
        assertEquals("USD", ShoppingSearch.currencyOf("$12"))
        assertEquals("SGD", ShoppingSearch.currencyOf("S$15.90"))
        assertEquals("HKD", ShoppingSearch.currencyOf("HK$80"))
        assertEquals("MYR", ShoppingSearch.currencyOf("RM 30.00"))
        assertEquals("JPY", ShoppingSearch.currencyOf("¥3,000"))
        assertEquals("CNY", ShoppingSearch.currencyOf("CN¥20"))
        assertEquals("EUR", ShoppingSearch.currencyOf("12,50 €"))
        assertEquals("GBP", ShoppingSearch.currencyOf("£9.99"))
    }

    @Test
    fun `checks saved before listings had links still read`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<PriceListing>("""{"title": "Hot Wheels R34", "price_idr": 45000, "marketplace": "tokopedia"}""")

        assertNull(old.link)
        assertEquals(45_000L, old.price)
    }
}
