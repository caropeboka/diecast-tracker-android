package io.github.caropeboka.diecasttracker.data.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.ui.edit.DiecastForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Collections

/** "Tanya AI": the conversation, Gemini's web search and the suggestions taken out of the answer. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class DiecastChatTest {
    private val env = TestEnvironment()
    private val fake = FakeGemini().on("models", 200, FakeGemini.models())
    private val events: MutableList<AiEvent> = Collections.synchronizedList(mutableListOf())

    private fun chat(setup: AiSetup = AiSetup.gemini("test-key")) =
        DiecastChat(fake.vision(MutableStateFlow(setup)), env.photos, env.texts, today = { LocalDate.of(2026, 11, 6) })

    private val form = DiecastForm(name = "Nissan Skyline GT-R", brand = "Hot Wheels", year = "2019")

    @After
    fun tearDown() = env.close()

    @Test
    fun `a question sees the photos and the form, and Gemini searches the web for it`() = runBlocking {
        fake.otherwise(
            200,
            gemini(
                "The card says **2019**, but this one came out in 2021.\n\n```suggest\n" +
                    """[{"field": "year", "value": "2021", "reason": "rilis 2021"}]""" + "\n```",
                WebSource("hotwheels.fandom.com", "https://vertexaisearch.cloud.google.com/grounding/1"),
            ),
        )
        val context = ChatContext(listOf(env.pendingPhoto()), form.chatFields(), dexEntry = "Hot Wheels Mainline (2021) · #123")

        val answer = chat().ask(context, emptyList(), "Tahun rilisnya benar?") { events += it }

        assertEquals("The card says **2019**, but this one came out in 2021.", answer.text)
        assertEquals(listOf(FieldSuggestion("year", "2021", "rilis 2021")), answer.suggestions)
        assertEquals(listOf(WebSource("hotwheels.fandom.com", "https://vertexaisearch.cloud.google.com/grounding/1")), answer.sources)
        assertTrue(answer.searched)
        assertEquals("Gemini", answer.by)
        assertTrue("```suggest" in answer.raw)

        val body = Json.parseToJsonElement(fake.bodies.last()).jsonObject
        assertTrue("google_search" in body["tools"].toString())
        val system = body["systemInstruction"].toString()
        assertTrue("- year: 2019" in system && "- make: (empty)" in system && "#123" in system && "2026-11-06" in system)
        val parts = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        assertEquals(listOf("inline_data", "text"), parts.map { it.jsonObject.keys.single() })
    }

    @Test
    fun `a model that can't search is asked again without it`() = runBlocking {
        fake.on(
            DEFAULT_GEMINI_MODEL,
            FakeGemini.Reply.Json(400, """{"error":{"code":400,"message":"Search Grounding is not supported."}}"""),
            FakeGemini.Reply.Json(200, gemini("It looks like a 2021 release.")),
        )

        val answer = chat().ask(ChatContext(emptyList(), form.chatFields()), emptyList(), "Tahun rilisnya benar?") { events += it }

        assertEquals("It looks like a 2021 release.", answer.text)
        assertFalse(answer.searched)
        val sent = fake.bodies.filter { "contents" in it }
        assertEquals(listOf(true, false), sent.map { "google_search" in it })
        assertTrue(events.any { it is AiEvent.Missed && it.retrying })
    }

    @Test
    fun `the conversation goes on with the photos only in the first question`() = runBlocking {
        val groq = AiSetup(listOf(ProviderSettings(AiProvider.Groq, "gsk-test")))
        fake.on("api.groq.com/openai/v1/chat/completions", 200, completion("<think>hmm</think>Yes, a Treasure Hunt: see the flame logo."))
        val history = listOf(ChatTurn(fromUser = true, "Ini seri apa?"), ChatTurn(fromUser = false, "HW J-Imports, 3/10."))

        val answer = chat(groq).ask(ChatContext(listOf(env.pendingPhoto()), form.chatFields()), history, "Ini TH/STH?")

        assertEquals("Yes, a Treasure Hunt: see the flame logo.", answer.text)
        assertEquals("Groq", answer.by)
        val messages = Json.parseToJsonElement(fake.bodies.last()).jsonObject["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val first = messages[1]["content"] as JsonArray
        assertEquals(listOf("image_url", "text"), first.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("Ini TH/STH?", (messages[3]["content"] as JsonPrimitive).content)
        assertFalse("response_format" in fake.bodies.last())
    }

    @Test
    fun `suggestions come out of the answer, a code sample stays in`() {
        val (text, suggestions) = DiecastChat.splitSuggestions(
            "Two changes.\n\n```json\n" +
                """{"changes": [{"field": "rarity", "value": "treasure_hunt"}, {"field": "price", "value": "1"}, {"field": "series", "value": null}]}""" +
                "\n```\n\nA casting code looks like this:\n```\nHW-123\n```\n\n```suggest\n[]\n```",
        )

        assertEquals(listOf(FieldSuggestion("rarity", "treasure_hunt")), suggestions)
        assertEquals("Two changes.\n\nA casting code looks like this:\n```\nHW-123\n```", text)
    }

    private companion object {
        /** A generateContent answer in words, with the pages Google Search found. */
        fun gemini(text: String, vararg sources: WebSource): String = buildJsonObject {
            putJsonArray("candidates") {
                addJsonObject {
                    putJsonObject("content") {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", "Thinking it over…")
                                put("thought", true)
                            }
                            addJsonObject { put("text", text) }
                        }
                    }
                    if (sources.isNotEmpty()) {
                        putJsonObject("groundingMetadata") {
                            putJsonArray("groundingChunks") {
                                sources.forEach { source ->
                                    addJsonObject {
                                        putJsonObject("web") {
                                            put("uri", source.url)
                                            put("title", source.title)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()

        fun completion(content: String) = buildJsonObject {
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
