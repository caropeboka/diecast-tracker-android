package io.github.caropeboka.diecasttracker.data.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException

/** Ports of the web app's Gemini tests (`DiecastTest`, `AiSettingsTest`). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class GeminiClientTest {
    private val fake = FakeGemini()
    private val cache = MemoryModelCache()
    private var now = 0L
    private val client = GeminiClient(fake.client, cache, baseUrl = "https://gemini.test/v1beta", clock = { now })

    private val schema = buildJsonObject { put("type", "object") }
    private val busy = FakeGemini.Reply.Json(503, """{"error":{"code":503,"status":"UNAVAILABLE"}}""")
    private val mazda = FakeGemini.answer("""{"name":"Mazda RX-7","brand":"Tomica","rarity":"regular","confidence":"high"}""")

    private suspend fun extract(model: String = "gemini-3.8-flash", images: Int = 1): JsonObject =
        client.extract("test-key", model, List(images) { byteArrayOf(1, 2, 3) }, "system", "Identify this diecast.", schema)

    private suspend fun extractError(model: String = "gemini-3.8-flash"): AiException {
        try {
            extract(model)
        } catch (e: AiException) {
            return e
        }
        fail("Expected an AiException")
        throw AssertionError()
    }

    @Test
    fun `sends every photo, the instruction and the key, and returns the answer`() = runTest {
        fake.on("models", 200, FakeGemini.models()).otherwise(200, mazda)

        val data = extract(images = 2)

        assertEquals("Mazda RX-7", data["name"]?.jsonPrimitive?.content)
        assertEquals(listOf("models", "gemini-3.8-flash"), fake.calls)
        assertTrue(fake.keys.all { it == "test-key" })

        val request = Json.parseToJsonElement(fake.bodies.last()).jsonObject
        val parts = request["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(3, parts.size)
        assertEquals("image/jpeg", parts[0].jsonObject["inline_data"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
        assertEquals("AQID", parts[1].jsonObject["inline_data"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        assertEquals("Identify this diecast.", parts[2].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("system", request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "application/json",
            request["generationConfig"]!!.jsonObject["responseMimeType"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `quota error is explained`() = runTest {
        fake.otherwise(429, """{"error":{"code":429}}""")

        val error = extractError()

        assertTrue(error is AiRetryLaterException)
        assertEquals("Kuota gratis Gemini habis untuk saat ini. Tunggu sebentar (atau besok) lalu coba lagi.", error.message)
    }

    @Test
    fun `busy model falls back to lite models first, then the newest flash models`() = runTest {
        fake.on(
            "models", 200,
            FakeGemini.models(
                "gemini-3.5-flash-lite", "gemini-2.5-flash", "gemini-3.8-flash", "gemini-3.6-flash",
                "gemini-3.7-flash", "gemini-3.1-flash-image", "gemini-3-flash-preview",
            ),
        )
            .on("gemini-3.8-flash", busy)
            .on("gemini-3.5-flash-lite", busy)
            .on("gemini-flash-lite-latest", FakeGemini.Reply.Fail(ConnectException("refused")))
            .on("gemini-3.7-flash", busy)
            .on("gemini-3.6-flash", 200, mazda)

        assertEquals("Mazda RX-7", extract()["name"]?.jsonPrimitive?.content)

        // Model list, chosen model (+ one quick retry), then lite models first (busy, unreachable), then the newest
        // regular flash models; fallbacks get no retry.
        assertEquals(
            listOf(
                "models", "gemini-3.8-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite",
                "gemini-flash-lite-latest", "gemini-3.7-flash", "gemini-3.6-flash",
            ),
            fake.calls,
        )
    }

    @Test
    fun `the fallback list is cached per key`() = runTest {
        fake.on("models", 200, FakeGemini.models("gemini-3.5-flash-lite")).otherwise(200, mazda)

        extract()
        extract()

        assertEquals(listOf("models", "gemini-3.8-flash", "gemini-3.8-flash"), fake.calls)
        assertEquals(listOf("gemini-3.5-flash-lite"), cache.stored["test-key"])
    }

    @Test
    fun `reports busy rather than a retired fallback`() = runTest {
        fake.on("models", 200, FakeGemini.models("gemini-2.5-flash"))
            .on("gemini-3.8-flash", 503)
            .otherwise(404, """{"error":{"message":"no longer available to new users"}}""")

        assertEquals("Server Gemini sedang sibuk (banyak pengguna). Coba lagi sebentar lagi.", extractError().message)
    }

    @Test
    fun `all models busy says try again later`() = runTest {
        fake.otherwise(busy)

        val error = extractError()

        assertTrue(error is AiRetryLaterException)
        assertEquals("Server Gemini sedang sibuk (banyak pengguna). Coba lagi sebentar lagi.", error.message)
    }

    @Test
    fun `gives up when the time budget is used up`() = runTest {
        fake.otherwise(busy)
        fake.onRequest = { now += 45_000 }

        extractError()

        // The list and both tries of the chosen model take 135 s: no fallback is started after that.
        assertEquals(listOf("models", "gemini-3.8-flash", "gemini-3.8-flash"), fake.calls)
    }

    @Test
    fun `no connection is reported as such, without trying other models`() = runTest {
        fake.otherwise(FakeGemini.Reply.Fail(UnknownHostException("gemini.test")))

        assertEquals("Tidak bisa terhubung ke Gemini. Cek koneksi internet.", extractError().message)
        assertEquals(listOf("models", "gemini-3.8-flash"), fake.calls)
    }

    @Test
    fun `a model that never answers is reported as busy`() = runTest {
        fake.on("models", 200, FakeGemini.models()).otherwise(FakeGemini.Reply.Fail(InterruptedIOException("timeout")))

        val error = extractError()

        assertTrue(error is AiRetryLaterException)
        assertEquals("Gemini tidak merespons (server penuh atau koneksi lambat). Coba lagi sebentar lagi.", error.message)
    }

    @Test
    fun `invalid key stops at once`() = runTest {
        fake.on("models", 400).otherwise(400, """{"error":{"code":400,"details":[{"reason":"API_KEY_INVALID"}]}}""")

        assertEquals("API key Gemini tidak valid. Cek Pengaturan AI.", extractError().message)
        assertEquals(listOf("models", "gemini-3.8-flash"), fake.calls)
    }

    @Test
    fun `unknown chosen model is named`() = runTest {
        fake.on("models", 200, FakeGemini.models()).otherwise(400)
            .on("gemini-9-flash", 404)

        assertEquals("Model Gemini \"gemini-9-flash\" tidak ditemukan. Pilih model lain di Pengaturan AI.", extractError("gemini-9-flash").message)
    }

    @Test
    fun `blocked image and unreadable answer get their own messages`() = runTest {
        fake.on("models", 200, FakeGemini.models()).otherwise(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals("AI tidak bisa memproses gambar ini.", extractError().message)

        fake.otherwise(200, """{"candidates":[{"content":{"parts":[{"text":"Sorry, I can't tell."}]}}]}""")
        assertEquals("Hasil AI tidak terbaca. Coba foto ulang dengan cahaya lebih terang.", extractError().message)
    }

    @Test
    fun `testing a key lists the models that can read a photo`() = runTest {
        fake.on(
            "models", 200,
            """
            {"models": [
                {"name": "models/gemini-3.8-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/gemini-embedding-001", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-3.8-flash-tts", "supportedGenerationMethods": ["generateContent"]}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("gemini-3.8-flash"), client.availableModels("AIza-test"))
        assertEquals(listOf<String?>("AIza-test"), fake.keys)
    }

    @Test
    fun `testing an invalid key says so`() = runTest {
        fake.on("models", 400, """{"error":{"code":400}}""")

        try {
            client.availableModels("wrong")
            fail("Expected an AiException")
        } catch (e: AiException) {
            assertEquals("API key Gemini tidak valid.", e.message)
        }
    }

    @Test
    fun `schema becomes Gemini's OpenAPI dialect`() {
        val schema = Json.parseToJsonElement(
            """
            {"type": "object", "additionalProperties": false, "required": ["name", "rarity"],
             "properties": {
                "name": {"anyOf": [{"type": "string"}, {"type": "null"}]},
                "rarity": {"type": "string", "enum": ["regular", "chase"]}
             }}
            """.trimIndent(),
        ).jsonObject

        assertEquals(
            Json.parseToJsonElement(
                """
                {"type": "OBJECT", "required": ["name", "rarity"],
                 "properties": {
                    "name": {"type": "STRING", "nullable": true},
                    "rarity": {"type": "STRING", "enum": ["regular", "chase"]}
                 },
                 "propertyOrdering": ["name", "rarity"]}
                """.trimIndent(),
            ),
            toGeminiSchema(schema),
        )
    }

    @Test
    fun `versions compare by number`() {
        assertTrue(compareVersions("3.10", "3.8") > 0)
        assertTrue(compareVersions("2.5", "3") < 0)
        assertEquals(0, compareVersions("3.5", "3.5"))
    }
}
