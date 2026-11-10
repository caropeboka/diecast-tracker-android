package io.github.caropeboka.diecasttracker.data.price

import androidx.annotation.StringRes
import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.ai.AiVision
import io.github.caropeboka.diecasttracker.data.ai.ChatTurn
import io.github.caropeboka.diecasttracker.data.ai.WebSource
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.PriceCheckEntity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.string
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Screenshots read per check (web: `PriceChecker::MAX_SCREENSHOTS`). */
const val MAX_PRICE_SCREENSHOTS = 6

/** How the search buttons are grouped: new from shops, second-hand, and what really sold abroad. */
enum class MarketGroup(@StringRes val label: Int) {
    Shops(R.string.price_group_shops),
    Preloved(R.string.price_group_preloved),
    SoldAbroad(R.string.price_group_sold),
}

/** How the keywords go into a marketplace's address. */
private enum class Words {
    /** A query parameter: spaces become "+". */
    Query,

    /** A path segment: spaces become "%20". */
    Path,

    /** OLX's "q-hot-wheels-skyline": lower case, words joined by dashes. */
    Slug,
}

/** Where a diecast can be looked up; [search] opens its results for some keywords. */
enum class Marketplace(
    val label: String,
    private val url: String,
    val group: MarketGroup,
    @StringRes val labelRes: Int? = null,
    private val words: Words = Words.Query,
) {
    Tokopedia("Tokopedia", "https://www.tokopedia.com/search?st=product&q={q}", MarketGroup.Shops),
    Shopee("Shopee", "https://shopee.co.id/search?keyword={q}", MarketGroup.Shops),
    Lazada("Lazada", "https://www.lazada.co.id/catalog/?q={q}", MarketGroup.Shops),

    /** Google's Shopping tab: prices from many shops (Blibli and other web shops too) on one page. */
    GoogleShopping("Google Shopping", "https://www.google.com/search?udm=28&gl=id&q={q}", MarketGroup.Shops),
    Facebook("Facebook", "https://www.facebook.com/marketplace/search/?query={q}", MarketGroup.Preloved),

    /** Only OLX's "Koleksi & Mainan Hobi": a plain search there is mostly real cars. */
    Olx("OLX", "https://www.olx.co.id/koleksi-mainan-hobi_c221/q-{q}", MarketGroup.Preloved, words = Words.Slug),
    Carousell("Carousell", "https://id.carousell.com/search/{q}", MarketGroup.Preloved, words = Words.Path),

    /** Sold listings only: what collectors abroad really paid. */
    EbaySold("eBay", "https://www.ebay.com/sch/i.html?_nkw={q}&LH_Sold=1&LH_Complete=1", MarketGroup.SoldAbroad, R.string.price_ebay_sold),

    /** Japanese auctions that ended in a sale, and Mercari's sold items: Tomica and other Japanese lines, in yen. */
    YahooAuctionsSold("Yahoo! Auctions", "https://auctions.yahoo.co.jp/closedsearch/closedsearch?p={q}", MarketGroup.SoldAbroad, R.string.price_yahoo_sold),
    MercariSold("Mercari", "https://jp.mercari.com/search?keyword={q}&status=sold_out", MarketGroup.SoldAbroad, R.string.price_mercari_sold),
    ;

    fun search(query: String): String {
        val text = query.trim()
        val encoded = when (words) {
            Words.Query -> URLEncoder.encode(text, "UTF-8")
            Words.Path -> URLEncoder.encode(text, "UTF-8").replace("+", "%20")
            Words.Slug -> text.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }.joinToString("-") { URLEncoder.encode(it, "UTF-8") }
        }
        return url.replace("{q}", encoded)
    }

    private companion object {
        val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}

/**
 * The AI's rough estimate from what it found on the web (Gemini searches; the others answer from memory), in whole
 * Rupiah; all null when it found nothing to go on.
 */
@Serializable
data class PriceEstimate(
    val low: Long?,
    val typical: Long?,
    val high: Long?,
    val text: String,
    val sources: List<WebSource> = emptyList(),
    val by: String,
    val searched: Boolean = false,
)

/**
 * Reads marketplace screenshots (Tokopedia, Shopee, eBay…) with the AI and records a price check for a diecast
 * (web: `PriceChecker.php`); [PriceAnalyzer] does the sums. Prices in another currency are converted at [rates].
 */
