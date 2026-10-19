package io.github.caropeboka.diecasttracker.data.places

import android.util.Log
import io.github.caropeboka.diecasttracker.data.db.PlaceCacheEntity
import io.github.caropeboka.diecasttracker.data.db.PlaceDao
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * OpenStreetMap's Nominatim, free under its usage policy: an identifying User-Agent, at most one request a
 * second, and cached answers (reverse lookups per ~10 m cell for 30 days, searches for 7, like the web).
 * Every call returns null when OpenStreetMap can't be reached: a place name is a nicety, the point is what counts.
 */
class Nominatim(
    http: OkHttpClient,
    private val cache: PlaceDao,
    private val userAgent: String,
    private val baseUrl: String = "https://nominatim.openstreetmap.org",
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMs: Long = 1_100,
) {
    private val http = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val pace = Mutex()
    private var lastRequest = 0L

    suspend fun reverse(lat: Double, lng: Double): JsonObject? {
        val url = "$baseUrl/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("lat", plain(lat))
            .addQueryParameter("lon", plain(lng))
            .addQueryParameter("zoom", "18")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", "id")
            .build()
        val key = String.format(Locale.ROOT, "osm-reverse:%.4f,%.4f", lat, lng)

        return cached(key, REVERSE_TTL_MS, url) { (it as? JsonObject)?.takeIf { json -> "error" !in json } } as JsonObject?
    }

    /** Biased towards ~30 km around [lat]/[lng] when given (preferred, not limited to). */
    suspend fun search(query: String, lat: Double?, lng: Double?): JsonArray? {
        val near = lat != null && lng != null
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "6")
            .addQueryParameter("addressdetails", "1")
            .addQueryParameter("accept-language", "id")
            .apply {
                if (near) {
                    addQueryParameter("viewbox", listOf(lng!! - 0.3, lat!! + 0.3, lng + 0.3, lat - 0.3).joinToString(",", transform = ::plain))
                }
            }
            .build()
        val key = "osm-search:$query|" + if (near) String.format(Locale.ROOT, "%.1f,%.1f", lat, lng) else ""

        return cached(key, SEARCH_TTL_MS, url) { it as? JsonArray } as JsonArray?
    }

    private suspend fun cached(key: String, ttl: Long, url: HttpUrl, accept: (JsonElement) -> JsonElement?): JsonElement? {
        cache.cached(key, since = clock() - ttl)?.let { hit ->
            runCatching { Json.parseToJsonElement(hit.body) }.getOrNull()?.let(accept)?.let { return it }
        }

        val body = fetch(url) ?: return null
        val answer = runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let(accept) ?: return null
        cache.cache(PlaceCacheEntity(key, body, clock()))
        return answer
    }

    private suspend fun fetch(url: HttpUrl): String? = pace.withLock {
        val wait = lastRequest + minIntervalMs - clock()
        if (wait > 0) delay(wait)
        lastRequest = clock()

        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(request).body().also { if (it == null) Log.i(TAG, "OpenStreetMap request failed: ${url.encodedPath}") }
    }

    /** The body of a successful answer, or null; never throws for network trouble. */
    private suspend fun Call.body(): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resume(null)

                override fun onResponse(call: Call, response: Response) {
                    val body = try {
                        response.use { if (it.isSuccessful) it.body.string() else null }
                    } catch (_: IOException) {
                        null
                    }
                    continuation.resume(body)
                }
            },
        )
    }

    companion object {
        private const val TAG = "Nominatim"
        const val REVERSE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val SEARCH_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
