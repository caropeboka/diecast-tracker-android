package io.github.caropeboka.diecasttracker.data.ai

import android.app.Application
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.testTexts
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.time.Instant
import java.util.Collections
import java.util.Random

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        GeminiClient(fake.client, MemoryModelCache(), testTexts(), baseUrl = "https://gemini.test/v1beta", clock = { now }),
        OpenAiClient(fake.client, testTexts(), clock = { now }),
        rests,
        testTexts(),
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
    fun `Groq gets at most 2 photos as data URLs with its key in JSON mode`() = runBlocking {
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
        assertEquals(listOf("image_url", "image_url", "text"), parts.map { it["type"]!!.jsonPrimitive.content })
        assertTrue(parts[0]["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertTrue(AiEvent.Trimmed(2, 4) in events)
    }

    @Test
    fun `over Groq's tokens a minute, fewer photos go, not smaller ones`() = runBlocking {
        // Groq's 413 for a request over the plan's limit; each photo counts 2048 tokens.
        val tooMany = """{"error":{"message":"Request too large for model `qwen/qwen3.8-27b` in organization `org_1` service tier `on_demand` on tokens per minute (TPM): Limit 6000, Requested 6900, please reduce your message size and try again.","type":"tokens","code":"rate_limit_exceeded"}}"""
        fake.on(GROQ_CHAT, FakeGemini.Reply.Json(413, tooMany), FakeGemini.Reply.Json(200, chat("""{"name": "Honda NSX-R"}""")))

        assertEquals("Groq", vision(groq).read(images = 2).by)

        fun photos(body: String) = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            .count { it.jsonObject["type"]!!.jsonPrimitive.content == "image_url" }
        assertEquals(listOf(2, 1), fake.bodies.map(::photos))
        // The same photo, not shrunk.
        assertEquals(
            Json.parseToJsonElement(fake.bodies[0]).jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0],
            Json.parseToJsonElement(fake.bodies[1]).jsonObject["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0],
        )
        assertTrue(AiEvent.Trimmed(1, 2) in events)

        // Still over with one: Groq gives up with its own reason, and the next AI may take it.
        fake.bodies.clear()
        fake.on(GROQ_CHAT, FakeGemini.Reply.Json(413, tooMany), FakeGemini.Reply.Json(413, tooMany))
        val error = runCatching { vision(groq).read(images = 2) }.exceptionOrNull()
        assertTrue(error is AiException)
        assertEquals(2, fake.bodies.size)
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
            assertEquals(testTexts()(R.string.ai_err_not_set), e.message)
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
        val client = OpenAiClient(fake.client, testTexts())

        assertEquals(listOf("openrouter/free", "google/gemma-4-31b-it:free"), client.availableModels(ProviderSettings(AiProvider.OpenRouter, "sk-or-test")))
        assertEquals(listOf("mistral-small-latest"), client.availableModels(mistral))
        assertEquals(listOf("qwen/qwen3.8-27b"), client.availableModels(groq))
    }

    @Test
    fun `Cloudflare runs on the account's own address, and OVHcloud needs no key but waits to be switched on`() = runBlocking {
        fake.on("api.cloudflare.com/client/v4/accounts/$ACCOUNT/ai/v1/chat/completions", 200, chat("""{"name": "Honda NSX-R"}"""))
        val cloudflare = ProviderSettings(AiProvider.Cloudflare, "cf-token", account = ACCOUNT)
        assertFalse(cloudflare.copy(account = null).isReady)

        assertEquals("Cloudflare", vision(cloudflare).read().by)
        assertEquals("Bearer cf-token", fake.bearers.single())

        assertFalse(AiProvider.Ovh in AiSetup.EMPTY.chain.map { it.provider })
        val ovh = ProviderSettings(AiProvider.Ovh, on = true)
        assertTrue(ovh.isReady)
        fake.on("oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions", 200, chat("""{"name": "Honda NSX-R"}"""))
        fake.bearers.clear()
        assertEquals("OVHcloud", vision(ovh).read().by)
        assertNull(fake.bearers.single())
    }

    @Test
    fun `photos a service refuses go again the other way encoded, or smaller`() = runBlocking {
        // Z.ai gets plain base64 first; when it refuses that, a data URL.
        fake.on(ZAI_CHAT, FakeGemini.Reply.Json(400, """{"error":{"message":"Invalid image_url"}}"""), FakeGemini.Reply.Json(200, chat("""{"name": "Honda NSX-R"}""")))
        vision(ProviderSettings(AiProvider.Zai, "zai-key")).read()
        assertEquals(listOf(false, true), fake.bodies.map { "data:image/jpeg;base64," in it })

        // Too big for NVIDIA: the same photo again, shrunk.
        fake.bodies.clear()
        fake.on(NVIDIA_CHAT, FakeGemini.Reply.Json(413, "{}"), FakeGemini.Reply.Json(200, chat("""{"name": "Honda NSX-R"}""")))
        val answer = vision(ProviderSettings(AiProvider.Nvidia, "nvapi-test")).extract(listOf(noisyJpeg()), "Read the card.", "Which diecast is this?", SCHEMA) { events += it }
        assertEquals("NVIDIA", answer.by)
        assertEquals(2, fake.bodies.size)
        assertTrue(fake.bodies[1].length < fake.bodies[0].length / 2)
    }

    @Test
    fun `lists without vision flags are read by the names, and a service without a list is asked a word`() = runBlocking {
        fake.on(
            "api.cloudflare.com/client/v4/accounts/$ACCOUNT/ai/models/search", 200,
            """{"success":true,"result":[
                {"id":"a1","name":"@cf/google/gemma-4-26b-a4b-it"},
                {"id":"a2","name":"@cf/meta/llama-3.3-70b-instruct-fp8-fast"},
                {"id":"a3","name":"@cf/baai/bge-m3"}
            ]}""",
        ).on(
            "integrate.api.nvidia.com/v1/models", 200,
            """{"data":[
                {"id":"google/gemma-4-31b-it"},{"id":"google/codegemma-7b"},{"id":"nvidia/nemotron-3.5-super-vl-120b-a12b"},
                {"id":"nvidia/llama-nemotron-embed-vl-1b-v2"},{"id":"meta/llama-3.3-70b-instruct"}
            ]}""",
        ).on(
            "oai.endpoints.kepler.ai.cloud.ovh.net/v1/models", 200,
            """{"data":[{"id":"Qwen3.8-27B"},{"id":"Qwen2.5-VL-72B-Instruct"},{"id":"Qwen3Guard-Gen-8B"},{"id":"gpt-oss-120b"}]}""",
        ).on("api.z.ai/api/paas/v4/models", 404, "{}").on(ZAI_CHAT, 200, chat("OK"))
        val client = OpenAiClient(fake.client, testTexts())

        assertEquals(
            listOf("@cf/meta/llama-4-scout-17b-16e-instruct", "@cf/google/gemma-4-26b-a4b-it"),
            client.availableModels(ProviderSettings(AiProvider.Cloudflare, "cf-token", account = ACCOUNT)),
        )
        assertEquals(listOf("google/gemma-4-31b-it", "nvidia/nemotron-3.5-super-vl-120b-a12b"), client.availableModels(ProviderSettings(AiProvider.Nvidia, "nvapi-test")))
        assertEquals(listOf("Qwen3.8-27B", "Qwen2.5-VL-72B-Instruct"), client.availableModels(ProviderSettings(AiProvider.Ovh, on = true)))
        assertEquals(listOf("glm-4.6v-flash"), client.availableModels(ProviderSettings(AiProvider.Zai, "zai-key")))
        assertEquals(ZAI_CHAT, fake.calls.last())
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
        const val ZAI_CHAT = "api.z.ai/api/paas/v4/chat/completions"
        const val NVIDIA_CHAT = "integrate.api.nvidia.com/v1/chat/completions"
        const val ACCOUNT = "0123456789abcdef0123456789abcdef"

        /** A photo of noise: JPEG can't make it small, so it is over a megabyte. */
        fun noisyJpeg(): ByteArray {
            val random = Random(7)
            val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(IntArray(1200 * 900) { random.nextInt() or (0xff shl 24) }, 0, 1200, 0, 0, 1200, 900)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        }

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