class PriceChecker(
    private val vision: AiVision,
    private val db: AppDatabase,
    private val texts: Texts,
    private val rates: suspend () -> ExchangeRates? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.priceChecks()

    val enabled: Flow<Boolean> = vision.enabled

    fun observe(diecastId: Long): Flow<List<PriceCheckEntity>> = dao.observeFor(diecastId)

    /** [images]: JPEG screenshots; at most [MAX_PRICE_SCREENSHOTS] are read. */
    suspend fun check(diecast: DiecastEntity, images: List<ByteArray>, onEvent: (AiEvent) -> Unit = {}): PriceCheckEntity {
        val sent = images.take(MAX_PRICE_SCREENSHOTS)
        val read = vision.extract(sent, SYSTEM_PROMPT.replace("{language}", texts(R.string.ai_answer_language)), instruction(diecast, sent.size), SCHEMA, onEvent).data

        val rows = read.array("listings").orEmpty().mapNotNull { it as? JsonObject }
        // Fetched only when something wasn't in Rupiah (eBay).
        val rates = if (rows.any { currencyOf(it) != IDR }) rates() else null
        val analysis = PriceAnalyzer.analyze(rows.mapNotNull { listing(it, rates) }, texts)
        val hint = read.string("search_hint")?.trim()?.ifEmpty { null }
        val abroad = analysis.listings.any { it.currency != null && it.status == ListingStatus.Included }
        val check = PriceCheckEntity(
            diecastId = diecast.id,
            screenshots = sent.size,
            listings = analysis.listings,
            stats = analysis.stats,
            accuracy = analysis.accuracy,
            reasons = analysis.reasons,
            tips = analysis.tips + listOfNotNull(
                texts(R.string.price_tip_abroad).takeIf { abroad },
                hint?.let { texts(R.string.price_tip_search_hint, it) },
            ),
            aiNote = read.string("note")?.trim()?.ifEmpty { null },
            createdAt = clock(),
        )
        return check.copy(id = dao.insert(check))
    }

    /** Uses the check's suggested price (or [value]) as the diecast's market value; returns the diecast. */
    suspend fun apply(check: PriceCheckEntity, value: Long? = null): DiecastEntity {
        val price = value ?: check.stats?.suggested ?: throw IllegalStateException(texts(R.string.price_nothing_to_use))
        val now = clock()

        return db.withTransaction {
            dao.setMarketValue(check.diecastId, price, now)
            dao.markApplied(check.id, now)
            checkNotNull(db.diecasts().get(check.diecastId)) { "Diecast ${check.diecastId} not found" }
        }
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Other keywords to search with, from the AI: broader ones, the casting's other names, a model code. No photos,
     * so it is quick.
     */
    suspend fun keywords(diecast: DiecastEntity, onEvent: (AiEvent) -> Unit = {}): List<String> {
        val read = vision.extract(emptyList(), KEYWORDS_PROMPT, "Target diecast:\n${describe(diecast)}", KEYWORDS_SCHEMA, onEvent).data
        val current = searchQuery(diecast).lowercase()
        return read.array("keywords").orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.take(80)?.ifEmpty { null } }
            .distinctBy { it.lowercase() }
            .filter { it.lowercase() != current }
            .take(MAX_KEYWORDS)
    }

    /** What the AI makes of the diecast's price from the web, flagged as rough (no screenshots, no sums of ours). */
    suspend fun estimate(diecast: DiecastEntity, onEvent: (AiEvent) -> Unit = {}): PriceEstimate {
        val system = ESTIMATE_PROMPT.replace("{language}", texts(R.string.ai_answer_language))
        val question = "Target diecast:\n${describe(diecast)}\n\nToday is ${Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()}. " +
            "What is it worth now in Indonesia?"
        val reply = vision.chat(emptyList(), system, listOf(ChatTurn(fromUser = true, question)), onEvent)
        val (text, range) = splitEstimate(reply.text)
        return PriceEstimate(range?.first, range?.second, range?.third, text, reply.sources, reply.by, reply.sources.isNotEmpty())
    }

    /** Uses the estimate's typical price as the diecast's market value, marked as the AI's estimate. */
    suspend fun applyEstimate(diecastId: Long, value: Long): DiecastEntity {
        dao.setEstimatedValue(diecastId, value, clock())
        return checkNotNull(db.diecasts().get(diecastId)) { "Diecast $diecastId not found" }
    }

    /**
     * One listing as the AI read it; numbers it wrote as text or decimals still count. A price in another currency is
     * converted at [rates]; without a rate for it the listing can't count.
     */
    private fun listing(json: JsonObject, rates: ExchangeRates?): PriceListing? {
        fun number(name: String): Double? = (json[name] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        fun flag(name: String) = (json[name] as? JsonPrimitive)?.booleanOrNull ?: false
        fun text(name: String) = json.string(name)?.trim()?.ifEmpty { null }

        val currency = currencyOf(json)
        val shown = number("price") ?: number("price_idr")
        val rate = if (currency == IDR) 1.0 else rates?.idrPer(currency)
        return PriceListing(
            title = text("title") ?: return null,
            price = if (rate == null) 0 else shown?.times(rate)?.roundToLong()?.coerceAtLeast(0) ?: 0,
            isPriceRange = flag("is_price_range"),
            shop = text("shop"),
            location = text("location"),
            sold = number("sold")?.toInt(),
            rating = number("rating"),
            isAd = flag("is_ad"),
            marketplace = text("marketplace") ?: "other",
            match = if (rate == null) ListingMatch.Unclear else ListingMatch.fromKey(text("match")),
            reason = if (rate == null) texts(R.string.price_no_rate, currency) else text("reason"),
            currency = currency.takeIf { it != IDR },
            originalPrice = shown.takeIf { currency != IDR },
        )
    }

    private fun currencyOf(json: JsonObject): String =
        json.string("currency")?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: IDR

    private fun describe(diecast: DiecastEntity): String = listOf(
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

    private fun instruction(diecast: DiecastEntity, count: Int): String =
        "Target diecast:\n${describe(diecast)}\n\nRead all listings in these $count screenshot(s)."

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

        /**
         * Other keywords worth a try without asking the AI: the name alone, brand and name without the rarity, a model
         * code (Mini GT's "MGT00512"). The default ([searchQuery]) is left out.
         */
        fun keywordVariants(diecast: DiecastEntity): List<String> {
            val name = diecast.name.replace(Regex("['\"]"), "").trim()
            val code = diecast.collectionNumber?.let { MODEL_CODE.find(it)?.value }
            return listOfNotNull(
                listOfNotNull(diecast.brand, name).joinToString(" "),
                name,
                listOfNotNull(diecast.brand, diecast.make, diecast.year?.toString()).takeIf { diecast.make != null && diecast.make !in name }
                    ?.let { (it + name).joinToString(" ") },
                code,
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
                .filter { it.lowercase() != searchQuery(diecast).lowercase() }
        }

        /** The words and the (low, typical, high) range from an answer's ```estimate block; the range null when it has none. */
        fun splitEstimate(answer: String): Pair<String, Triple<Long?, Long?, Long?>?> {
            var range: Triple<Long?, Long?, Long?>? = null
            val text = ESTIMATE_BLOCK.replace(answer) { block ->
                val json = runCatching { Json.parseToJsonElement(block.groupValues[1].trim()) as? JsonObject }.getOrNull()
                    ?: return@replace block.value
                fun price(key: String) = (json[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
                    ?.roundToLong()?.takeIf { it > 0 }
                val typical = price("typical")
                if (typical != null) range = Triple(price("low")?.takeIf { it <= typical } ?: typical, typical, price("high")?.takeIf { it >= typical } ?: typical)
                ""
            }
            return text.replace(Regex("\n{3,}"), "\n\n").trim() to range
        }

        const val MAX_KEYWORDS = 6

        /** What the AI may say a listing came from; "bukalapak" stays readable in older checks, not offered any more. */
        val MARKETPLACE_KEYS = listOf("tokopedia", "shopee", "lazada", "google", "facebook", "olx", "carousell", "ebay", "yahoo_auctions", "mercari", "other")
        private const val IDR = "IDR"
        private val CURRENCY = Regex("^[A-Z]{3}$")
        private val MODEL_CODE = Regex("\\b[A-Z]{2,5}\\d{3,}\\b")
        private val ESTIMATE_BLOCK = Regex("```[ \\t]*(?:estimate|json)?[ \\t]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

        private val KEYWORDS_PROMPT = """
            You help an Indonesian diecast collector find one diecast for sale online. Suggest up to $MAX_KEYWORDS search
            keyword sets for Indonesian marketplaces, Google Shopping and eBay that would find it when the obvious search
            doesn't: shorter or broader ones, other names the casting is sold under (English or Japanese), the model code,
            the car's chassis code (e.g. "R34", "AE86", "FD3S"), the series. For a Japanese line (Tomica, Tomica Premium,
            Kyosho…) include one set in Japanese (e.g. "トミカ スカイライン") for Yahoo! Auctions and Mercari Japan. Each
            set 1 to 6 words, the most promising first.
        """.trimIndent()

        private val KEYWORDS_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("keywords") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("keywords") }
            put("additionalProperties", false)
        }

        private val ESTIMATE_PROMPT = """
            You help an Indonesian diecast collector estimate what one diecast is worth now. Search the web if you can:
            Indonesian marketplaces (Tokopedia, Shopee, Lazada), eBay sold listings, Yahoo! Auctions and Mercari Japan
            for Japanese lines, collector forums and price guides. Answer in {language} in three to six short sentences or bullets: the prices you found (where, carded
            or loose, sold or asking), and how sure the estimate is. Never invent prices; if you found nothing reliable,
            say so.

            End with a block like this, in whole Indonesian rupiah (convert foreign prices at today's rate and add
            nothing for shipping or import):
            ```estimate
            {"low": 30000, "typical": 45000, "high": 60000}
            ```
            Use null for all three when you found nothing to go on.
        """.trimIndent()

        private val SYSTEM_PROMPT = """
            You help an Indonesian diecast collector estimate market prices. You are given screenshots of marketplace
            search results or product pages (usually Tokopedia, sometimes Shopee, Lazada, Google Shopping or Google Lens,
            Facebook Marketplace, OLX, Carousell, or sold listings on eBay, Yahoo! Auctions Japan or Mercari Japan) and a
            description of the diecast being priced.

            Extract EVERY product listing visible in the screenshots — also the ones that are not this diecast — exactly
            as shown. Never invent listings or guess prices that aren't visible. If the same listing appears in two
            overlapping screenshots, list it once.

            For each listing:
            - title: the product title as shown (it may be cut off).
            - price: the price a buyer pays now, as a plain number in the currency shown ("Rp45.000" → 45000,
              "Rp1,2jt" → 1200000, "$12.50" → 12.5). If a discount is shown, use the discounted price, not the
              struck-through one. If a price range is shown ("Rp35.000 - Rp60.000"), use the lowest price and set
              is_price_range to true. Google Lens puts the price as a tag on the picture ("IDR 200,000*"); a
              picture without a price tag is not a listing. On sold results (eBay, Yahoo! Auctions' ended auctions, Mercari's SOLD items) use
              the price it sold for, without shipping.
            - currency: the price's ISO currency code: IDR for rupiah, USD for "$" or "US $" (eBay.com), JPY for "円" or
              "¥" (Yahoo! Auctions, Mercari), EUR, GBP…
            - shop: the shop / seller name if visible (on Google Shopping and Google Lens, the store named with the
              price), else null. location: the city if visible, else null.
            - sold: how many sold as an integer ("10+ terjual" → 10, "1rb+ terjual" → 1000, "250 terjual" → 250), null if not shown.
            - rating: the rating number if shown, else null. is_ad: true if marked as an ad ("Ad", "Iklan").
            - marketplace: tokopedia, shopee, lazada, google (Google Shopping or Google Lens results), facebook, olx,
              carousell, ebay, yahoo_auctions, mercari or other.
            - match — compare with the target diecast:
              "match": the same casting and the same variant, in the same condition (carded vs loose) as the target.
              "different_variant": same casting but another variant — e.g. Super Treasure Hunt vs regular, another color,
                series, year, premium vs mainline. STH and regular prices differ hugely, so be strict.
              "different_condition": same item but loose when the target is carded, or the other way round.
              "bundle": a lot / set / several cars sold together.
              "different_item": a different car or product.
              "unclear": the title and picture don't let you tell.
              A listing title that doesn't mention "loose" is normally carded.
            - reason: a few words in {language} explaining the match decision.

            Also give:
            - note: one or two sentences in {language} about what you saw (e.g. many loose listings, prices cluster around X).
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
                "price" to type("number"),
                "currency" to type("string"),
                "is_price_range" to type("boolean"),
                "shop" to nullable("string"),
                "location" to nullable("string"),
                "sold" to nullable("integer"),
                "rating" to nullable("number"),
                "is_ad" to type("boolean"),
                "marketplace" to choice(MARKETPLACE_KEYS),
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
