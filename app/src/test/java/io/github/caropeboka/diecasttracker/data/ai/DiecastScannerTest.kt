package io.github.caropeboka.diecasttracker.data.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiecastScannerTest {
    private val env = TestEnvironment()
    private val fake = FakeGemini()
    private val settings = MutableStateFlow(GeminiSettings(key = "test-key"))
    private val gemini = GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta")
    private val scanner = DiecastScanner(settings, gemini, env.photos, currentYear = { 2026 })
    private val matcher = CollectionMatcher(env.repository)

    @After
    fun tearDown() = env.close()

    private fun requestParts() =
        Json.parseToJsonElement(fake.bodies.last()).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray

    @Test
    fun `scan reads all photos, drops values outside the options and finds the duplicate`() = runBlocking {
        fake.on("models", 200, FakeGemini.models()).otherwise(
            200,
            FakeGemini.answer(
                """{"name": "'71 Datsun 510", "brand": "Hot Wheels", "rarity": "treasure_hunt", "condition": "bogus",
                    "packaging": "carded", "year": 1890, "color": "  ", "confidence": "high"}""",
            ),
        )
        env.repository.save(DiecastEntity(name = "Datsun 510", brand = "Hot Wheels"))
        env.repository.save(DiecastEntity(name = "Ford Mustang", brand = "Hot Wheels"))

        val fields = scanner.scan(listOf(env.pendingPhoto(), env.pendingPhoto()))

        assertEquals("'71 Datsun 510", fields.name)
        assertEquals(Rarity.TreasureHunt, fields.rarity)
        assertEquals(Packaging.Carded, fields.packaging)
        assertNull(fields.condition)
        assertNull(fields.year)
        assertNull(fields.color)
        assertEquals(Confidence.High, fields.confidence)

        val parts = requestParts()
        assertEquals(3, parts.size)
        assertTrue(parts[2].jsonObject["text"]!!.jsonPrimitive.content.startsWith("These 2 photos"))
        assertEquals("test-key", fake.keys.last())

        assertEquals(listOf("Datsun 510"), matcher.find(fields).map { it.name })
    }

    @Test
    fun `at most four photos are sent`() = runBlocking {
        fake.on("models", 200, FakeGemini.models()).otherwise(200, FakeGemini.answer("""{"name": "Mazda RX-7"}"""))

        scanner.scan(List(6) { env.pendingPhoto() })

        assertEquals(5, requestParts().size)
        assertTrue(requestParts()[4].jsonObject["text"]!!.jsonPrimitive.content.startsWith("These 4 photos"))
    }

    @Test
    fun `without a key the scan explains how to turn it on`() = runBlocking {
        settings.value = GeminiSettings(key = null)

        assertFalse(scanner.isEnabled())
        try {
            scanner.scan(listOf(env.pendingPhoto()))
            fail("Expected an AiException")
        } catch (e: AiException) {
            assertEquals("Scan AI belum aktif. Isi API key di Pengaturan AI.", e.message)
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `fields are cleaned`() {
        val fields = ScanFields.from(
            Json.parseToJsonElement(
                """{"name": "  Nissan Skyline GT-R (BNR34) ", "collection_number": "", "year": "2027", "scale": "1:64",
                    "rarity": "regular", "condition": "near_mint", "confidence": "unsure", "unknown": "x"}""",
            ).jsonObject,
            currentYear = 2026,
        )

        assertEquals("Nissan Skyline GT-R (BNR34)", fields.name)
        assertNull(fields.collectionNumber)
        assertEquals(2027, fields.year)
        assertEquals("1:64", fields.scale)
        assertEquals(Rarity.Regular, fields.rarity)
        assertNull(fields.packaging)
        assertEquals(Condition.NearMint, fields.condition)
        assertEquals(Confidence.Medium, fields.confidence)
    }
}
