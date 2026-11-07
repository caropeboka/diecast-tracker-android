package io.github.caropeboka.diecasttracker.data.dex

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.ai.AiVision
import io.github.caropeboka.diecasttracker.data.ai.ChatTurn
import io.github.caropeboka.diecasttracker.data.ai.WebSource
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.util.array
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Screenshots read per go (web: `CatalogImport::MAX_SCREENSHOTS`). */
const val MAX_CHECKLIST_IMAGES = 6

/**
 * A release list the AI looked up on the web, with what it said about it; [searched] false when the provider answered
 * from what it knows, without a web search.
 */
class WebChecklist(val rows: List<DexRow>, val note: String, val sources: List<WebSource>, val by: String, val searched: Boolean)

/**
 * Reads a release checklist (an Instagram post, a catalog page, a store's pre-order list, a printed sheet) into
 * rows for an own dex (web: `CatalogImport::fromScreenshots`), or looks one up on the web for a line no wiki lists.
 * The rows are reviewed before they're added.
 */
class ChecklistReader(private val vision: AiVision, private val texts: Texts) {
    val enabled: Flow<Boolean> = vision.enabled

    /** [images]: JPEGs. Rows without a name are left out. */
    suspend fun read(catalog: CatalogEntity, images: List<ByteArray>, onEvent: (AiEvent) -> Unit = {}): List<DexRow> {
        val sent = images.take(MAX_CHECKLIST_IMAGES)
        val data = vision.extract(sent, SYSTEM_PROMPT, instruction(catalog, sent.size), SCHEMA, onEvent).data
        return rows(data)
    }

    /** Asks the AI (Gemini with Google Search where it can) for the releases of [query], or of the dex itself. */
    suspend fun search(catalog: CatalogEntity, query: String, onEvent: (AiEvent) -> Unit = {}): WebChecklist {
        val what = query.trim().ifEmpty { describe(catalog) }
        val question = "Find the list of releases for: $what\nThe list goes into the collector's dex \"${describe(catalog)}\"."
        val reply = vision.chat(emptyList(), SEARCH_PROMPT.replace("{language}", texts(R.string.ai_answer_language)), listOf(ChatTurn(fromUser = true, question)), onEvent)
        val (note, data) = splitList(reply.text)
        return WebChecklist(data?.let(::rows).orEmpty(), note, reply.sources, reply.by, reply.sources.isNotEmpty())
    }

    private fun describe(catalog: CatalogEntity) = "${catalog.brand} — ${catalog.name}" + (catalog.year?.let { " ($it)" } ?: "")

    private fun instruction(catalog: CatalogEntity, count: Int): String =
        "These $count image(s) show a release checklist for: ${describe(catalog)}. List every release."

    private companion object {
        fun rows(data: JsonObject): List<DexRow> = data.array("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = (item[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.ifEmpty { null }
            DexRow(
                number = text("number"),
                name = text("name") ?: return@mapNotNull null,
                series = text("series"),
                seriesNumber = text("series_number"),
                variantLabel = text("variant"),
            )
        }

        /** The answer's text without its ```json block, and that block (`{"items": […]}` or just the array). */
        fun splitList(answer: String): Pair<String, JsonObject?> {
            var data: JsonObject? = null
            val text = LIST_BLOCK.replace(answer) { block ->
                val json = runCatching { Json.parseToJsonElement(block.groupValues[1].trim()) }.getOrNull()
                val found = when (json) {
                    is JsonObject -> json.takeIf { "items" in it }
                    is JsonArray -> JsonObject(mapOf("items" to json))
                    else -> null
                } ?: return@replace block.value
                if (data == null) data = found
                ""
            }
            return text.replace(Regex("\n{3,}"), "\n\n").trim() to data
        }

        private val LIST_BLOCK = Regex("```[ \\t]*(?:json)?[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

        private val SEARCH_PROMPT = """
            You look up release lists of diecast lines on the web for a collector who builds a checklist of a brand's
            releases (for example Mini GT's releases of 2024, Tarmac Works' Hobby64 line, Tomica Premium, Inno64,
            Pop Race, Maisto's 1:64 lines). Search the brand's own site, fan wikis, collectors' sites and shops' listings,
            and list every release you can confirm for what is asked, in catalog order. Never invent a release: leave out
            what you can't confirm.

            Answer with a short paragraph in {language} on what you found, where, and how complete it looks; then one
            code block marked json:
            ```json
            {"items": [{"number": "MGT00512", "name": "Nissan Skyline GT-R (R34) Nismo", "series": null, "series_number": null, "variant": "Bayside Blue"}]}
            ```
            For each release:
            - number: the product / catalog number as the brand prints it (e.g. "MGT00512", "T64-084", "No. 23"); null if none.
            - name: the model name, without the brand.
            - series: the line or sub-series, else null.
            - series_number: a number within a series like "3/5", else null.
            - variant: what tells this release apart from others of the same model (color, livery, "LHD", "Chase",
              "Exclusive"), else null.
            If you found nothing reliable, say so and give an empty list.
        """.trimIndent()

        val SYSTEM_PROMPT = """
            You read checklists of diecast releases for a collector: screenshots or photos of a brand's release list,
            an Instagram checklist post, a catalog page, a store's pre-order list, or a printed sheet.

            List EVERY release shown, in the order shown. Never invent releases that aren't visible. If the same release
            appears twice (overlapping screenshots), list it once.

            For each release:
            - number: the product / catalog number exactly as printed (e.g. "MGT00512", "T64-084", "No. 23", "#12"); null if none.
            - name: the model name as printed (e.g. "Nissan Skyline GT-R (R34) Nismo"), without the brand.
            - series: the line or sub-series if shown, else null.
            - series_number: a number within a series like "3/5" if shown, else null.
            - variant: what tells this release apart from others of the same model (color, livery, "LHD", "Chase",
              "Exclusive"), else null.
            Ignore prices, dates, hashtags and other text that isn't a release.
        """.trimIndent()

        val SCHEMA: JsonObject = run {
            val nullable = buildJsonObject {
                putJsonArray("anyOf") {
                    addJsonObject { put("type", "string") }
                    addJsonObject { put("type", "null") }
                }
            }
            val fields = linkedMapOf(
                "number" to nullable,
                "name" to buildJsonObject { put("type", "string") },
                "series" to nullable,
                "series_number" to nullable,
                "variant" to nullable,
            )

            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("items") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            put("properties", JsonObject(fields))
                            putJsonArray("required") { fields.keys.forEach { add(it) } }
                            put("additionalProperties", false)
                        }
                    }
                }
                putJsonArray("required") { add("items") }
                put("additionalProperties", false)
            }
        }
    }
}
