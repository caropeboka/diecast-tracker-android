package io.github.caropeboka.diecasttracker.data.ai

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
 * Answers Gemini requests from a table instead of the network (the web tests' `Http::fake`), and records what
 * was asked: "models" for the model list, otherwise the model a generateContent call went to.
 */
class FakeGemini : Interceptor {
    sealed interface Reply {
        data class Json(val code: Int, val body: String) : Reply

        data class Fail(val error: IOException) : Reply
    }

    private val routes = linkedMapOf<String, Reply>()
    private var fallback: Reply = Reply.Json(599, """{"error":"no route"}""")

    /** Called before each request is answered, e.g. to move a fake clock. */
    var onRequest: () -> Unit = {}

    val calls = mutableListOf<String>()
    val keys = mutableListOf<String?>()
    val bodies = mutableListOf<String>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun on(target: String, reply: Reply) = apply { routes[target] = reply }

    fun on(target: String, code: Int, body: String = "{}") = on(target, Reply.Json(code, body))

    fun otherwise(reply: Reply) = apply { fallback = reply }

    fun otherwise(code: Int, body: String = "{}") = otherwise(Reply.Json(code, body))

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = request.url.encodedPath.substringAfter("/v1beta/models").trimStart('/').substringBefore(':').ifEmpty { "models" }
        calls += target
        keys += request.header("x-goog-api-key")
        bodies += request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
        onRequest()

        return when (val reply = routes[target] ?: fallback) {
            is Reply.Fail -> throw reply.error
            is Reply.Json -> Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("fake")
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
