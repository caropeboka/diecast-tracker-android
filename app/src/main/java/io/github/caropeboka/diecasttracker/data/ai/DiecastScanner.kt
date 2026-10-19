package io.github.caropeboka.diecasttracker.data.ai

import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.MIN_YEAR
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.Rarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate

/** Photos sent per scan; front + back of the card + the loose car is plenty. */
const val MAX_SCAN_PHOTOS = 4

enum class Confidence(val key: String, val label: String) {
    High("high", "Yakin"),
    Medium("medium", "Cukup yakin"),
    Low("low", "Kurang yakin — cek lagi"),
}

/** What the AI read from the photos, with values outside the allowed options dropped so the form never gets junk. */
data class ScanFields(
    val name: String? = null,
    val brand: String? = null,
    val make: String? = null,
    val series: String? = null,
    val collectionNumber: String? = null,
    val year: Int? = null,
    val scale: String? = null,
    val color: String? = null,
    val rarity: Rarity? = null,
    val packaging: Packaging? = null,
    val condition: Condition? = null,
    val notes: String? = null,
    val confidence: Confidence = Confidence.Medium,
) {
    companion object {
        fun from(data: JsonObject, currentYear: Int): ScanFields {
            fun text(key: String) = data.string(key)?.trim()?.ifEmpty { null }

            val year = (data["year"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toIntOrNull() }

            return ScanFields(
                name = text("name"),
                brand = text("brand"),
                make = text("make"),
                series = text("series"),
                collectionNumber = text("collection_number"),
                year = year?.takeIf { it in MIN_YEAR..currentYear + 1 },
                scale = text("scale"),
                color = text("color"),
                rarity = Rarity.entries.find { it.key == text("rarity") },
                packaging = Packaging.entries.find { it.key == text("packaging") },
                condition = Condition.entries.find { it.key == text("condition") },
                notes = text("notes"),
                confidence = Confidence.entries.find { it.key == text("confidence") } ?: Confidence.Medium,
            )
        }
    }
}

/** Identifies a diecast from photos of it and returns the form fields it can fill in (web: `DiecastScanner.php`). */
class DiecastScanner(
    private val settings: Flow<GeminiSettings>,
    private val gemini: GeminiClient,
    private val photos: PhotoStore,
    private val currentYear: () -> Int = { LocalDate.now().year },
) {
    val enabled: Flow<Boolean> = settings.map { it.isEnabled }.distinctUntilChanged()

    suspend fun isEnabled(): Boolean = settings.first().isEnabled

    /** Reads the first [MAX_SCAN_PHOTOS] photos (file names in the photo store) as one diecast. */
    suspend fun scan(photoNames: List<String>): ScanFields {
        val config = settings.first()
        val key = config.key?.takeIf { config.isEnabled }
            ?: throw AiException("Scan AI belum aktif. Isi API key di Pengaturan AI.")

        val images = withContext(Dispatchers.IO) {
            photoNames.take(MAX_SCAN_PHOTOS).map { photos.file(it).readBytes() }
        }
        val data = gemini.extract(key, config.model, images, SYSTEM_PROMPT, instruction(images.size), SCHEMA)

        return ScanFields.from(data, currentYear())
    }

    private fun instruction(photoCount: Int) =
        if (photoCount > 1) {
            "These $photoCount photos all show the same diecast (for example the front and back of the card, or the car out of " +
                "the package). Combine what you can read from all of them, identify it and fill in the fields."
        } else {
            "Identify this diecast and fill in the fields."
        }

    private companion object {
        val SYSTEM_PROMPT = """
            You are an expert diecast car collector who identifies model cars from photos for a collection tracker.

            Read everything visible: the car itself, the blister card or box, printed text, logos, numbers and stickers.
            Fill in only what you can see or confidently recognize. Use null for anything you cannot determine. A wrong
            value is worse than an empty one, because the collector will trust what you fill in.

            Field guidance:
            - name: the casting name exactly as printed on the package (e.g. "'71 Datsun 510", "Nissan Skyline GT-R (BNR34)").
              For a loose car, use the most specific name you recognize.
            - brand: the diecast maker (Hot Wheels, Matchbox, Tomica, Mini GT, Inno64, Tarmac Works, Pop Race, Kaido House,
              Majorette, Greenlight, Maisto, Johnny Lightning, M2 Machines, Auto World, Bburago, etc.).
            - make: the real-world car manufacturer (Nissan, Toyota, Porsche, ...).
            - series: the line or sub-series printed on the card (e.g. "HW J-Imports", "Car Culture: Modern Classics", "Boulevard").
            - collection_number: numbers as printed, e.g. "123/250" or "3/10"; for Tomica use "No. 23"; for Mini GT "MGT00512".
              If both a mainline number and a series number are visible, give both separated by " · ".
            - year: the release year from the card's copyright/date code if visible. Not the real car's model year.
            - scale: e.g. "1:64". Hot Wheels, Matchbox, Mini GT, Inno64 and Tarmac Works are 1:64 unless the package says otherwise.
            - color: main body color in English, short (e.g. "Metallic Blue", "White with red stripes").
            - rarity: "treasure_hunt" for a Hot Wheels mainline with the low-production circle-flame logo on the card or car;
              "super_treasure_hunt" for Spectraflame paint + Real Riders rubber tires + "TH" tampo / gold flame on the card;
              "chase" for a chase piece (Mini GT/Matchbox/Greenlight chase stickers, green machines, raw/zamac chase);
              "limited" for numbered limited runs; "exclusive" for store/event exclusives (e.g. RLC, convention, Target red);
              otherwise "regular".
            - packaging: "carded" (blister card), "boxed" (window box / box), or "loose".
            - condition: judge the packaging and car: "mint" (perfect), "near_mint" (tiny flaws), "good" (visible wear,
              soft corners, small blister dents), "fair" (creases, cracked blister), "damaged". null if you can't tell.
            - notes: one or two short sentences in Indonesian about notable details (variations, tampo, wheel type, card
              damage). null if nothing notable.
            - confidence: your overall confidence in the identification.
        """.trimIndent()

        val SCHEMA: JsonObject = run {
            fun type(name: String) = buildJsonObject { put("type", name) }

            fun enum(values: List<String>) = buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { values.forEach { add(it) } }
            }

            fun nullable(type: JsonObject) = buildJsonObject {
                putJsonArray("anyOf") {
                    add(type)
                    addJsonObject { put("type", "null") }
                }
            }

            val string = nullable(type("string"))
            val properties = linkedMapOf(
                "name" to string,
                "brand" to string,
                "make" to string,
                "series" to string,
                "collection_number" to string,
                "year" to nullable(type("integer")),
                "scale" to string,
                "color" to string,
                "rarity" to enum(Rarity.entries.map { it.key }),
                "packaging" to enum(Packaging.entries.map { it.key }),
                "condition" to nullable(enum(Condition.entries.map { it.key })),
                "notes" to string,
                "confidence" to enum(Confidence.entries.map { it.key }),
            )

            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                putJsonArray("required") { properties.keys.forEach { add(it) } }
                put("additionalProperties", false)
            }
        }
    }
}
