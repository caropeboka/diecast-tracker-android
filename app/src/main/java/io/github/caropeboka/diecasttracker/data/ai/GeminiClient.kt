package io.github.caropeboka.diecasttracker.data.ai

import android.util.Log
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** A scan that failed for a reason the collector should read; the form still works without it. */
open class AiException(message: String) : Exception(message)

/**
 * The AI can't take the request right now (free quota used up, overloaded); nothing is wrong with the photo.
 * [restUntil]: when its quota is back (epoch millis), if it said; until then other providers go first.
 */
class AiRetryLaterException(message: String, val restUntil: Long? = null) : AiException(message)

/** No connection to the AI at all; nothing is wrong with the photo either. */
class AiOfflineException(message: String) : AiException(message)

/**
 * Google Gemini over the REST `generateContent` endpoint, with the web app's fallbacks (`AiVision.php`): when
 * the chosen model is busy, out of free quota or retired, up to [MAX_FALLBACKS] other models are tried within
 * [TIME_BUDGET_MS]. Free-tier quota is counted per model, so this also stretches the daily allowance.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val modelCache: ModelListCache,
    private val texts: Texts,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Checks a key by listing the models it can use to read an image and answer. */
    suspend fun availableModels(key: String): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/models?pageSize=1000")
            .header("x-goog-api-key", key)
            .build()

        val reply = try {
            http.withTimeout(LIST_TIMEOUT_S).newCall(request).fetch(::reply)
        } catch (_: IOException) {
            throw AiException(texts(R.string.ai_err_connect, GEMINI))
        }

        if (!reply.ok) {
            throw AiException(
                texts(if (reply.code in listOf(400, 401, 403)) R.string.ai_err_key_invalid_short else R.string.ai_err_trouble_later, GEMINI),
            )
        }

        return reply.json()?.array("models").orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { "generateContent" in it.strings("supportedGenerationMethods") }
            .mapNotNull { it.string("name")?.removePrefix("models/") }
            .filter { it.startsWith("gemini-") && !NOT_FOR_SCANNING.containsMatchIn(it) }
    }

    /**
     * Asks about some JPEGs and returns the JSON object the model answers, shaped by [schema] (JSON Schema,
     * nullable fields as `anyOf [..., {type: null}]`). [onEvent] hears every step, also from OkHttp's threads.
     */
    suspend fun extract(
        key: String,
        model: String,
        images: List<ByteArray>,
        system: String,
        instruction: String,
        schema: JsonObject,
        onEvent: (AiEvent) -> Unit = {},
    ): JsonObject {
        val payload = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        images.forEach { image ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(image))
                                }
                            }
                        }
                        addJsonObject { put("text", instruction) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", toGeminiSchema(schema))
            }
        }.toString().toByteArray()

        // Busy, timed out, out of (per-model) free quota, or retired for this key: move on to the next model,
        // within an overall time budget so the scan doesn't hang.
        val started = clock()
        val outcomes = linkedMapOf<String, Outcome>()
        var last: Outcome? = null

        for ((index, candidate) in candidates(key, model, onEvent).withIndex()) {
            if (index > 0 && clock() - started > TIME_BUDGET_MS) break

            val outcome = call(key, candidate, payload, index, onEvent)
            outcomes[candidate] = outcome
            last = outcome
            onEvent(if (outcome is Outcome.Reply && outcome.ok) AiEvent.Answered else AiEvent.Missed(candidate, missReason(outcome), retrying = false))

            // No connection at all: other models won't do better.
            if (outcome == Outcome.Offline || outcome is Outcome.Reply && (outcome.ok || outcome.code !in FALLBACK_CODES)) break
        }

        val reply = last as? Outcome.Reply
        if (reply == null || !reply.ok) {
            Log.w(TAG, "Gemini request failed: $outcomes ${reply?.body?.take(500)}")
            throw failure(model, outcomes, reply)
        }

        val json = reply.json()
        if (json?.obj("promptFeedback")?.string("blockReason") != null) {
            throw AiException(texts(R.string.ai_err_blocked))
        }

        val text = json?.array("candidates")?.firstOrNull()?.let { it as? JsonObject }
            ?.obj("content")?.array("parts").orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("")

        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw AiException(texts(R.string.ai_err_unreadable))
    }

    /**
     * One generateContent call; [fallback] 0 is the chosen model. It gets one quick retry on a brief overload
     * (500/503); fallbacks don't, to keep it fast.
     */
    private suspend fun call(key: String, model: String, payload: ByteArray, fallback: Int, onEvent: (AiEvent) -> Unit): Outcome {
        val client = http.withTimeout(if (fallback == 0) TIMEOUT_S else FALLBACK_TIMEOUT_S)
        val total = payload.size.toLong()
        val body = ProgressBody(payload) { sent -> onEvent(if (sent < total) AiEvent.Uploading(sent, total) else AiEvent.Waiting(model)) }
        val request = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body)
            .build()
        val attempts = if (fallback == 0) 2 else 1

        var attempt = 1
        while (true) {
            onEvent(AiEvent.Sending(model, total, fallback))
            val reply = try {
                client.newCall(request).fetch(::reply)
            } catch (_: UnknownHostException) {
                return Outcome.Offline
            } catch (_: IOException) {
                return Outcome.TimedOut
            }

            if (attempt == attempts || reply.code !in RETRY_CODES) return reply
            onEvent(AiEvent.Missed(model, missReason(reply), retrying = true))
            attempt++
            delay(RETRY_DELAY_MS)
        }
    }

    /** Why a model didn't answer, short enough for one line on the screen. */
    private fun missReason(outcome: Outcome): String = when (outcome) {
        Outcome.Offline -> texts(R.string.ai_miss_offline)
        Outcome.TimedOut -> texts(R.string.ai_miss_timeout)
        is Outcome.Reply -> when (outcome.code) {
            429 -> texts(R.string.ai_miss_quota)
            500, 503 -> texts(R.string.ai_miss_busy)
            404 -> texts(R.string.ai_miss_unavailable)
            else -> texts(R.string.ai_miss_failed, outcome.code)
        }
    }

    /**
     * The chosen model first, then the newest "flash" models this key can use, lite ones first: in a demand spike
     * all regular flash models tend to be busy together, while lite runs on separate capacity and still reads
     * printed text well.
     */
    private suspend fun candidates(key: String, model: String, onEvent: (AiEvent) -> Unit): List<String> {
        val (lite, regular) = fallbacks(key, onEvent).filter { it != model }.partition { it.endsWith("-lite") }

        return (listOf(model, lite.firstOrNull() ?: LITE_LATEST, LITE_LATEST) + regular + lite.drop(1))
            .distinct()
            .take(MAX_FALLBACKS + 1)
    }

    /** Picked from the live model list (cached), because Google retires and adds models often. */
    private suspend fun fallbacks(key: String, onEvent: (AiEvent) -> Unit): List<String> {
        modelCache.get(key)?.let { return it }

        onEvent(AiEvent.ListingModels)
        val available = try {
            availableModels(key)
        } catch (_: AiException) {
            return emptyList()
        }

        return available
            .filter(FLASH::matches)
            .sortedWith(
                compareByDescending<String> { it.endsWith("-lite") }
                    .then { a, b -> compareVersions(version(b), version(a)) },
            )
            .also { modelCache.put(key, it) }
    }

    /** The most useful reason across all models tried, not just the last fallback's. */
    private fun failure(model: String, outcomes: Map<String, Outcome>, last: Outcome.Reply?): AiException {
        val codes = outcomes.values.mapNotNull { (it as? Outcome.Reply)?.code }.toSet()
        val reason = last?.json()?.obj("error")?.array("details")?.firstOrNull()?.let { it as? JsonObject }?.string("reason")

        return when {
            reason == "API_KEY_INVALID" || 403 in codes -> AiException(texts(R.string.ai_err_key_invalid, GEMINI))
            429 in codes -> AiRetryLaterException(
                texts(R.string.ai_err_quota, GEMINI),
                restUntil = quotaBack(outcomes.values.filterIsInstance<Outcome.Reply>().filter { it.code == 429 }),
            )
            500 in codes || 503 in codes -> AiRetryLaterException(texts(R.string.ai_err_gemini_busy))
            Outcome.Offline in outcomes.values -> AiOfflineException(texts(R.string.ai_err_offline, GEMINI))
            Outcome.TimedOut in outcomes.values -> AiRetryLaterException(
                texts(R.string.ai_err_timeout, GEMINI),
            )
            (outcomes[model] as? Outcome.Reply)?.code == 404 -> AiException(texts(R.string.ai_err_gemini_model_missing, model))
            else -> AiException(texts(R.string.ai_err_gemini_other))
        }
    }

    private fun reply(response: Response) = Outcome.Reply(response.code, response.body.string())

    /**
     * When the free quota is back: midnight Pacific time when every model ran out for the day (Google resets daily
     * quotas then), otherwise after the longest wait Google asked for (a per-minute limit).
     */
    private fun quotaBack(limited: List<Outcome.Reply>): Long {
        val now = clock()
        if (limited.isNotEmpty() && limited.all { DAILY.containsMatchIn(it.body) }) return nextMidnight(now, PACIFIC)
        val wait = limited.mapNotNull { RETRY_DELAY.find(it.body)?.groupValues?.get(1)?.toDoubleOrNull() }.maxOrNull()
        return now + ((wait ?: 60.0) * 1000).toLong()
    }

    private sealed interface Outcome {
        class Reply(val code: Int, val body: String) : Outcome {
            val ok get() = code in 200..299

            fun json(): JsonObject? = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject

            override fun toString() = code.toString()
        }

        data object TimedOut : Outcome

        data object Offline : Outcome
    }

    private fun version(name: String) = VERSION.find(name)?.groupValues?.get(1) ?: "0"

    private companion object {
        const val TAG = "GeminiClient"
        const val GEMINI = "Gemini"
        const val MAX_FALLBACKS = 4

        // Seconds per call (an overloaded model sometimes hangs instead of answering 503), and the total.
        const val TIMEOUT_S = 40L
        const val FALLBACK_TIMEOUT_S = 25L
        const val LIST_TIMEOUT_S = 20L
        const val TIME_BUDGET_MS = 80_000L
        const val RETRY_DELAY_MS = 1_500L

        const val LITE_LATEST = "gemini-flash-lite-latest"
        val RETRY_CODES = setOf(500, 503)
        val FALLBACK_CODES = setOf(404, 429, 500, 503)
        val FLASH = Regex("^gemini-\\d+(\\.\\d+)?-flash(-lite)?$")
        val VERSION = Regex("^gemini-(\\d+(?:\\.\\d+)?)")
        val NOT_FOR_SCANNING = Regex("(embedding|tts|image|banana|live|audio|robotics|computer-use|transcribe|customtools)")
        val DAILY = Regex("PerDay")
        val RETRY_DELAY = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"")
        val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}

