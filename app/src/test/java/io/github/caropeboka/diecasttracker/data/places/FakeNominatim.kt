package io.github.caropeboka.diecasttracker.data.places

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** OpenStreetMap's Nominatim from a table instead of the network (the web tests' `Http::fake`). */
class FakeNominatim : Interceptor {
    private val replies = mutableMapOf<String, Pair<Int, String>>()

    val requests = mutableListOf<HttpUrl>()
    val userAgents = mutableListOf<String?>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    /** [path] "reverse" or "search". */
    fun on(path: String, body: String, code: Int = 200) = apply { replies[path] = code to body }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request.url
        userAgents += request.header("User-Agent")
        val (code, body) = replies[request.url.encodedPath.trim('/')] ?: (599 to "{}")

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
