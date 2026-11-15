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
    private val searches = mutableMapOf<String, String>()

    val requests = mutableListOf<HttpUrl>()
    val userAgents = mutableListOf<String?>()

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    /** [path] "reverse" or "search". */
    fun on(path: String, body: String, code: Int = 200) = apply { replies[path] = code to body }

    /** A search for exactly [query] answers [body]; other searches answer what [on] set for "search". */
    fun onSearch(query: String, body: String) = apply { searches[query] = body }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request.url
        userAgents += request.header("User-Agent")
        val path = request.url.encodedPath.trim('/')
        val (code, body) = request.url.queryParameter("q")?.takeIf { path == "search" }?.let(searches::get)?.let { 200 to it }
            ?: replies[path]
            ?: (599 to "{}")

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
