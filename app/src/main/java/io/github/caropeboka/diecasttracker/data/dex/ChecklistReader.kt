package io.github.caropeboka.diecasttracker.data.dex

import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.ai.AiVision
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Screenshots read per go (web: `CatalogImport::MAX_SCREENSHOTS`). */
const val MAX_CHECKLIST_IMAGES = 6

/**
 * Reads a release checklist (an Instagram post, a catalog page, a store's pre-order list, a printed sheet) into
 * rows for an own dex (web: `CatalogImport::fromScreenshots`). The rows are reviewed before they're added.
 */
class ChecklistReader(private val vision: AiVision) {
    val enabled: Flow<Boolean> = vision.enabled

    /** [images]: JPEGs. Rows without a name are left out. */
    suspend fun read(catalog: CatalogEntity, images: List<ByteArray>, onEvent: (AiEvent) -> Unit = {}): List<DexRow> {
        val sent = images.take(MAX_CHECKLIST_IMAGES)
        val data = vision.extract(sent, SYSTEM_PROMPT, instruction(catalog, sent.size), SCHEMA, onEvent).data

        return data.array("items").orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun text(name: String) = item.string(name)?.trim()?.ifEmpty { null }
            DexRow(
                number = text("number"),
                name = text("name") ?: return@mapNotNull null,
                series = text("series"),
                seriesNumber = text("series_number"),
                variantLabel = text("variant"),
            )
        }
    }

    private fun instruction(catalog: CatalogEntity, count: Int): String {
        val what = "${catalog.brand} — ${catalog.name}" + (catalog.year?.let { " ($it)" } ?: "")
        return "These $count image(s) show a release checklist for: $what. List every release."
    }

    private companion object {
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
