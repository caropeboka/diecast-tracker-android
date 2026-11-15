package io.github.caropeboka.diecasttracker.data.price

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.fetch
import io.github.caropeboka.diecasttracker.data.ai.withTimeout
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.string
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One product from a Google Shopping search: [price] in [currency] (an ISO code), [shop] the store Google names with
 * it, [condition] what the shop says when it is second-hand ("Bekas").
 */
data class ShoppingResult(
    val title: String,
    val price: Double,
    val currency: String,
    val shop: String? = null,
    val link: String? = null,
    val rating: Double? = null,
    val condition: String? = null,
)

/** A SerpApi account as its free account API tells it: the plan and the searches left (this month's and extra). */
data class SerpApiAccount(val plan: String?, val perMonth: Int?, val left: Int?)

/** SerpApi refused or failed; the message says why in the app's language. */
class ShoppingSearchException(message: String) : Exception(message)

/**
 * Google Shopping through SerpApi (serpapi.com), with the collector's own key: the listings Google shows for some
 * keywords in Indonesia, with their prices as numbers. Only the keywords and the key are sent, never a photo. A search
 * counts against the plan (250 a month free) unless SerpApi answers it from its cache or it fails; the account API is
 * free.
 */
class ShoppingSearch(
    private val http: OkHttpClient,
    private val key: Flow<String?>,
    private val texts: Texts,
    private val baseUrl: String = BASE_URL,
) {
    val enabled: Flow<Boolean> = key.map { !it.isNullOrBlank() }

    /** Up to [MAX_RESULTS] listings with a price, in Google's order; empty when Google found nothing. */
    suspend fun search(query: String): List<ShoppingResult> {
        val apiKey = key.first()?.trim()?.ifEmpty { null } ?: throw ShoppingSearchException(texts(R.string.serpapi_no_key))
        val url = url("search.json", apiKey)
            .addQueryParameter("engine", "google_shopping")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("gl", "id")
            .addQueryParameter("hl", "id")
            .build()
        val json = get(url) ?: return emptyList()

        val rows = json.array("shopping_results").orEmpty() + json.array("inline_shopping_results").orEmpty() +
            json.array("categorized_shopping_results").orEmpty().flatMap { (it as? JsonObject)?.array("shopping_results").orEmpty() }
        return rows.mapNotNull { (it as? JsonObject)?.let(::result) }
            .distinctBy { listOf(it.title.lowercase(), it.shop.orEmpty().lowercase(), it.price.toString()).joinToString("|") }
            .take(MAX_RESULTS)
    }

    /** The plan and the searches left for [apiKey] (the saved key when null). Free: it doesn't use a search. */
    suspend fun account(apiKey: String? = null): SerpApiAccount {
        val used = apiKey?.trim()?.ifEmpty { null } ?: key.first()?.trim()?.ifEmpty { null }
            ?: throw ShoppingSearchException(texts(R.string.serpapi_no_key))
        val json = get(url("account.json", used).build()) ?: throw ShoppingSearchException(texts(R.string.serpapi_failed, "—"))
        fun number(name: String) = (json[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
        return SerpApiAccount(
            plan = json.string("plan_name")?.trim()?.ifEmpty { null },
            perMonth = number("searches_per_month"),
            left = number("total_searches_left") ?: number("plan_searches_left"),
        )
    }

    private fun url(path: String, apiKey: String): HttpUrl.Builder =
        baseUrl.toHttpUrl().newBuilder().addPathSegment(path).addQueryParameter("api_key", apiKey)

    /** The answer, or null when Google returned no results (an error SerpApi doesn't count as a search). */
    private suspend fun get(url: HttpUrl): JsonObject? {
        val (code, body) = try {
            http.withTimeout(TIMEOUT_SECONDS).newCall(Request.Builder().url(url).build()).fetch { it.code to it.body.string() }
        } catch (_: UnknownHostException) {
            throw ShoppingSearchException(texts(R.string.serpapi_offline))
        } catch (e: IOException) {
            throw ShoppingSearchException(texts(R.string.serpapi_failed, e.message ?: e::class.simpleName.orEmpty()))
        }
        val json = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val error = json?.string("error")?.trim()?.ifEmpty { null }
        when {
            code == 401 || code == 403 -> throw ShoppingSearchException(texts(R.string.serpapi_key_rejected))
            code == 429 -> throw ShoppingSearchException(texts(R.string.serpapi_quota))
            error != null && NO_RESULTS.containsMatchIn(error) -> return null
            code !in 200..299 || error != null || json == null ->
                throw ShoppingSearchException(texts(R.string.serpapi_failed, error ?: "HTTP $code"))
        }
        return json
    }

    /** A listing with a title and a price; null without them. */
    private fun result(json: JsonObject): ShoppingResult? {
        val title = json.string("title")?.trim()?.ifEmpty { null } ?: return null
        val shown = json.string("price")
        val price = (json["extracted_price"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: return null
        return ShoppingResult(
            title = title,
            price = price,
            currency = currencyOf(shown),
            shop = json.string("source")?.trim()?.ifEmpty { null },
            link = (json.string("product_link") ?: json.string("link"))?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
            rating = (json["rating"] as? JsonPrimitive)?.doubleOrNull,
            condition = json.string("second_hand_condition")?.trim()?.ifEmpty { null },
        )
    }

    companion object {
        const val BASE_URL = "https://serpapi.com"

        /** Where a key is made and shown (it asks to sign in first). */
        const val KEY_URL = "https://serpapi.com/manage-api-key"

        /** About what one page of Google Shopping holds. */
        const val MAX_RESULTS = 40

        private const val TIMEOUT_SECONDS = 60L
        private val NO_RESULTS = Regex("hasn'?t returned any results", RegexOption.IGNORE_CASE)

        /**
         * The currency of a price as Google writes it ("Rp 45.000", "US$12.50", "¥3,000"); Rupiah when it says nothing
         * else, as the search is Indonesia's.
         */
        fun currencyOf(price: String?): String {
            val text = price?.trim().orEmpty()
            return when {
                text.isEmpty() || text.contains("Rp", ignoreCase = true) || text.contains("IDR", ignoreCase = true) -> "IDR"
                // Before "S$", which "US$" holds too.
                text.contains("US$") || text.contains("USD") -> "USD"
                text.contains("S$") || text.contains("SGD") -> "SGD"
                text.contains("A$") || text.contains("AUD") -> "AUD"
                text.contains("HK$") || text.contains("HKD") -> "HKD"
                text.contains("C$") || text.contains("CA$") || text.contains("CAD") -> "CAD"
                text.contains("RM") || text.contains("MYR") -> "MYR"
                text.contains("CN¥") || text.contains("CNY") || text.contains("RMB") -> "CNY"
                text.contains('¥') || text.contains('円') || text.contains("JPY") -> "JPY"
                text.contains('€') || text.contains("EUR") -> "EUR"
                text.contains('£') || text.contains("GBP") -> "GBP"
                text.contains('$') || text.contains("USD") -> "USD"
                else -> "IDR"
            }
        }
    }
}
