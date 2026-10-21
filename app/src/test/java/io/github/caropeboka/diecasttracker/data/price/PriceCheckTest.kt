package io.github.caropeboka.diecasttracker.data.price

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.ai.MemoryModelCache
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Web: `tests/Feature/PriceCheckTest.php` (screenshots are not kept here, so those checks have no counterpart). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PriceCheckTest {
    private val env = TestEnvironment(now = { 1_000L })
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val checker = PriceChecker(
        MutableStateFlow(GeminiSettings(key = "test-key")),
        GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta"),
        env.db,
        clock = { 5_000L },
    )

    @After
    fun tearDown() = env.close()

    private suspend fun diecast(): DiecastEntity {
        val id = env.repository.save(DiecastEntity(name = "'68 Dodge Dart", brand = "Hot Wheels", series = "HW Flames", collectionNumber = "6/10"))
        return env.db.diecasts().get(id)!!
    }

    /** What the AI answers, filled in like the web test's `fakeListings`. */
    private fun fakeListings(vararg listings: Map<String, Any?>, note: String? = null, hint: String? = null) {
        val defaults = mapOf<String, Any?>(
            "is_price_range" to false, "shop" to null, "location" to null, "sold" to null, "rating" to null,
            "is_ad" to false, "marketplace" to "tokopedia", "reason" to "ok",
        )
        fun json(value: Any?) = when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
        val answer = buildJsonObject {
            put("listings", JsonArray(listings.map { listing -> JsonObject((defaults + listing).mapValues { json(it.value) }) }))
            put("note", note)
            put("search_hint", hint)
        }
        fake.otherwise(200, FakeGemini.answer(answer))
    }

    private val jpeg get() = TestEnvironment.fixtureJpeg

    @Test
    fun `screenshots are read and median computed`() = runBlocking {
        fakeListings(
            mapOf("title" to "Hot Wheels '68 Dodge Dart HW Flames", "price_idr" to 35_000, "shop" to "Garasi A", "sold" to 10, "match" to "match"),
            mapOf("title" to "HW '68 Dodge Dart orange", "price_idr" to 40_000, "shop" to "Toko B", "sold" to 5, "match" to "match"),
            mapOf("title" to "Hot Wheels 68 Dodge Dart carded", "price_idr" to 45_000, "shop" to "Toko C", "sold" to 30, "match" to "match"),
            mapOf("title" to "Dodge Dart LOOSE", "price_idr" to 15_000, "shop" to "Toko D", "match" to "different_condition"),
            note = "Kebanyakan listing carded.",
            hint = "hot wheels dodge dart flames",
        )
        val diecast = diecast()

        val check = checker.check(diecast, listOf(jpeg, jpeg))

        assertEquals(40_000L, check.stats!!.median)
        assertEquals(3, check.stats!!.count)
        assertEquals("Kebanyakan listing carded.", check.aiNote)
        assertEquals(2, check.screenshots)
        assertEquals(PriceAccuracy.Medium, check.accuracy)
        assertEquals("Coba cari dengan kata kunci: \"hot wheels dodge dart flames\".", check.tips.last())
        assertEquals(listOf(check), checker.observe(diecast.id).first())

        // The diecast is described to the AI, and the answer is shaped by the listing schema.
        val body = fake.bodies.last()
        assertTrue(body.contains("- Name: '68 Dodge Dart"))
        assertTrue(body.contains("- Collection number: 6/10"))
        assertTrue(body.contains("price_idr"))
    }

    @Test
    fun `applying sets market value from the check`() = runBlocking {
        fakeListings(
            mapOf("title" to "A", "price_idr" to 36_000, "match" to "match"),
            mapOf("title" to "B", "price_idr" to 38_000, "match" to "match"),
        )
        val diecast = diecast()
        val check = checker.check(diecast, listOf(jpeg))

        val applied = checker.apply(check)

        assertEquals(37_000L, applied.marketValue)
        assertEquals(ValueSource.PriceCheck, applied.marketValueSource)
        assertEquals(5_000L, applied.marketValueUpdatedAt)
        assertEquals(5_000L, checker.observe(diecast.id).first().single().appliedAt)

        // Saving the form with the same value keeps the source; typing a new one marks it manual.
        env.repository.save(applied.copy(notes = "Dicek"))
        assertEquals(ValueSource.PriceCheck, env.db.diecasts().get(diecast.id)!!.marketValueSource)
        env.repository.save(env.db.diecasts().get(diecast.id)!!.copy(marketValue = 50_000))
        assertEquals(ValueSource.Manual, env.db.diecasts().get(diecast.id)!!.marketValueSource)
    }

    @Test
    fun `ai quota error is reported and nothing is stored`() = runBlocking {
        fake.otherwise(429)
        val diecast = diecast()

        val error = assertThrows(AiRetryLaterException::class.java) { runBlocking { checker.check(diecast, listOf(jpeg)) } }

        assertEquals("Kuota gratis Gemini habis untuk saat ini. Tunggu sebentar (atau besok) lalu coba lagi.", error.message)
        assertEquals(emptyList<Any>(), checker.observe(diecast.id).first())
    }

    @Test
    fun `deleting a diecast removes its checks`() = runBlocking {
        fakeListings(mapOf("title" to "A", "price_idr" to 36_000, "match" to "match"))
        val diecast = diecast()
        checker.check(diecast, listOf(jpeg))

        env.repository.delete(diecast.id)

        assertEquals(emptyList<Any>(), env.db.priceChecks().all())
    }

    @Test
    fun `a single listing is not a market price yet`() = runBlocking {
        fakeListings(mapOf("title" to "A", "price_idr" to 36_000, "match" to "match"))
        val diecast = diecast()

        val check = checker.check(diecast, listOf(jpeg))

        assertEquals(PriceAccuracy.Insufficient, check.accuracy)
        assertNotNull(check.stats)
        assertEquals(1, checker.observe(diecast.id).first().size)
    }

    @Test
    fun `search keywords name the variant and loose cars`() {
        val sth = DiecastEntity(name = "'68 Dodge \"Dart\"", brand = "Hot Wheels", rarity = Rarity.SuperTreasureHunt, packaging = Packaging.Loose)

        assertEquals("Hot Wheels 68 Dodge Dart super treasure hunt loose", PriceChecker.searchQuery(sth))
        assertEquals("Mini GT", PriceChecker.searchQuery(DiecastEntity(name = "", brand = "Mini GT")).trim())
    }
}
