package io.github.caropeboka.diecasttracker.data.backup

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.MAX_TAGS
import io.github.caropeboka.diecasttracker.data.MAX_TAG_LENGTH
import io.github.caropeboka.diecasttracker.data.MIN_YEAR
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastWithDetails
import io.github.caropeboka.diecasttracker.data.db.PriceCheckEntity
import io.github.caropeboka.diecasttracker.data.db.ShelfSlotEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.price.PriceAccuracy
import io.github.caropeboka.diecasttracker.data.price.PriceListing
import io.github.caropeboka.diecasttracker.data.price.PriceStats
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.util.groupThousands
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class BackupException(message: String) : Exception(message)

/**
 * `diecasts.json` of the backup zip, format version 1, as the web app writes it (`app/Services/CollectionBackup.php`).
 * Reading validates every record the way the web's restore does (`DiecastRequest` rules, `SightingLog::rules`).
 *
 * Backups made on the phone add keys the web's restore ignores (it keeps only the fields it validates): the market
 * value's source, timestamps, the Dex link as `dex_item` / `dex_match` (see [DexBackup]) and the price checks
 * (`price_checks`, what was read from the screenshots) and where it stands in the 3D garage (`shelf_slot`). The link is
 * never `catalog_item_id`, which the web would check against its own Dex.
 */
object WebBackupFormat {
    const val FORMAT = "diecast-tracker-backup"
    const val MANIFEST = "diecasts.json"
    @StringRes val NOT_A_BACKUP = R.string.backup_not_a_backup
    const val MAX_PHOTO_BYTES = 15L * 1024 * 1024

    private val PRETTY = Json { prettyPrint = true }
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /** "2026-10-21T14:05:00+07:00", like the web's `exported_at`. */
    fun timestamp(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(TIMESTAMP)

    fun parseTimestamp(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    fun manifest(records: List<JsonObject>, exportedAt: Long): String = PRETTY.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("exported_at", timestamp(exportedAt))
            put("diecasts", JsonArray(records))
        },
    )

    /**
     * One diecast with the web's fields in its order, then the phone's own keys. [photos] are the zip entries of its
     * photos that exist; [sightingPhoto] gives a sighting's entry, if its photo exists.
     */
    fun record(
        details: DiecastWithDetails,
        photos: List<String>,
        sightingPhoto: (SightingEntity) -> String?,
        priceChecks: List<PriceCheckEntity> = emptyList(),
        shelfSlot: ShelfSlotEntity? = null,
    ): JsonObject =
        buildJsonObject {
            val diecast = details.diecast
            put("name", diecast.name)
            put("brand", diecast.brand)
            put("make", diecast.make)
            put("series", diecast.series)
            put("collection_number", diecast.collectionNumber)
            put("year", diecast.year)
            put("scale", diecast.scale)
            put("color", diecast.color)
            put("rarity", diecast.rarity.key)
            put("packaging", diecast.packaging.key)
            put("condition", diecast.condition?.key)
            put("status", diecast.status.key)
            put("quantity", diecast.quantity)
            put("purchase_price", diecast.purchasePrice)
            put("market_value", diecast.marketValue)
            put("target_price", diecast.targetPrice)
            put("purchase_date", diecast.purchaseDate?.toString())
            put("purchase_place", diecast.purchasePlace)
            put("purchase_latitude", diecast.purchaseLatitude)
            put("purchase_longitude", diecast.purchaseLongitude)
            put("storage_location", diecast.storageLocation)
            put("notes", diecast.notes)
            put("ai_scanned", diecast.aiScanned)

            put("market_value_source", diecast.marketValueSource?.key)
            put("market_value_updated_at", diecast.marketValueUpdatedAt?.let(::timestamp))
            put("created_at", diecast.createdAt.takeIf { it > 0 }?.let(::timestamp))
            put("updated_at", diecast.updatedAt.takeIf { it > 0 }?.let(::timestamp))
            put("dex_item", diecast.catalogItemId)
            put("dex_match", diecast.catalogMatch?.key)
            put(
                "shelf_slot",
                shelfSlot?.let { slot ->
                    buildJsonObject {
                        put("shelf", slot.shelf.key)
                        put("spot", slot.spot)
                        put("position", slot.position)
                    }
                } ?: JsonNull,
            )

            putJsonArray("tags") { details.orderedTags.forEach { add(it.name) } }
            putJsonArray("photos") { photos.forEach { add(it) } }
            putJsonArray("sightings") {
                details.sightings.sortedBy { it.id }.forEach { sighting ->
                    addJsonObject {
                        put("place", sighting.place)
                        put("latitude", sighting.latitude)
                        put("longitude", sighting.longitude)
                        put("seen_on", sighting.seenOn.toString())
                        put("price", sighting.price)
                        put("notes", sighting.notes)
                        put("photo", sightingPhoto(sighting))
                        put("created_at", sighting.createdAt.takeIf { it > 0 }?.let(::timestamp))
                    }
                }
            }
            putJsonArray("price_checks") {
                priceChecks.forEach { check ->
                    addJsonObject {
                        put("screenshots", check.screenshots)
                        put("listings", PRICE_JSON.encodeToJsonElement(ListSerializer(PriceListing.serializer()), check.listings))
                        put("stats", check.stats?.let { PRICE_JSON.encodeToJsonElement(PriceStats.serializer(), it) } ?: JsonNull)
                        put("accuracy", check.accuracy.key)
                        putJsonArray("reasons") { check.reasons.forEach { add(it) } }
                        putJsonArray("tips") { check.tips.forEach { add(it) } }
                        put("ai_note", check.aiNote)
                        put("applied_at", check.appliedAt?.let(::timestamp))
                        put("created_at", timestamp(check.createdAt))
                    }
                }
            }
        }

