package io.github.caropeboka.diecasttracker.data.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Answers AI requests from a table instead of the network (the web tests' `Http::fake`), and records what was asked.
 * Gemini's targets are "models" for the model list, otherwise the model a generateContent call went to; the
 * OpenAI-style providers' are host + path ("api.groq.com/openai/v1/chat/completions"). Several replies for one
 * target are given in turn, the last one again and again.
 */
class FakeGemini : Interceptor {
    sealed interface Reply {
        data class Json(val code: Int, val body: String, val headers: Map<String, String> = emptyMap()) : Reply

        data class Fail(val error: IOException) : Reply
    }

    private val routes = linkedMapOf<String, ArrayDeque<Reply>>()
    private var fallback: Reply = Reply.Json(599, """{"error":"no route"}""")

    /** Called before each request is answered, e.g. to move a fake clock. */
    var onRequest: () -> Unit = {}

    val calls = mutableListOf<String>()
    val keys = mutableListOf<String?>()
    val bodies = mutableListOf<String>()

    /** Every request's `Authorization` header (the OpenAI-style providers' key). */
    val bearers = mutableListOf<String?>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun on(target: String, vararg replies: Reply) = apply { routes[target] = ArrayDeque(replies.toList()) }

    fun on(target: String, code: Int, body: String = "{}") = on(target, Reply.Json(code, body))

    fun otherwise(reply: Reply) = apply { fallback = reply }

    fun otherwise(code: Int, body: String = "{}") = otherwise(Reply.Json(code, body))

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val target = if ("/v1beta/models" in path) {
            path.substringAfter("/v1beta/models").trimStart('/').substringBefore(':').ifEmpty { "models" }
        } else {
            request.url.host + path
        }
        calls += target
        keys += request.header("x-goog-api-key")
        bearers += request.header("Authorization")
        bodies += request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
        onRequest()

        val queue = routes[target]
        val reply = when {
            queue == null || queue.isEmpty() -> fallback
            queue.size > 1 -> queue.removeFirst()
            else -> queue.first()
        }
        return when (reply) {
            is Reply.Fail -> throw reply.error
            is Reply.Json -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("fake")
                .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
                .body(reply.body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    companion object {
        /** A model list as `GET /models` returns it. */
        fun models(vararg names: String, methods: List<String> = listOf("generateContent")): String = buildJsonObject {
            putJsonArray("models") {
                names.forEach { name ->
                    add(
                        buildJsonObject {
                            put("name", "models/$name")
                            put("supportedGenerationMethods", buildJsonArray { methods.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            }
        }.toString()

        /** A generateContent answer whose text is [fields] as JSON. */
        fun answer(fields: JsonObject): String = buildJsonObject {
            putJsonArray("candidates") {
                add(
                    buildJsonObject {
                        putJsonObject("content") {
                            putJsonArray("parts") { add(buildJsonObject { put("text", fields.toString()) }) }
                        }
                    },
                )
            }
        }.toString()

        fun answer(json: String): String = answer(Json.parseToJsonElement(json) as JsonObject)
    }
}

/** [AiSettings]' model list cache, in memory. */
class MemoryModelCache : ModelListCache {
    val stored = mutableMapOf<String, List<String>>()

    override suspend fun get(key: String) = stored[key]

    override suspend fun put(key: String, models: List<String>) {
        stored[key] = models
    }
}

/** [AiSettings]' provider rests, in memory. */
class MemoryRests : ProviderRests {
    val rests = mutableMapOf<AiProvider, Long>()

    override suspend fun until(provider: AiProvider) = rests[provider] ?: 0

    override suspend fun rest(provider: AiProvider, until: Long) {
        rests[provider] = until
    }

    override suspend fun clear(provider: AiProvider) {
        rests.remove(provider)
    }
}

/** The app's [AiVision] with [setup] (only Gemini by default), every request answered by this fake. */
fun FakeGemini.vision(setup: Flow<AiSetup> = MutableStateFlow(AiSetup.gemini("test-key"))) = AiVision(
    setup,
    GeminiClient(client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta"),
    OpenAiClient(client),
    MemoryRests(),
)
