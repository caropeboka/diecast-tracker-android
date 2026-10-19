package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.MAX_TAGS
import io.github.caropeboka.diecasttracker.data.MAX_TAG_LENGTH
import io.github.caropeboka.diecasttracker.data.MIN_YEAR
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.util.groupThousands
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

class BackupException(message: String) : Exception(message)

/**
 * Reads `diecasts.json` of the web app's backup zip (`app/Services/CollectionBackup.php`, format version 1)
 * and validates every record the way the web's restore does (`DiecastRequest` rules, `SightingLog::rules`).
 */
object WebBackupFormat {
    const val FORMAT = "diecast-tracker-backup"
    const val MANIFEST = "diecasts.json"
    const val NOT_A_BACKUP = "Ini bukan file backup Diecast Tracker."

    /**
     * Only images stored under photos/ are taken from the zip. Wider than the web's own restore (JPEG only),
     * which drops the .webp photos its backups contain.
     */
    val PHOTO_ENTRY = Regex("photos/[A-Za-z0-9._-]+\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)

    class Manifest(val exportedOn: LocalDate?, val records: List<JsonElement>)

    sealed interface Record {
        data class Valid(
            val diecast: DiecastEntity,
            val tags: List<String>,
            /** Zip entry names, e.g. "photos/abc.jpg". */
            val photos: List<String>,
            val sightings: List<Sighting>,
        ) : Record

        data class Invalid(val reason: String) : Record
    }

    /** [entity] has no diecast yet; [photo] is a zip entry name. */
    data class Sighting(val entity: SightingEntity, val photo: String?)

    fun parseManifest(text: String): Manifest {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val records = root?.get("diecasts") as? JsonArray

        if ((root?.get("format") as? JsonPrimitive)?.contentOrNull != FORMAT || records == null) {
            throw BackupException(NOT_A_BACKUP)
        }

        val exportedOn = (root["exported_at"] as? JsonPrimitive)?.contentOrNull?.let {
            try {
                OffsetDateTime.parse(it).toLocalDate()
            } catch (_: DateTimeParseException) {
                null
            }
        }

        return Manifest(exportedOn, records)
    }

    /** [index] is zero-based; messages count from one like the web app. */
    fun parseRecord(index: Int, element: JsonElement, today: LocalDate): Record {
        val fields = Fields(element as? JsonObject ?: JsonObject(emptyMap()))

        val name = fields.text("name", "Nama", 255, required = true)
        val diecast = DiecastEntity(
            name = name.orEmpty(),
            brand = fields.text("brand", "Merek", 100),
            make = fields.text("make", "Pabrikan", 100),
            series = fields.text("series", "Seri", 255),
            collectionNumber = fields.text("collection_number", "Nomor koleksi", 100),
            year = fields.integer("year", "Tahun", MIN_YEAR, today.year + 1),
            scale = fields.text("scale", "Skala", 20),
            color = fields.text("color", "Warna", 100),
            rarity = fields.choice("rarity", "Kelangkaan", Rarity::fromKey, required = true) ?: Rarity.Regular,
            packaging = fields.choice("packaging", "Kemasan", Packaging::fromKey, required = true) ?: Packaging.Carded,
            condition = fields.choice("condition", "Kondisi", Condition::fromKey),
            status = fields.choice("status", "Status", DiecastStatus::fromKey, required = true) ?: DiecastStatus.Owned,
            quantity = fields.integer("quantity", "Jumlah", 1, 9999, required = true) ?: 1,
            purchasePrice = fields.money("purchase_price", "Harga beli"),
            marketValue = fields.money("market_value", "Harga pasaran"),
            targetPrice = fields.money("target_price", "Target harga"),
            purchaseDate = fields.date("purchase_date", "Tanggal beli"),
            purchasePlace = fields.text("purchase_place", "Tempat beli", 255),
            storageLocation = fields.text("storage_location", "Lokasi simpan", 255),
            notes = fields.text("notes", "Catatan", 5000),
            aiScanned = fields.boolean("ai_scanned", "ai_scanned"),
        ).let { record ->
            val (latitude, longitude) = fields.coordinates("purchase_latitude", "purchase_longitude", "Koordinat tempat beli")
            record.copy(purchaseLatitude = latitude, purchaseLongitude = longitude)
        }
        val tags = fields.tags()

        fields.error?.let { return Record.Invalid("Item ${index + 1} (${name ?: "?"}): $it") }

        val photos = (fields.json["photos"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        val sightings = (fields.json["sightings"] as? JsonArray).orEmpty()
            .mapNotNull { parseSighting(it, today) }

        return Record.Valid(diecast, tags, photos, sightings)
    }

    /** Invalid sightings are left out, like the web's restore does. */
    private fun parseSighting(element: JsonElement, today: LocalDate): Sighting? {
        val fields = Fields(element as? JsonObject ?: return null)

        val seenOn = fields.date("seen_on", "Tanggal", required = true, notAfter = today)
        val (latitude, longitude) = fields.coordinates("latitude", "longitude", "Koordinat")
        val entity = SightingEntity(
            diecastId = 0,
            place = fields.text("place", "Tempat", 255),
            latitude = latitude,
            longitude = longitude,
            seenOn = seenOn ?: today,
            price = fields.money("price", "Harga"),
            notes = fields.text("notes", "Catatan", 500),
        )

        if (fields.error != null) return null

        return Sighting(entity, (fields.json["photo"] as? JsonPrimitive)?.contentOrNull)
    }

    /** Reads typed values out of one JSON object and keeps the first validation error. */
    private class Fields(val json: JsonObject) {
        var error: String? = null
            private set

        private fun fail(message: String) {
            if (error == null) error = message
        }

        /** The value as text, or null when missing, null or blank. Objects/arrays/booleans are an error. */
        private fun raw(key: String, label: String): String? {
            val value = json[key]
            if (value == null || value is JsonNull) return null
            if (value !is JsonPrimitive || (!value.isString && value.content in setOf("true", "false"))) {
                fail("$label tidak valid.")
                return null
            }
            return value.content.trim().ifEmpty { null }
        }

        fun text(key: String, label: String, max: Int, required: Boolean = false): String? {
            val value = raw(key, label)
            when {
                value == null -> if (required) fail("$label wajib diisi.")
                value.length > max -> fail("$label maksimal $max karakter.")
                else -> return value
            }
            return null
        }

        fun integer(key: String, label: String, min: Int, max: Int, required: Boolean = false): Int? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail("$label wajib diisi.")
                return null
            }
            val number = value.toIntOrNull()
            when {
                number == null -> fail("$label harus bilangan bulat.")
                number < min || number > max -> fail("$label harus antara $min dan $max.")
                else -> return number
            }
            return null
        }

        /** Rupiah, rounded to whole numbers ("35000.00" → 35000). */
        fun money(key: String, label: String): Long? {
            val value = raw(key, label) ?: return null
            val number = value.toBigDecimalOrNull()
            when {
                number == null -> fail("$label harus berupa angka.")
                number < BigDecimal.ZERO || number > BigDecimal.valueOf(MAX_MONEY) ->
                    fail("$label harus antara 0 dan ${groupThousands(MAX_MONEY)}.")
                else -> return number.setScale(0, RoundingMode.HALF_UP).toLong()
            }
            return null
        }

        fun date(key: String, label: String, required: Boolean = false, notAfter: LocalDate? = null): LocalDate? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail("$label wajib diisi.")
                return null
            }
            val date = DATE.matchAt(value, 0)?.let {
                try {
                    LocalDate.parse(it.value)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
            when {
                date == null -> fail("$label bukan tanggal yang valid.")
                notAfter != null && date > notAfter -> fail("$label tidak boleh setelah hari ini.")
                else -> return date
            }
            return null
        }

        fun <T> choice(key: String, label: String, parse: (String) -> T?, required: Boolean = false): T? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail("$label wajib diisi.")
                return null
            }
            return parse(value) ?: null.also { fail("$label tidak dikenal: \"$value\".") }
        }

