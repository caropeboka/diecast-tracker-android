package io.github.caropeboka.diecasttracker.data.ai

import android.util.Log
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A scan that failed for a reason the collector should read; the form still works without it. */
open class AiException(message: String) : Exception(message)

/** The AI can't take the request right now (free quota used up, overloaded); nothing is wrong with the photo. */
class AiRetryLaterException(message: String) : AiException(message)

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
            http.withTimeout(LIST_TIMEOUT_S).newCall(request).fetch()
        } catch (_: IOException) {
            throw AiException("Tidak bisa menghubungi Gemini. Cek koneksi internet.")
        }

        if (!reply.ok) {
            throw AiException(
                if (reply.code in listOf(400, 401, 403)) "API key Gemini tidak valid." else "Gemini sedang bermasalah. Coba lagi nanti.",
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
     * nullable fields as `anyOf [..., {type: null}]`).
     */
    suspend fun extract(
        key: String,
        model: String,
        images: List<ByteArray>,
        system: String,
        instruction: String,
        schema: JsonObject,
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
        }.toString()

        // Busy, timed out, out of (per-model) free quota, or retired for this key: move on to the next model,
        // within an overall time budget so the scan doesn't hang.
        val started = clock()
        val outcomes = linkedMapOf<String, Outcome>()
        var last: Outcome? = null

        for ((index, candidate) in candidates(key, model).withIndex()) {
            if (index > 0 && clock() - started > TIME_BUDGET_MS) break

            val outcome = call(key, candidate, payload, retry = index == 0)
            outcomes[candidate] = outcome
            last = outcome

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
            throw AiException("AI tidak bisa memproses gambar ini.")
        }

        val text = json?.array("candidates")?.firstOrNull()?.let { it as? JsonObject }
            ?.obj("content")?.array("parts").orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("")

        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw AiException("Hasil AI tidak terbaca. Coba foto ulang dengan cahaya lebih terang.")
    }

    /**
     * One generateContent call. The chosen model gets one quick retry on a brief overload (500/503); fallbacks
     * don't, to keep it fast.
     */
    private suspend fun call(key: String, model: String, payload: String, retry: Boolean): Outcome {
        val client = http.withTimeout(if (retry) TIMEOUT_S else FALLBACK_TIMEOUT_S)
        val request = Request.Builder()
            .url("$baseUrl/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toRequestBody(JSON_TYPE))
            .build()
        val attempts = if (retry) 2 else 1

        var attempt = 1
        while (true) {
            val reply = try {
                client.newCall(request).fetch()
            } catch (_: UnknownHostException) {
                return Outcome.Offline
            } catch (_: IOException) {
                return Outcome.TimedOut
            }

            if (attempt == attempts || reply.code !in RETRY_CODES) return reply
            attempt++
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * The chosen model first, then the newest "flash" models this key can use, lite ones first: in a demand spike
     * all regular flash models tend to be busy together, while lite runs on separate capacity and still reads
     * printed text well.
     */
    private suspend fun candidates(key: String, model: String): List<String> {
        val (lite, regular) = fallbacks(key).filter { it != model }.partition { it.endsWith("-lite") }

        return (listOf(model, lite.firstOrNull() ?: LITE_LATEST, LITE_LATEST) + regular + lite.drop(1))
            .distinct()
            .take(MAX_FALLBACKS + 1)
    }

    /** Picked from the live model list (cached), because Google retires and adds models often. */
    private suspend fun fallbacks(key: String): List<String> {
        modelCache.get(key)?.let { return it }

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
            reason == "API_KEY_INVALID" || 403 in codes -> AiException("API key Gemini tidak valid. Cek Pengaturan AI.")
            429 in codes -> AiRetryLaterException("Kuota gratis Gemini habis untuk saat ini. Tunggu sebentar (atau besok) lalu coba lagi.")
            500 in codes || 503 in codes -> AiRetryLaterException("Server Gemini sedang sibuk (banyak pengguna). Coba lagi sebentar lagi.")
            Outcome.Offline in outcomes.values -> AiOfflineException("Tidak bisa terhubung ke Gemini. Cek koneksi internet.")
            Outcome.TimedOut in outcomes.values -> AiRetryLaterException(
                "Gemini tidak merespons (server penuh atau koneksi lambat). Coba lagi sebentar lagi.",
            )
            (outcomes[model] as? Outcome.Reply)?.code == 404 -> AiException("Model Gemini \"$model\" tidak ditemukan. Pilih model lain di Pengaturan AI.")
            else -> AiException("Gemini sedang bermasalah. Isi manual dulu atau coba lagi.")
        }
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

    /** Runs the call without holding a thread, and cancels it when the scan is abandoned. */
    private suspend fun Call.fetch(): Outcome.Reply = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val reply = try {
                        response.use { Outcome.Reply(it.code, it.body.string()) }
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(reply)
                }
            },
        )
    }

    private fun OkHttpClient.withTimeout(seconds: Long) = newBuilder()
        .callTimeout(seconds, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS)
        .build()

    private fun version(name: String) = VERSION.find(name)?.groupValues?.get(1) ?: "0"

    private companion object {
        const val TAG = "GeminiClient"
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
        val JSON_TYPE = "application/json".toMediaType()
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

private fun JsonObject.strings(key: String): List<String> =
    array(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