    /** A price check of a phone backup, for no diecast yet; null when it can't be read (it is left out). */
    private fun parsePriceCheck(element: JsonElement): PriceCheckEntity? = runCatching {
        val json = element as JsonObject
        fun texts(key: String) = (json[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        PriceCheckEntity(
            diecastId = 0,
            screenshots = (json["screenshots"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            listings = PRICE_JSON.decodeFromJsonElement(ListSerializer(PriceListing.serializer()), json["listings"] ?: JsonArray(emptyList())),
            stats = json["stats"]?.takeIf { it !is JsonNull }?.let { PRICE_JSON.decodeFromJsonElement(PriceStats.serializer(), it) },
            accuracy = PriceAccuracy.fromKey((json["accuracy"] as? JsonPrimitive)?.contentOrNull),
            reasons = texts("reasons"),
            tips = texts("tips"),
            aiNote = (json["ai_note"] as? JsonPrimitive)?.contentOrNull,
            appliedAt = (json["applied_at"] as? JsonPrimitive)?.contentOrNull?.let(::parseTimestamp),
            createdAt = (json["created_at"] as? JsonPrimitive)?.contentOrNull?.let(::parseTimestamp) ?: return@runCatching null,
        )
    }.getOrNull()

    /** Where a diecast of a phone backup stood in the 3D garage, for no diecast yet; null when it can't be read. */
    private fun parseShelfSlot(element: JsonElement?): ShelfSlotEntity? {
        val json = element as? JsonObject ?: return null
        val shelf = Shelf.fromKey((json["shelf"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val spot = (json["spot"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0 until ShelfLayout.spots(shelf) } ?: return null
        return ShelfSlotEntity(0, shelf, spot, (json["position"] as? JsonPrimitive)?.intOrNull ?: 0)
    }

    private val PRICE_JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Only images stored under photos/ are taken from the zip. Wider than the web's own restore (JPEG only),
     * which drops the .webp photos its backups contain.
     */
    val PHOTO_ENTRY = Regex("photos/[A-Za-z0-9._-]+\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)

    class Manifest(val exportedOn: LocalDate?, val records: List<JsonElement>)

    sealed interface Record {
        data class Valid(
            /** With its market value source and Dex match when the backup has them; ids and timestamps unset. */
            val diecast: DiecastEntity,
            val tags: List<String>,
            /** Zip entry names, e.g. "photos/abc.jpg". */
            val photos: List<String>,
            val sightings: List<Sighting>,
            /** Phone backups: the Dex item's id in `dex.json`. */
            val dexItem: Long? = null,
            val createdAt: Long? = null,
            val updatedAt: Long? = null,
            /** Phone backups: for no diecast yet. */
            val priceChecks: List<PriceCheckEntity> = emptyList(),
            /** Phone backups: where it stood in the 3D garage, for no diecast yet. */
            val shelfSlot: ShelfSlotEntity? = null,
        ) : Record

        data class Invalid(val reason: String) : Record
    }

    /** [entity] has no diecast yet; [photo] is a zip entry name. */
    data class Sighting(val entity: SightingEntity, val photo: String?)

    fun parseManifest(text: String, texts: Texts): Manifest {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val records = root?.get("diecasts") as? JsonArray

        if ((root?.get("format") as? JsonPrimitive)?.contentOrNull != FORMAT || records == null) {
            throw BackupException(texts(NOT_A_BACKUP))
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
    fun parseRecord(index: Int, element: JsonElement, today: LocalDate, texts: Texts): Record {
        val fields = Fields(element as? JsonObject ?: JsonObject(emptyMap()), texts)

        val name = fields.text("name", R.string.field_name, 255, required = true)
        val diecast = DiecastEntity(
            name = name.orEmpty(),
            brand = fields.text("brand", R.string.field_brand, 100),
            make = fields.text("make", R.string.field_make, 100),
            series = fields.text("series", R.string.field_series, 255),
            collectionNumber = fields.text("collection_number", R.string.field_collection_number, 100),
            year = fields.integer("year", R.string.field_year, MIN_YEAR, today.year + 1),
            scale = fields.text("scale", R.string.field_scale, 20),
            color = fields.text("color", R.string.field_color, 100),
            rarity = fields.choice("rarity", R.string.field_rarity, Rarity::fromKey, required = true) ?: Rarity.Regular,
            packaging = fields.choice("packaging", R.string.field_packaging, Packaging::fromKey, required = true) ?: Packaging.Carded,
            condition = fields.choice("condition", R.string.field_condition, Condition::fromKey),
            status = fields.choice("status", R.string.field_status, DiecastStatus::fromKey, required = true) ?: DiecastStatus.Owned,
            quantity = fields.integer("quantity", R.string.field_quantity, 1, 9999, required = true) ?: 1,
            purchasePrice = fields.money("purchase_price", R.string.field_purchase_price),
            marketValue = fields.money("market_value", R.string.field_market_value),
            targetPrice = fields.money("target_price", R.string.field_target_price),
            purchaseDate = fields.date("purchase_date", R.string.field_purchase_date),
            purchasePlace = fields.text("purchase_place", R.string.field_purchase_place, 255),
            storageLocation = fields.text("storage_location", R.string.field_storage_location, 255),
            notes = fields.text("notes", R.string.field_notes, 5000),
            aiScanned = fields.boolean("ai_scanned", R.string.field_ai_scanned),
            catalogMatch = fields.extra("dex_match")?.let(CatalogMatch::fromKey),
        ).let { record ->
            val (latitude, longitude) = fields.coordinates("purchase_latitude", "purchase_longitude", R.string.field_purchase_coordinates)
            val valued = record.marketValue != null
            record.copy(
                purchaseLatitude = latitude,
                purchaseLongitude = longitude,
                marketValueSource = fields.extra("market_value_source")?.let(ValueSource::fromKey)?.takeIf { valued },
                marketValueUpdatedAt = fields.extra("market_value_updated_at")?.let(::parseTimestamp)?.takeIf { valued },
            )
        }
        val tags = fields.tags()

        fields.error?.let { return Record.Invalid(texts(R.string.backup_item_invalid, index + 1, name ?: "?", it)) }

        val photos = (fields.json["photos"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        val sightings = (fields.json["sightings"] as? JsonArray).orEmpty()
            .mapNotNull { parseSighting(it, today, texts) }

        return Record.Valid(
            diecast,
            tags,
            photos,
            sightings,
            dexItem = fields.extra("dex_item")?.toLongOrNull(),
            createdAt = fields.extra("created_at")?.let(::parseTimestamp),
            updatedAt = fields.extra("updated_at")?.let(::parseTimestamp),
            priceChecks = (fields.json["price_checks"] as? JsonArray).orEmpty().mapNotNull(::parsePriceCheck),
            shelfSlot = parseShelfSlot(fields.json["shelf_slot"]),
        )
    }

    /** Invalid sightings are left out, like the web's restore does. */
    private fun parseSighting(element: JsonElement, today: LocalDate, texts: Texts): Sighting? {
        val fields = Fields(element as? JsonObject ?: return null, texts)

        val seenOn = fields.date("seen_on", R.string.field_date, required = true, notAfter = today)
        val (latitude, longitude) = fields.coordinates("latitude", "longitude", R.string.field_coordinates)
        val entity = SightingEntity(
            diecastId = 0,
            place = fields.text("place", R.string.field_place, 255),
            latitude = latitude,
            longitude = longitude,
            seenOn = seenOn ?: today,
            price = fields.money("price", R.string.field_price),
            notes = fields.text("notes", R.string.field_notes, 500),
            createdAt = fields.extra("created_at")?.let(::parseTimestamp) ?: 0,
        )

        if (fields.error != null) return null

        return Sighting(entity, (fields.json["photo"] as? JsonPrimitive)?.contentOrNull)
    }

    /** Reads typed values out of one JSON object and keeps the first validation error. */
    private class Fields(val json: JsonObject, private val texts: Texts) {
        var error: String? = null
            private set

        private fun fail(message: String) {
            if (error == null) error = message
        }

        /** The value as text, or null when missing, null or blank. Objects/arrays/booleans are an error. */
        private fun raw(key: String, @StringRes label: Int): String? {
            val value = json[key]
            if (value == null || value is JsonNull) return null
            if (value !is JsonPrimitive || (!value.isString && value.content in setOf("true", "false"))) {
                fail(texts(R.string.valid_invalid, texts(label)))
                return null
            }
            return value.content.trim().ifEmpty { null }
        }

        /** A key only phone backups have: a value that doesn't fit is left out instead of failing the record. */
        fun extra(key: String): String? = (json[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

        fun text(key: String, @StringRes label: Int, max: Int, required: Boolean = false): String? {
            val value = raw(key, label)
            when {
                value == null -> if (required) fail(texts(R.string.valid_required, texts(label)))
                value.length > max -> fail(texts(R.string.valid_max_chars, texts(label), max))
                else -> return value
            }
            return null
        }

        fun integer(key: String, @StringRes label: Int, min: Int, max: Int, required: Boolean = false): Int? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail(texts(R.string.valid_required, texts(label)))
                return null
            }
            val number = value.toIntOrNull()
            when {
                number == null -> fail(texts(R.string.valid_integer, texts(label)))
                number < min || number > max -> fail(texts(R.string.valid_between, texts(label), min.toString(), max.toString()))
                else -> return number
            }
            return null
        }

        /** Rupiah, rounded to whole numbers ("35000.00" → 35000). */
        fun money(key: String, @StringRes label: Int): Long? {
            val value = raw(key, label) ?: return null
            val number = value.toBigDecimalOrNull()
            when {
                number == null -> fail(texts(R.string.valid_number, texts(label)))
                number < BigDecimal.ZERO || number > BigDecimal.valueOf(MAX_MONEY) ->
                    fail(texts(R.string.valid_between, texts(label), "0", groupThousands(MAX_MONEY)))
                else -> return number.setScale(0, RoundingMode.HALF_UP).toLong()
            }
            return null
        }

        fun date(key: String, @StringRes label: Int, required: Boolean = false, notAfter: LocalDate? = null): LocalDate? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail(texts(R.string.valid_required, texts(label)))
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
                date == null -> fail(texts(R.string.valid_date, texts(label)))
                notAfter != null && date > notAfter -> fail(texts(R.string.valid_not_future, texts(label)))
                else -> return date
            }
            return null
        }

        fun <T> choice(key: String, @StringRes label: Int, parse: (String) -> T?, required: Boolean = false): T? {
            val value = raw(key, label)
            if (value == null) {
                if (required) fail(texts(R.string.valid_required, texts(label)))
                return null
            }
            return parse(value) ?: null.also { fail(texts(R.string.valid_unknown, texts(label), value)) }
        }

        fun boolean(key: String, @StringRes label: Int): Boolean {
            val value = json[key]
            if (value == null || value is JsonNull) return false
            return when ((value as? JsonPrimitive)?.content) {
                "true", "1" -> true
                "false", "0" -> false
                else -> false.also { fail(texts(R.string.valid_boolean, texts(label))) }
            }
        }

        /** Both or neither, within range. */
        fun coordinates(latitudeKey: String, longitudeKey: String, @StringRes label: Int): Pair<Double?, Double?> {
            val latitude = raw(latitudeKey, label)?.let { it.toDoubleOrNull() ?: Double.NaN }
            val longitude = raw(longitudeKey, label)?.let { it.toDoubleOrNull() ?: Double.NaN }
            when {
                latitude == null && longitude == null -> {}
                latitude == null || longitude == null -> fail(texts(R.string.valid_incomplete, texts(label)))
                latitude.isNaN() || longitude.isNaN() -> fail(texts(R.string.valid_number, texts(label)))
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> fail(texts(R.string.valid_out_of_range, texts(label)))
                else -> return latitude to longitude
            }
            return null to null
        }

        fun tags(): List<String> {
            val value = json["tags"]
            if (value == null || value is JsonNull) return emptyList()
            if (value !is JsonArray) {
                fail(texts(R.string.valid_tags_list))
                return emptyList()
            }
            if (value.size > MAX_TAGS) fail(texts(R.string.valid_tags_max, MAX_TAGS))

            return value.mapNotNull { tag ->
                val name = (tag as? JsonPrimitive)?.takeIf { it.isString }?.content
                when {
                    name == null -> null.also { fail(texts(R.string.valid_tags_text)) }
                    name.length > MAX_TAG_LENGTH -> null.also { fail(texts(R.string.valid_tag_length, MAX_TAG_LENGTH)) }
                    else -> name
                }
            }
        }

        companion object {
            private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        }
    }
}
