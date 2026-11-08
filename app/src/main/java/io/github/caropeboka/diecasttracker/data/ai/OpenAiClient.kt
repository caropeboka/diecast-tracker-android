package io.github.caropeboka.diecasttracker.data.ai

import android.util.Log
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
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
 * The providers that speak OpenAI's chat completions (all but Gemini): photos go as data URLs (or plain base64) and
 * the answer comes back as a JSON object. Their support for JSON Schema differs, so the schema goes in the
 * prompt and JSON mode only asks for an object; the callers clean the fields as they do Gemini's.
 */
class OpenAiClient(
    private val http: OkHttpClient,
    private val texts: Texts,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Checks a key by listing its models: only those that read images where the service says which do, or whose
     * names say so. A service without a list gets a one-word question instead.
     */
    suspend fun availableModels(provider: ProviderSettings): List<String> {
        val label = provider.label
        val cloudflare = provider.provider == AiProvider.Cloudflare
        // Workers AI lists its models outside the OpenAI-style part of its API.
        val listing = if (cloudflare) url(provider, "").removeSuffix("/v1/") + "/models/search?per_page=100" else url(provider, "models")
        val request = Request.Builder().url(listing).authorized(provider).build()

        val reply = try {
            http.withTimeout(LIST_TIMEOUT_S).newCall(request).fetch(::reply)
        } catch (_: IOException) {
            throw AiException(texts(R.string.ai_err_connect_address, label))
        }

        if (reply.code == 404 && provider.provider != AiProvider.Custom && !cloudflare) return listOf(ping(provider))
        if (!reply.ok) {
            throw AiException(if (reply.code in listOf(401, 403)) texts(R.string.ai_err_key_invalid_short, label) else texts(R.string.ai_err_answered_code, label, reply.code))
        }

        val json = reply.json()
        val models = (json?.array("data") ?: json?.array("result")).orEmpty().mapNotNull { it as? JsonObject }
        fun List<JsonObject>.ids() = mapNotNull { it.string(if (cloudflare) "name" else "id") }
        val default = provider.provider.defaultModel

        return when (provider.provider) {
            AiProvider.OpenRouter -> listOf(AiProvider.OpenRouter.defaultModel) + models
                .filter { it.string("id").orEmpty().endsWith(":free") && "image" in it.obj("architecture")?.strings("input_modalities").orEmpty() }
                .ids()
            AiProvider.Mistral -> models.filter { (it.obj("capabilities")?.get("vision") as? JsonPrimitive)?.booleanOrNull == true }.ids()
            // Their lists don't say which read images; the names mostly do. One page of Cloudflare's may miss its default.
            AiProvider.Cloudflare, AiProvider.Nvidia, AiProvider.Zai, AiProvider.Ovh -> {
                val vision = models.ids().filter { VISION.containsMatchIn(it) && !NOT_FOR_VISION.containsMatchIn(it) }
                (if (cloudflare || default in vision) listOf(default) else emptyList()) + vision
            }
            else -> models.ids().filterNot { NOT_FOR_VISION.containsMatchIn(it) }
        }.distinct()
    }

    /** A one-word question: the model asked if the key works (or only the quota is out), else why not. */
    private suspend fun ping(provider: ProviderSettings): String {
        val model = provider.model.ifBlank { provider.provider.defaultModel }
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "Reply with OK.")
                }
            }
            put("max_tokens", 5)
        }.toString().toByteArray()
        val request = Request.Builder().url(url(provider, "chat/completions")).authorized(provider).post(ProgressBody(payload) {}).build()
        val reply = try {
            http.withTimeout(LIST_TIMEOUT_S).newCall(request).fetch(::reply)
        } catch (_: IOException) {
            throw AiException(texts(R.string.ai_err_connect_address, provider.label))
        }
        if (reply.ok || reply.code == 429) return model
        if (reply.code in listOf(401, 403)) throw AiException(texts(R.string.ai_err_key_invalid_short, provider.label))
        throw failure(provider, reply)
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
        val withSchema = "$system\n\nAnswer with a single JSON object and nothing else. It must follow this JSON Schema:\n$schema"
        val turns = listOf(ChatTurn(fromUser = true, instruction))
        var jsonMode = true
        val text = complete(
            provider,
            images,
            onEvent,
            // Some models refuse JSON mode; the schema in the prompt still asks for an object.
            retry = { reply ->
                (jsonMode && reply.code == 400 && reply.body.contains("response_format")).also { refused ->
                    if (refused) {
                        onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_no_json), retrying = true))
                        jsonMode = false
                    }
                }
            },
        ) { sent, raw -> payload(provider.model, sent, withSchema, turns, jsonMode, raw) }
        return text?.let(::jsonObjectIn) ?: throw AiException(texts(R.string.ai_err_unreadable_named, provider.label))
    }

    /** A conversation about some JPEGs (they go with the first turn): the answer in words, past any thinking. */
    suspend fun chat(
        provider: ProviderSettings,
        images: List<ByteArray>,
        system: String,
        turns: List<ChatTurn>,
        onEvent: (AiEvent) -> Unit = {},
    ): String {
        val text = complete(provider, images, onEvent) { sent, raw -> payload(provider.model, sent, system, turns, jsonMode = false, raw) }
        return text?.let { THINKING.replace(it, "").trim() }?.ifEmpty { null }
            ?: throw AiException(texts(R.string.ai_err_unreadable_named, provider.label))
    }

    /**
     * Sends what [payload] builds from the photos that fit (plain base64 or not) until [provider] answers: fewer or
     * smaller photos or the other encoding when it refuses them, again when [retry] says so. The answer's text.
     */
    private suspend fun complete(
        provider: ProviderSettings,
        images: List<ByteArray>,
        onEvent: (AiEvent) -> Unit,
        retry: (Reply) -> Boolean = { false },
        payload: (List<ByteArray>, Boolean) -> ByteArray,
    ): String? {
        var sent = fitting(provider.provider, images)
        if (sent.size < images.size) onEvent(AiEvent.Trimmed(sent.size, images.size))

        var raw = provider.provider.rawBase64
        var shrunk = false
        var flipped = false
        while (true) {
            val reply = call(provider, payload(sent, raw), onEvent)
            val error = reply.json()?.obj("error")?.takeIf { reply.ok }

            if (retry(reply)) continue
            if (reply.code == 413 || reply.code == 400 && TOO_BIG.containsMatchIn(reply.body)) {
                val keep = photosWithinTokens(provider.provider, reply.body, sent.size)
                when {
                    // Over a token limit (Groq's tokens a minute): a photo counts the same however small, so fewer go.
                    keep != null -> if (keep in 1 until sent.size) {
                        onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_too_many_tokens), retrying = true))
                        sent = sent.take(keep)
                        onEvent(AiEvent.Trimmed(sent.size, images.size))
                        continue
                    }
                    // Some take only small photos inline (NVIDIA's older ones: about 135 KB each).
                    !shrunk -> {
                        onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_too_big_retry), retrying = true))
                        sent = sent.map { shrinkJpeg(it, SMALL_PHOTO_BYTES) }
                        shrunk = true
                        continue
                    }
                    // Still too much: the first photo alone.
                    sent.size > 1 -> {
                        onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_too_big_retry), retrying = true))
                        sent = sent.take(1)
                        onEvent(AiEvent.Trimmed(sent.size, images.size))
                        continue
                    }
                }
            }
            // Some want plain base64, others a data URL.
            if (!flipped && reply.code == 400 && IMAGE_FORMAT.containsMatchIn(reply.body)) {
                onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_image_format), retrying = true))
                raw = !raw
                flipped = true
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
            return answer(reply)
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
            onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_offline), retrying = false))
            throw AiOfflineException(texts(R.string.ai_err_offline, provider.label))
        } catch (_: IOException) {
            onEvent(AiEvent.Missed(provider.model, texts(R.string.ai_miss_timeout), retrying = false))
            throw AiRetryLaterException(texts(R.string.ai_err_timeout, provider.label))
        }
    }

    private fun payload(model: String, images: List<ByteArray>, system: String, turns: List<ChatTurn>, jsonMode: Boolean, raw: Boolean) =
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                turns.forEachIndexed { index, turn ->
                    addJsonObject {
                        put("role", if (turn.fromUser) "user" else "assistant")
                        if (index > 0 || images.isEmpty()) {
                            put("content", turn.text)
                            return@addJsonObject
                        }
                        putJsonArray("content") {
                            images.forEach { image ->
                                addJsonObject {
                                    put("type", "image_url")
                                    val encoded = Base64.getEncoder().encodeToString(image)
                                    putJsonObject("image_url") { put("url", if (raw) encoded else "data:image/jpeg;base64,$encoded") }
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", turn.text)
                            }
                        }
                    }
                }
            }
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }.toString().toByteArray()

    /**
     * When [body] says the request was over a token limit ("Limit 8000, Requested 9472", Groq's 413): how many of
     * the [sent] photos should bring it under, at least one; 0 when one was already too many. Null when the limit
     * was something else.
     */
    private fun photosWithinTokens(provider: AiProvider, body: String, sent: Int): Int? {
        val numbers = TOKEN_LIMIT.find(body)
        if (numbers == null && !TOKENS.containsMatchIn(body)) return null
        if (sent <= 1) return 0
        val limit = numbers?.groupValues?.get(1)?.toLongOrNull()
        val requested = numbers?.groupValues?.get(2)?.toLongOrNull()
        if (limit == null || requested == null || requested <= limit) return sent - 1
        // Without the service's figure, the photos are taken to be most of what was asked.
        val perPhoto = provider.imageTokens?.toLong() ?: (requested / (sent + 1)).coerceAtLeast(1)
        val drop = ((requested - limit + perPhoto - 1) / perPhoto).toInt()
        return (sent - drop).coerceIn(1, sent - 1)
    }

    /** The photos one request to [provider] may carry, best first: as many as it takes, within its size limit. */
    private fun fitting(provider: AiProvider, images: List<ByteArray>): List<ByteArray> {
        var sent = provider.maxImages?.let(images::take) ?: images
        val budget = provider.maxRequestBytes ?: return sent
        // Base64 grows them by a third; the prompt needs some room too.
        while (sent.size > 1 && sent.sumOf { it.size.toLong() } * 4 / 3 + PROMPT_ROOM > budget) sent = sent.dropLast(1)
        return sent
    }

    private fun answer(reply: Reply): String? {
        val message = reply.json()?.array("choices")?.firstOrNull()?.let { it as? JsonObject }?.obj("message")
        return when (val content = message?.get("content")) {
            is JsonPrimitive -> content.contentOrNull
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("")
            else -> null
        }
    }

    private fun missReason(code: Int) = when (code) {
        429 -> texts(R.string.ai_miss_quota)
        401, 403 -> texts(R.string.ai_miss_key)
        404 -> texts(R.string.ai_miss_no_model)
        in 500..599 -> texts(R.string.ai_miss_busy)
        else -> texts(R.string.ai_miss_failed, code)
    }

    private fun failure(provider: ProviderSettings, reply: Reply): AiException {
        val label = provider.label
        val said = reply.json()?.let {
            it.obj("error")?.string("message") ?: it.string("message") ?: it.string("detail")
                // Cloudflare's own API.
                ?: (it.array("errors")?.firstOrNull() as? JsonObject)?.string("message")
        }?.trim()?.take(160)

        return when (reply.code) {
            401, 403 -> AiException(texts(R.string.ai_err_key_invalid, label))
            402 -> AiException(texts(R.string.ai_err_credit, label, provider.model))
            404 -> AiException(texts(R.string.ai_err_model_missing, label, provider.model))
            413 -> AiException(texts(if (TOKENS.containsMatchIn(reply.body)) R.string.ai_err_too_many_tokens else R.string.ai_err_too_big, label))
            429 -> AiRetryLaterException(texts(R.string.ai_err_quota, label), restUntil = quotaBack(reply))
            in 500..599 -> AiRetryLaterException(texts(R.string.ai_err_busy, label))
            else -> AiException(said?.let { texts(R.string.ai_err_refused_said, label, it) } ?: texts(R.string.ai_err_refused_code, label, reply.code))
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
            ?: throw AiException(texts(R.string.ai_err_bad_address, provider.label))
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

        val NOT_FOR_VISION = Regex("(whisper|tts|guard|orpheus|playai|embed|transcribe|moderation|retriever|diffusion|codegemma|recurrentgemma)", RegexOption.IGNORE_CASE)

        /** Names of models that read images: "-vl-", "vision", Gemma 3+, Llama 4, Qwen 3.5+, GLM-…V. */
        val VISION = Regex("(vision|[-_]vl\\b|[-_]vl[-_]|scout|maverick|gemma-[3-9]|omni|kimi-k2\\.[5-9]|qwen3\\.[5-9]|mistral-small-3|pixtral|llava|glm-[\\d.]+v)", RegexOption.IGNORE_CASE)
        val TOO_BIG = Regex("(too (large|big)|exceed|payload|request entity|180,?000)", RegexOption.IGNORE_CASE)
        val TOKEN_LIMIT = Regex("Limit:? (\\d+), Requested:? (\\d+)", RegexOption.IGNORE_CASE)
        val TOKENS = Regex("tokens per minute|\\bTPM\\b|token limit|too many tokens", RegexOption.IGNORE_CASE)
        val IMAGE_FORMAT = Regex("(image_url|base64|data ?ur[il]|image format|invalid image|unsupported image|decode image)", RegexOption.IGNORE_CASE)

        // Base64 of 180,000 characters at most.
        const val SMALL_PHOTO_BYTES = 135_000
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
