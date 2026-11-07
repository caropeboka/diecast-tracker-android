package io.github.caropeboka.diecasttracker.data.ai

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** A change to the form the AI suggests, [field] by the scan's names ("year", "collection_number"…). */
@Serializable
data class FieldSuggestion(val field: String, val value: String, val reason: String? = null)

/**
 * What the AI answered in "Tanya AI": its words, the changes it suggests, the pages it searched and who answered.
 * [raw] is the answer as given, suggestions and all, for the next turn's history.
 */
@Serializable
data class ChatAnswer(
    val text: String,
    val suggestions: List<FieldSuggestion> = emptyList(),
    val sources: List<WebSource> = emptyList(),
    val by: String,
    val searched: Boolean = false,
    val raw: String = text,
)

/** What the form holds when a question is asked: photo file names (real sides first) and the fields by name. */
data class ChatContext(val photos: List<String>, val fields: Map<String, String>, val dexEntry: String? = null)

/**
 * "Tanya AI" in the diecast form: a conversation about one diecast that sees its photos and what the form says, and
 * may suggest changes the collector applies one by one. Gemini can search the web for it.
 */
class DiecastChat(
    private val vision: AiVision,
    private val photos: PhotoStore,
    private val texts: Texts,
    private val today: () -> LocalDate = LocalDate::now,
) {
    /** [history]: the earlier questions and their [ChatAnswer.raw] answers, oldest first. */
    suspend fun ask(context: ChatContext, history: List<ChatTurn>, question: String, onEvent: (AiEvent) -> Unit = {}): ChatAnswer {
        val images = withContext(Dispatchers.IO) {
            context.photos.take(MAX_SCAN_PHOTOS).mapNotNull { name -> photos.file(name).takeIf { it.exists() }?.readBytes() }
        }
        val reply = vision.chat(images, system(context, images.size), history + ChatTurn(fromUser = true, question), onEvent)
        val (text, suggestions) = splitSuggestions(reply.text)
        return ChatAnswer(text, suggestions, reply.sources, reply.by, searched = reply.sources.isNotEmpty(), raw = reply.text)
    }

    private fun system(context: ChatContext, photoCount: Int): String {
        val form = context.fields.entries.joinToString("\n") { (key, value) -> "- $key: ${value.ifBlank { "(empty)" }}" }
        val dex = context.dexEntry?.let { "\n\nThe entry it is linked to in the collector's Dex (a list from a fan wiki): $it" }.orEmpty()
        val seen = when (photoCount) {
            0 -> "There are no photos yet."
            1 -> "The collector's photo of it is attached to their first message."
            else -> "The collector's $photoCount photos of it (the same diecast) are attached to their first message."
        }
        return SYSTEM_PROMPT
            .replace("{language}", texts(R.string.ai_answer_language))
            .replace("{today}", today().toString())
            .replace("{photos}", seen) + "\n\nWhat the form says now:\n$form$dex"
    }

    companion object {
        /** Fields the AI may suggest a value for, as the scan names them. */
        val FIELDS = listOf("name", "brand", "make", "series", "collection_number", "year", "scale", "color", "rarity", "packaging", "condition", "notes")

        private val SYSTEM_PROMPT = """
            You are an expert diecast car collector helping a collector with one diecast in their collection tracker.
            {photos} Answer their questions in {language}, briefly and concretely, in plain sentences or short lists.
            Today is {today}.

            Be honest about how sure you are: say when you are guessing, and never invent release details, numbers or
            years. When web search results are available, rely on them. Remember that the year printed on a card is
            usually the casting's copyright year, not the year it was released: a car released in 2024 often carries
            an older year. Hot Wheels Treasure Hunts carry a circle-flame logo; Super Treasure Hunts have Spectraflame
            paint, Real Riders rubber tires and a "TH" tampo.

            When the form should change, end your answer with a block like this, and only then:
            ```suggest
            [{"field": "year", "value": "2021", "reason": "a few words in {language}"}]
            ```
            Fields: name, brand, make (the real car's maker), series, collection_number (as printed, e.g. "123/250"),
            year (the release year, a number), scale (like "1:64"), color, rarity (one of: ${Rarity.entries.joinToString { it.key }}),
            packaging (one of: ${Packaging.entries.joinToString { it.key }}), condition (one of: ${Condition.entries.joinToString { it.key }}),
            notes. Suggest only what you are fairly sure of, and not values the form already has.
        """.trimIndent()

        /** The answer's words and its suggestions, from a ```suggest (or ```json) block anywhere in it. */
        fun splitSuggestions(answer: String): Pair<String, List<FieldSuggestion>> {
            val suggestions = mutableListOf<FieldSuggestion>()
            val text = BLOCK.replace(answer) { block ->
                val found = parse(block.groupValues[1])
                if (found == null) {
                    block.value
                } else {
                    suggestions += found
                    ""
                }
            }
            return text.replace(BLANK_LINES, "\n\n").trim() to suggestions.distinctBy { it.field }
        }

        /** Null when it isn't a list of suggestions (a code sample, say), so the text keeps it. */
        private fun parse(json: String): List<FieldSuggestion>? {
            val element = runCatching { Json.parseToJsonElement(json.trim()) }.getOrNull() ?: return null
            val items: List<JsonElement> = when (element) {
                is JsonArray -> element
                is JsonObject -> (element["changes"] ?: element["suggestions"]) as? JsonArray ?: listOf(element)
                else -> return null
            }
            val suggestions = items.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val field = obj.string("field")?.trim()?.lowercase()?.takeIf { it in FIELDS } ?: return@mapNotNull null
                val value = (obj["value"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                FieldSuggestion(field, value, obj.string("reason")?.trim()?.ifEmpty { null })
            }
            return suggestions.takeIf { items.isEmpty() || it.isNotEmpty() }
        }

        private val BLOCK = Regex("```[ \\t]*(?:suggest|json)?[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        private val BLANK_LINES = Regex("\\n{3,}")
    }
}
