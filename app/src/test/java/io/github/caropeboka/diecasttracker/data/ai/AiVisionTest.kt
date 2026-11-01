package io.github.caropeboka.diecasttracker.data.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.UnknownHostException
import java.time.Instant
import java.util.Collections

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class AiVisionTest {
    private var now = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val rests = MemoryRests()
    private val events: MutableList<AiEvent> = Collections.synchronizedList(mutableListOf())

    private val gemini = ProviderSettings(AiProvider.Gemini, "gemini-key")
    private val groq = ProviderSettings(AiProvider.Groq, "gsk-test")
    private val mistral = ProviderSettings(AiProvider.Mistral, "mistral-key")

    private fun vision(vararg providers: ProviderSettings) = AiVision(
        MutableStateFlow(AiSetup(providers.toList())),
        GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta", clock = { now }),
        OpenAiClient(fake.client, clock = { now }),
        rests,
        clock = { now },
    )

    private suspend fun AiVision.read(images: Int = 1) =
        extract(List(images) { ByteArray(16) { i -> i.toByte() } }, "Read the card.", "Which diecast is this?", SCHEMA) { events += it }

    @Test
    fun `Gemini out of its daily quota hands the photo to Groq and rests until midnight in California`() = runBlocking {
        fake.otherwise(429, DAILY_429).on(GROQ_CHAT, 200, chat("""{"name": "Honda NSX-R"}"""))
        val vision = vision(gemini, groq)

        val answer = vision.read()

        assertEquals(AiProvider.Groq, answer.provider)
        assertEquals("Groq", answer.by)
        assertEquals("Honda NSX-R", answer.data["name"]!!.jsonPrimitive.content)
        assertTrue(AiEvent.Switching("Groq") in events)
        // Google resets daily quotas at midnight Pacific time (PDT on this date).
        val midnight = Instant.parse("2026-10-21T07:00:00Z").toEpochMilli()
        assertEquals(midnight, rests.rests[AiProvider.Gemini])

        // The next photo goes straight to Groq, and the log says why Gemini was left out.
        fake.calls.clear()
        events.clear()
        vision.read()
        assertTrue(fake.calls.all { it == GROQ_CHAT })
        assertEquals(AiEvent.Resting("Gemini", midnight), events.first())
        assertFalse(events.any { it is AiEvent.Switching })

        // Once its quota is back, Gemini is first again.
        now = midnight + 1
        fake.otherwise(200, FakeGemini.answer("""{"name": "Honda NSX-R"}"""))
        assertEquals(AiProvider.Gemini, vision.read().provider)
        assertNull(rests.rests[AiProvider.Gemini])
    }

    @Test
    fun `Groq gets at most 3 photos as data URLs with its key in JSON mode`() = runBlocking {
        fake.on(GROQ_CHAT, 200, chat("""{"name": "Honda NSX-R"}"""))

        vision(groq).read(images = 4)

        val body = Json.parseToJsonElement(fake.bodies.single()).jsonObject
        assertEquals("Bearer gsk-test", fake.bearers.single())
        assertEquals(AiProvider.Groq.defaultModel, body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith("Read the card."))
        assertTrue("the schema goes in the prompt", "\"name\"" in system)
        val parts = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image_url", "image_url", "image_url", "text"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertTrue(parts[0]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertTrue(AiEvent.Trimmed(3, 4) in events)
    }

    @Test
    fun `a model that refuses JSON mode is asked again without it`() = runBlocking {
        fake.on(
            GROQ_CHAT,
            FakeGemini.Reply.Json(400, """{"error":{"message":"response_format json_object is not supported by this model"}}"""),
            FakeGemini.Reply.Json(200, chat("""{"name": "Honda NSX-R"}""")),
        )

        val answer = vision(groq).read()

        assertEquals("Honda NSX-R", answer.data["name"]!!.jsonPrimitive.content)
        assertEquals(2, fake.bodies.size)
        assertTrue("response_format" in fake.bodies[0])
        assertFalse("response_format" in fake.bodies[1])
    }

    @Test
    fun `a refused key moves on to the next provider without resting it`() = runBlocking {
        fake.on(GROQ_CHAT, 401, """{"error":{"message":"Invalid API Key"}}""")
            .on(MISTRAL_CHAT, 200, chat("""{"name": "Honda NSX-R"}"""))

        val answer = vision(groq, mistral).read()

        assertEquals(AiProvider.Mistral, answer.provider)
        assertTrue(rests.rests.isEmpty())
    }

    @Test
    fun `when every provider is busy or out of quota the request is to be tried later`() = runBlocking {
        fake.otherwise(429, """{"error":{"code":429,"details":[{"retryDelay":"30s"}]}}""")
            .on(GROQ_CHAT, 503, """{"error":{"message":"Service unavailable"}}""")

        try {
            vision(gemini, groq).read()
            fail("Expected an AiRetryLaterException")
        } catch (e: AiRetryLaterException) {
            assertTrue(e.message!!.startsWith("Semua AI yang diatur gagal:\n• Kuota gratis Gemini habis"))
            assertTrue("Server Groq sedang sibuk" in e.message!!)
        }
        assertEquals(now + 30_000, rests.rests[AiProvider.Gemini])
        assertNull("busy is not out of quota", rests.rests[AiProvider.Groq])
    }

    @Test
    fun `without a connection to any of them the request says so`() = runBlocking {
        fake.otherwise(FakeGemini.Reply.Fail(UnknownHostException("no network")))

        try {
            vision(gemini, groq).read()
            fail("Expected an AiOfflineException")
        } catch (e: AiOfflineException) {
            assertEquals("Tidak bisa terhubung ke Gemini. Cek koneksi internet.", e.message)
        }
    }

    @Test
    fun `nothing set up says how to turn the AI on`() = runBlocking {
        val vision = vision(ProviderSettings(AiProvider.Gemini), ProviderSettings(AiProvider.Groq, "gsk-test", on = false))

        assertFalse(vision.isEnabled())
        try {
            vision.read()
            fail("Expected an AiException")
        } catch (e: AiException) {
            assertEquals(AiVision.NOT_SET, e.message)
        }
    }

    @Test
    fun `a provider out of quota rests as long as it says`() = runBlocking {
        fake.on(GROQ_CHAT, 429, """{"error":{"message":"Rate limit reached for model. Please try again in 1m26.4s."}}""")
            .on(OPENROUTER_CHAT, FakeGemini.Reply.Json(429, """{"error":{"message":"Rate limit exceeded: free-models-per-day"}}""", mapOf("x-ratelimit-reset" to "${now + 3_600_000}")))
            .on(MISTRAL_CHAT, FakeGemini.Reply.Json(429, """{"message":"Requests rate limit exceeded"}""", mapOf("retry-after" to "20")))

        try {
            vision(groq, ProviderSettings(AiProvider.OpenRouter, "sk-or-test"), mistral).read()
            fail("Expected an AiRetryLaterException")
        } catch (_: AiRetryLaterException) {
        }
        assertEquals(now + 86_400, rests.rests[AiProvider.Groq])
        assertEquals(now + 3_600_000, rests.rests[AiProvider.OpenRouter])
        assertEquals(now + 20_000, rests.rests[AiProvider.Mistral])
    }

    @Test
    fun `a custom server takes its own address and may need no key`() = runBlocking {
        fake.on("integrate.api.nvidia.com/v1/chat/completions", 200, chat("""{"name": "Honda NSX-R"}"""))
        val custom = ProviderSettings(AiProvider.Custom, model = "meta/llama-4-scout", baseUrl = "https://integrate.api.nvidia.com/v1/")

        val answer = vision(custom).read()

        assertEquals("integrate.api.nvidia.com", answer.by)
        assertNull(fake.bearers.single())
    }

    @Test
    fun `the model lists keep the ones that read photos`() = runBlocking {
        fake.on(
            "openrouter.ai/api/v1/models", 200,
            """{"data":[
                {"id":"google/gemma-4-31b-it:free","architecture":{"input_modalities":["text","image"]}},
                {"id":"meta/llama-text:free","architecture":{"input_modalities":["text"]}},
                {"id":"openai/gpt-5","architecture":{"input_modalities":["text","image"]}}
            ]}""",
        ).on(
            "api.mistral.ai/v1/models", 200,
            """{"data":[{"id":"mistral-small-latest","capabilities":{"vision":true}},{"id":"codestral-latest","capabilities":{"vision":false}}]}""",
        ).on("api.groq.com/openai/v1/models", 200, """{"data":[{"id":"qwen/qwen3.8-27b"},{"id":"whisper-large-v3"}]}""")
        val client = OpenAiClient(fake.client)

        assertEquals(listOf("openrouter/free", "google/gemma-4-31b-it:free"), client.availableModels(ProviderSettings(AiProvider.OpenRouter, "sk-or-test")))
        assertEquals(listOf("mistral-small-latest"), client.availableModels(mistral))
        assertEquals(listOf("qwen/qwen3.8-27b"), client.availableModels(groq))
    }

    @Test
    fun `answers are found past thinking and code fences`() {
        val text = "<think>The card says {name}…</think>\nHere it is:\n```json\n{\"name\": \"Honda NSX-R\"}\n```"

        assertEquals("Honda NSX-R", jsonObjectIn(text)!!["name"]!!.jsonPrimitive.content)
        assertNull(jsonObjectIn("Sorry, I can't read this photo."))
    }

    @Test
    fun `waits are read the way Groq writes them`() {
        assertEquals(86_400L, durationMillis("1m26.4s"))
        assertEquals(860L, durationMillis("860ms"))
        assertEquals(7_200_000L, durationMillis("2h"))
    }

    private companion object {
        const val GROQ_CHAT = "api.groq.com/openai/v1/chat/completions"
        const val MISTRAL_CHAT = "api.mistral.ai/v1/chat/completions"
        const val OPENROUTER_CHAT = "openrouter.ai/api/v1/chat/completions"

        const val DAILY_429 = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}
        ]}}"""

        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("name") { put("type", "string") } }
        }

        /** A chat completion whose message is [content]. */
        fun chat(content: String) = buildJsonObject {
            putJsonArray("choices") {
                addJsonObject {
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                }
            }
        }.toString()
    }
}
