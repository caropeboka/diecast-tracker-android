package io.github.caropeboka.diecasttracker.data.price

import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.PriceCheckEntity
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.roundToLong

/** Screenshots read per check (web: `PriceChecker::MAX_SCREENSHOTS`). */
const val MAX_PRICE_SCREENSHOTS = 6

/**
 * Reads marketplace screenshots (Tokopedia, Shopee…) with the AI and records a price check for a diecast
 * (web: `PriceChecker.php`); [PriceAnalyzer] does the sums.
 */
class PriceChecker(
    private val settings: Flow<GeminiSettings>,
    private val gemini: GeminiClient,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.priceChecks()

    val enabled: Flow<Boolean> = settings.map { it.isEnabled }.distinctUntilChanged()

    fun observe(diecastId: Long): Flow<List<PriceCheckEntity>> = dao.observeFor(diecastId)

    /** [images]: JPEG screenshots; at most [MAX_PRICE_SCREENSHOTS] are read. */
    suspend fun check(diecast: DiecastEntity, images: List<ByteArray>): PriceCheckEntity {
        val config = settings.first()
        val key = config.key?.takeIf { config.isEnabled } ?: throw AiException("AI belum aktif. Isi API key di Pengaturan AI.")

        val sent = images.take(MAX_PRICE_SCREENSHOTS)
        val read = gemini.extract(key, config.model, sent, SYSTEM_PROMPT, instruction(diecast, sent.size), SCHEMA)

        val analysis = PriceAnalyzer.analyze(read.array("listings").orEmpty().mapNotNull { (it as? JsonObject)?.let(::listing) })
        val hint = read.string("search_hint")?.trim()?.ifEmpty { null }
        val check = PriceCheckEntity(
            diecastId = diecast.id,
            screenshots = sent.size,
            listings = analysis.listings,
            stats = analysis.stats,
            accuracy = analysis.accuracy,
            reasons = analysis.reasons,
            tips = analysis.tips + listOfNotNull(hint?.let { "Coba cari dengan kata kunci: \"$it\"." }),
            aiNote = read.string("note")?.trim()?.ifEmpty { null },
            createdAt = clock(),
        )
        return check.copy(id = dao.insert(check))
    }

    /** Uses the check's suggested price (or [value]) as the diecast's market value; returns the diecast. */
    suspend fun apply(check: PriceCheckEntity, value: Long? = null): DiecastEntity {
        val price = value ?: check.stats?.suggested ?: throw IllegalStateException("Belum ada harga untuk dipakai.")
        val now = clock()

        return db.withTransaction {
            dao.setMarketValue(check.diecastId, price, now)
            dao.markApplied(check.id, now)
            checkNotNull(db.diecasts().get(check.diecastId)) { "Diecast ${check.diecastId} not found" }
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** One listing as the AI read it; numbers it wrote as text or decimals still count. */
    private fun listing(json: JsonObject): PriceListing? {
        fun number(name: String): Double? = (json[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        fun flag(name: String) = (json[name] as? JsonPrimitive)?.booleanOrNull ?: false
        fun text(name: String) = json.string(name)?.trim()?.ifEmpty { null }

        return PriceListing(
            title = text("title") ?: return null,
            price = number("price_idr")?.roundToLong()?.coerceAtLeast(0) ?: 0,
            isPriceRange = flag("is_price_range"),
            shop = text("shop"),
            location = text("location"),
            sold = number("sold")?.toInt(),
            rating = number("rating"),
            isAd = flag("is_ad"),
            marketplace = text("marketplace") ?: "other",
            match = ListingMatch.fromKey(text("match")),
            reason = text("reason"),
        )
    }

    private fun instruction(diecast: DiecastEntity, count: Int): String {
        val describe = listOf(
            "Name" to diecast.name,
            "Brand" to diecast.brand,
            "Car maker" to diecast.make,
            "Series" to diecast.series,
            "Collection number" to diecast.collectionNumber,
            "Release year" to diecast.year?.toString(),
            "Color" to diecast.color,
            "Scale" to diecast.scale,
            "Variant / rarity" to diecast.rarity.key,
            "Condition" to diecast.packaging.key,
        ).filter { (_, value) -> !value.isNullOrBlank() }.joinToString("\n") { (label, value) -> "- $label: $value" }

        return "Target diecast:\n$describe\n\nRead all listings in these $count screenshot(s)."
    }

    companion object {
        /** Keywords that usually find the right listings on Tokopedia/Shopee (web: `searchQuery`). */
        fun searchQuery(diecast: DiecastEntity): String = listOf(
            diecast.brand,
            diecast.name.replace(Regex("['\"]"), ""),
            when (diecast.rarity) {
                Rarity.SuperTreasureHunt -> "super treasure hunt"
                Rarity.TreasureHunt -> "treasure hunt"
                Rarity.Chase -> "chase"
                else -> null
            },
            "loose".takeIf { diecast.packaging == Packaging.Loose },
        ).filter { !it.isNullOrBlank() }.joinToString(" ")

        private val SYSTEM_PROMPT = """
            You help an Indonesian diecast collector estimate market prices. You are given screenshots of marketplace
            search results or product pages (usually Tokopedia, sometimes Shopee, Bukalapak or Facebook Marketplace)
            and a description of the diecast being priced.

            Extract EVERY product listing visible in the screenshots — also the ones that are not this diecast — exactly
            as shown. Never invent listings or guess prices that aren't visible. If the same listing appears in two
            overlapping screenshots, list it once.

            For each listing:
            - title: the product title as shown (it may be cut off).
            - price_idr: the price a buyer pays now, as a plain integer in rupiah ("Rp45.000" → 45000, "Rp1,2jt" → 1200000).
              If a discount is shown, use the discounted price, not the struck-through one. If a price range is shown
              ("Rp35.000 - Rp60.000"), use the lowest price and set is_price_range to true.
            - shop: the shop / seller name if visible, else null. location: the city if visible, else null.
            - sold: how many sold as an integer ("10+ terjual" → 10, "1rb+ terjual" → 1000, "250 terjual" → 250), null if not shown.
            - rating: the rating number if shown, else null. is_ad: true if marked as an ad ("Ad", "Iklan").
            - marketplace: tokopedia, shopee, bukalapak, facebook or other.
            - match — compare with the target diecast:
              "match": the same casting and the same variant, in the same condition (carded vs loose) as the target.
              "different_variant": same casting but another variant — e.g. Super Treasure Hunt vs regular, another color,
                series, year, premium vs mainline. STH and regular prices differ hugely, so be strict.
              "different_condition": same item but loose when the target is carded, or the other way round.
              "bundle": a lot / set / several cars sold together.
              "different_item": a different car or product.
              "unclear": the title and picture don't let you tell.
              A listing title that doesn't mention "loose" is normally carded.
            - reason: a few words in Indonesian explaining the match decision.

            Also give:
            - note: one or two sentences in Indonesian about what you saw (e.g. many loose listings, prices cluster around X).
            - search_hint: better Tokopedia search keywords for this diecast if the results look off-target, else null.
        """.trimIndent()

        private val SCHEMA: JsonObject = run {
            fun type(name: String) = buildJsonObject { put("type", name) }
            fun nullable(name: String) = buildJsonObject {
                putJsonArray("anyOf") {
                    addJsonObject { put("type", name) }
                    addJsonObject { put("type", "null") }
                }
            }
            fun choice(values: List<String>) = buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { values.forEach { add(it) } }
            }

            val listing = linkedMapOf(
                "title" to type("string"),
                "price_idr" to type("integer"),
                "is_price_range" to type("boolean"),
                "shop" to nullable("string"),
                "location" to nullable("string"),
                "sold" to nullable("integer"),
                "rating" to nullable("number"),
                "is_ad" to type("boolean"),
                "marketplace" to choice(listOf("tokopedia", "shopee", "bukalapak", "facebook", "other")),
                "match" to choice(ListingMatch.entries.map { it.key }),
                "reason" to type("string"),
            )

            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("listings") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            put("properties", JsonObject(listing))
                            putJsonArray("required") { listing.keys.forEach { add(it) } }
                            put("additionalProperties", false)
                        }
                    }
                    put("note", nullable("string"))
                    put("search_hint", nullable("string"))
                }
                putJsonArray("required") {
                    add("listings")
                    add("note")
                    add("search_hint")
                }
                put("additionalProperties", false)
            }
        }
    }
}
