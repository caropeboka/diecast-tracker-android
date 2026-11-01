package io.github.caropeboka.diecasttracker.data.ai

import android.util.Log
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import java.time.ZoneOffset
import java.util.Base64

/**
 * The providers that speak OpenAI's chat completions (Groq, Mistral, OpenRouter, a custom server): photos go as data
 * URLs and the answer comes back as a JSON object. Their support for JSON Schema differs, so the schema goes in the
 * prompt and JSON mode only asks for an object; the callers clean the fields as they do Gemini's.
 */
class OpenAiClient(
    private val http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Checks a key by listing its models: only those that read images where the service says which do. */
    suspend fun availableModels(provider: ProviderSettings): List<String> {
        val label = provider.label
        val request = Request.Builder().url(url(provider, "models")).authorized(provider).build()

        val reply = try {
            http.withTimeout(LIST_TIMEOUT_S).newCall(request).fetch(::reply)
        } catch (_: IOException) {
            throw AiException("Tidak bisa menghubungi $label. Cek koneksi internet dan alamatnya.")
        }

        if (!reply.ok) {
            throw AiException(if (reply.code in listOf(401, 403)) "API key $label tidak valid." else "$label menjawab ${reply.code}. Cek alamat dan key-nya.")
        }

        val models = reply.json()?.array("data").orEmpty().mapNotNull { it as? JsonObject }
        fun List<JsonObject>.ids() = mapNotNull { it.string("id") }

        return when (provider.provider) {
            AiProvider.OpenRouter -> listOf(AiProvider.OpenRouter.defaultModel) + models
                .filter { it.string("id").orEmpty().endsWith(":free") && "image" in it.obj("architecture")?.strings("input_modalities").orEmpty() }
                .ids()
            AiProvider.Mistral -> models.filter { (it.obj("capabilities")?.get("vision") as? JsonPrimitive)?.booleanOrNull == true }.ids()
            else -> models.ids().filterNot { NOT_FOR_VISION.containsMatchIn(it) }
        }.distinct()
    }

    /**
     * Asks [provider] about some JPEGs and returns the JSON object it answers, meant to follow [schema]. Photos over
     * its limit are left off the end ([AiEvent.Trimmed]). [onEvent] hears every step, also from OkHttp's threads.
     */
    suspend fun extract(
        provider: ProviderSettings,
        images: List<ByteArray>,
        system: String,
        instruction: String,
        schema: JsonObject,
        onEvent: (AiEvent) -> Unit = {},
    ): JsonObject {
        val sent = fitting(provider.provider, images)
        if (sent.size < images.size) onEvent(AiEvent.Trimmed(sent.size, images.size))

        var jsonMode = true
        while (true) {
            val reply = call(provider, payload(provider.model, sent, system, instruction, schema, jsonMode), onEvent)
            val error = reply.json()?.obj("error")?.takeIf { reply.ok }

            // Some models refuse JSON mode; the schema in the prompt still asks for an object.
            if (jsonMode && reply.code == 400 && reply.body.contains("response_format")) {
                onEvent(AiEvent.Missed(provider.model, "tidak mendukung mode JSON", retrying = true))
                jsonMode = false
                continue
            }

            // OpenRouter sometimes reports a failed upstream model inside a 200.
            val failed = if (error != null) reply.copy(code = (error["code"] as? JsonPrimitive)?.intOrNull ?: 502) else reply
            if (!failed.ok) {
                Log.w(TAG, "${provider.label} request failed: ${failed.code} ${failed.body.take(500)}")
                onEvent(AiEvent.Missed(provider.model, missReason(failed.code), retrying = false))
                throw failure(provider, failed)
            }

            onEvent(AiEvent.Answered)
            return answer(reply) ?: throw AiException("Hasil ${provider.label} tidak terbaca. Coba lagi atau foto ulang dengan cahaya lebih terang.")
        }
    }

    private suspend fun call(provider: ProviderSettings, payload: ByteArray, onEvent: (AiEvent) -> Unit): Reply {
        val total = payload.size.toLong()
        val body = ProgressBody(payload) { sent -> onEvent(if (sent < total) AiEvent.Uploading(sent, total) else AiEvent.Waiting(provider.model)) }
        val request = Request.Builder().url(url(provider, "chat/completions")).authorized(provider).post(body).build()

        onEvent(AiEvent.Sending(provider.model, total, 0))
        return try {
            http.withTimeout(TIMEOUT_S).newCall(request).fetch(::reply)
        } catch (_: UnknownHostException) {
            onEvent(AiEvent.Missed(provider.model, "tidak bisa dihubungi (cek koneksi)", retrying = false))
            throw AiOfflineException("Tidak bisa terhubung ke ${provider.label}. Cek koneksi internet.")
        } catch (_: IOException) {
            onEvent(AiEvent.Missed(provider.model, "tidak menjawab", retrying = false))
            throw AiRetryLaterException("${provider.label} tidak merespons (server penuh atau koneksi lambat). Coba lagi sebentar lagi.")
        }
    }

    private fun payload(model: String, images: List<ByteArray>, system: String, instruction: String, schema: JsonObject, jsonMode: Boolean) =
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", "$system\n\nAnswer with a single JSON object and nothing else. It must follow this JSON Schema:\n$schema")
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { image ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image)) }
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", instruction)
                        }
                    }
                }
            }
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }.toString().toByteArray()

    /** The photos one request to [provider] may carry, best first: as many as it takes, within its size limit. */
    private fun fitting(provider: AiProvider, images: List<ByteArray>): List<ByteArray> {
        var sent = provider.maxImages?.let(images::take) ?: images
        val budget = provider.maxRequestBytes ?: return sent
        // Base64 grows them by a third; the prompt needs some room too.
        while (sent.size > 1 && sent.sumOf { it.size.toLong() } * 4 / 3 + PROMPT_ROOM > budget) sent = sent.dropLast(1)
        return sent
    }

    private fun answer(reply: Reply): JsonObject? {
        val message = reply.json()?.array("choices")?.firstOrNull()?.let { it as? JsonObject }?.obj("message")
        val text = when (val content = message?.get("content")) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("")
            else -> null
        }
        return text?.let(::jsonObjectIn)
    }

    private fun missReason(code: Int) = when (code) {
        429 -> "kuotanya habis"
        401, 403 -> "key tidak diterima"
        404 -> "model tidak ada"
        in 500..599 -> "sedang sibuk"
        else -> "gagal ($code)"
    }

    private fun failure(provider: ProviderSettings, reply: Reply): AiException {
        val label = provider.label
        val said = reply.json()?.let { it.obj("error")?.string("message") ?: it.string("message") ?: it.string("detail") }?.trim()?.take(160)

        return when (reply.code) {
            401, 403 -> AiException("API key $label tidak valid. Cek Pengaturan AI.")
            402 -> AiException("$label minta kredit untuk model \"${provider.model}\". Pilih model gratis di Pengaturan AI.")
            404 -> AiException("Model \"${provider.model}\" tidak ada di $label. Pilih model lain di Pengaturan AI.")
            413 -> AiException("Fotonya terlalu besar untuk $label.")
            429 -> AiRetryLaterException("Kuota gratis $label habis untuk saat ini. Tunggu sebentar (atau besok) lalu coba lagi.", restUntil = quotaBack(reply))
            in 500..599 -> AiRetryLaterException("Server $label sedang sibuk. Coba lagi sebentar lagi.")
            else -> AiException("$label menolak permintaan ini" + (said?.let { ": $it" } ?: " (${reply.code})."))
        }
    }

    /**
     * When the quota is back: the wait the service asked for (a `retry-after` header, OpenRouter's reset time, Groq's
     * "try again in 7m12s"), midnight UTC for a daily limit that doesn't say, otherwise in a minute.
     */
    private fun quotaBack(reply: Reply): Long {
        val now = clock()
        reply.retryAfter?.trim()?.toDoubleOrNull()?.let { return now + (it * 1000).toLong() }
        reply.reset?.trim()?.toLongOrNull()?.takeIf { it > now }?.let { return it }
        TRY_AGAIN.find(reply.body)?.let { match -> return now + durationMillis(match.groupValues[1]) }
        if (PER_DAY.containsMatchIn(reply.body)) return nextMidnight(now, ZoneOffset.UTC)
        return now + 60_000
    }

    private fun url(provider: ProviderSettings, path: String): String {
        val endpoint = provider.endpoint?.takeIf { it.toHttpUrlOrNull() != null }
            ?: throw AiException("Alamat server ${provider.label} tidak valid. Cek Pengaturan AI.")
        return "$endpoint/$path"
    }

    private fun Request.Builder.authorized(provider: ProviderSettings) = apply {
        provider.key?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer ${it.trim()}") }
        if (provider.provider == AiProvider.OpenRouter) header("X-Title", "Diecast Tracker")
    }

    private fun reply(response: Response) =
        Reply(response.code, response.body.string(), response.header("retry-after"), response.header("x-ratelimit-reset"))

    private data class Reply(val code: Int, val body: String, val retryAfter: String?, val reset: String?) {
        val ok get() = code in 200..299

        fun json(): JsonObject? = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    }

    private companion object {
        const val TAG = "OpenAiClient"

        // Free tiers read photos slowly when busy.
        const val TIMEOUT_S = 60L
        const val LIST_TIMEOUT_S = 20L
        const val PROMPT_ROOM = 64 * 1024L

        val NOT_FOR_VISION = Regex("(whisper|tts|guard|orpheus|playai|embed|transcribe|moderation)", RegexOption.IGNORE_CASE)
        val TRY_AGAIN = Regex("try again in ((?:\\d+(?:\\.\\d+)?(?:ms|h|m|s))+)", RegexOption.IGNORE_CASE)
        val PER_DAY = Regex("per[ -]day|daily|\\bRPD\\b", RegexOption.IGNORE_CASE)
    }
}

/** The JSON object in a model's answer, past any `<think>` block, code fence or words around it. */
internal fun jsonObjectIn(text: String): JsonObject? {
    val cleaned = THINKING.replace(text, "")
    val start = cleaned.indexOf('{')
    val end = cleaned.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching { Json.parseToJsonElement(cleaned.substring(start, end + 1)) }.getOrNull() as? JsonObject
}

/** "1m26.4s", "860ms", "2h" → milliseconds. */
internal fun durationMillis(text: String): Long =
    DURATION_PART.findAll(text).sumOf { part ->
        val value = part.groupValues[1].toDouble()
        when (part.groupValues[2]) {
            "h" -> value * 3_600_000
            "m" -> value * 60_000
            "s" -> value * 1_000
            else -> value
        }
    }.toLong()

private val THINKING = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
private val DURATION_PART = Regex("(\\d+(?:\\.\\d+)?)(ms|h|m|s)")
