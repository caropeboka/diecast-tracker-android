package io.github.caropeboka.diecasttracker.data.money

import io.github.caropeboka.diecasttracker.data.ai.fetch
import io.github.caropeboka.diecasttracker.data.ai.withTimeout
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/**
 * Daily exchange rates without a key: the European Central Bank's reference rates through Frankfurter, and
 * ExchangeRate-API's open endpoint when that one is down.
 */
class RateSource(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val frankfurter: String = "https://api.frankfurter.dev/v1/latest?base=USD&symbols=IDR,CNY," + OTHERS.joinToString(","),
    private val openEr: String = "https://open.er-api.com/v6/latest/USD",
) {
    /** Null when neither answered. */
    suspend fun fetch(): ExchangeRates? = fromFrankfurter() ?: fromOpenEr()

    private suspend fun fromFrankfurter(): ExchangeRates? {
        val json = get(frankfurter) ?: return null
        val rates = json.obj("rates") ?: return null
        return rates(rates, json.string("date") ?: today(), "ECB")
    }

    private suspend fun fromOpenEr(): ExchangeRates? {
        val json = get(openEr)?.takeIf { it.string("result") == "success" } ?: return null
        val rates = json.obj("rates") ?: return null
        val date = (json["time_last_update_unix"] as? JsonPrimitive)?.longOrNull
            ?.let { Instant.ofEpochSecond(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() } ?: today()
        return rates(rates, date, "ExchangeRate-API")
    }

    /** Both list their rates per US dollar: Rupiah per dollar, and per yuan (and the others) through the dollar. */
    private fun rates(rates: JsonObject, date: String, source: String): ExchangeRates? {
        fun perDollar(code: String) = (rates[code] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
        val idr = perDollar("IDR") ?: return null
        val cny = perDollar("CNY") ?: return null
        val others = OTHERS.mapNotNull { code -> perDollar(code)?.let { code to idr / it } }.toMap()
        return ExchangeRates(usd = idr, cny = idr / cny, date = date, source = source, fetchedAt = clock(), others = others)
    }

    private suspend fun get(url: String): JsonObject? = try {
        http.withTimeout(TIMEOUT_S).newCall(Request.Builder().url(url).build()).fetch { response ->
            if (response.isSuccessful) runCatching { Json.parseToJsonElement(response.body.string()) as? JsonObject }.getOrNull() else null
        }
    } catch (_: IOException) {
        null
    }

    private fun today() = Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC).toLocalDate().toString()

    private companion object {
        const val TIMEOUT_S = 15L

        /** What eBay sellers mostly price in, besides dollars. */
        val OTHERS = listOf("EUR", "GBP", "JPY", "AUD", "CAD", "SGD", "MYR", "HKD")
    }
}