        fun boolean(key: String, label: String): Boolean {
            val value = json[key]
            if (value == null || value is JsonNull) return false
            return when ((value as? JsonPrimitive)?.content) {
                "true", "1" -> true
                "false", "0" -> false
                else -> false.also { fail("$label harus true atau false.") }
            }
        }

        /** Both or neither, within range. */
        fun coordinates(latitudeKey: String, longitudeKey: String, label: String): Pair<Double?, Double?> {
            val latitude = raw(latitudeKey, label)?.let { it.toDoubleOrNull() ?: Double.NaN }
            val longitude = raw(longitudeKey, label)?.let { it.toDoubleOrNull() ?: Double.NaN }
            when {
                latitude == null && longitude == null -> {}
                latitude == null || longitude == null -> fail("$label tidak lengkap.")
                latitude.isNaN() || longitude.isNaN() -> fail("$label harus berupa angka.")
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> fail("$label di luar jangkauan.")
                else -> return latitude to longitude
            }
            return null to null
        }

        fun tags(): List<String> {
            val value = json["tags"]
            if (value == null || value is JsonNull) return emptyList()
            if (value !is JsonArray) {
                fail("Tag harus berupa daftar.")
                return emptyList()
            }
            if (value.size > MAX_TAGS) fail("Tag maksimal $MAX_TAGS.")

            return value.mapNotNull { tag ->
                val name = (tag as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    name == null -> null.also { fail("Tag harus berupa teks.") }
                    name.length > MAX_TAG_LENGTH -> null.also { fail("Tag maksimal $MAX_TAG_LENGTH karakter.") }
                    else -> name
                }
            }
        }

        companion object {
            private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        }
    }
}