/** JSON Schema → Gemini's OpenAPI-style responseSchema (uppercase types, "nullable" instead of anyOf-null). */
internal fun toGeminiSchema(schema: JsonObject): JsonObject {
    schema.array("anyOf")?.let { anyOf ->
        val type = anyOf.mapNotNull { it as? JsonObject }.first { it.string("type") != "null" }
        return JsonObject(toGeminiSchema(type) + ("nullable" to JsonPrimitive(true)))
    }

    return buildJsonObject {
        put("type", schema.string("type").orEmpty().uppercase())
        for (key in listOf("enum", "description", "required")) {
            schema[key]?.let { put(key, it) }
        }
        schema.obj("properties")?.let { properties ->
            put("properties", JsonObject(properties.mapValues { (_, value) -> toGeminiSchema(value as JsonObject) }))
            put("propertyOrdering", buildJsonArray { properties.keys.forEach { add(JsonPrimitive(it)) } })
        }
        schema.obj("items")?.let { put("items", toGeminiSchema(it)) }
    }
}

/** PHP's version_compare for plain dotted numbers: "3.10" is newer than "3.8". */
internal fun compareVersions(a: String, b: String): Int {
    val left = a.split('.').map { it.toIntOrNull() ?: 0 }
    val right = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(i) ?: -1).compareTo(right.getOrNull(i) ?: -1)
        if (diff != 0) return diff
    }
    return 0
}

internal fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

/** The next midnight in [zone] after [now] (epoch millis): when a daily quota there starts again. */
internal fun nextMidnight(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
