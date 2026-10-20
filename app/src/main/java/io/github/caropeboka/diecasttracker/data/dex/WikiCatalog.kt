package io.github.caropeboka.diecasttracker.data.dex

import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A sync failed for a reason the collector should read. */
class DexSyncException(message: String) : Exception(message)

/** A wiki the Dex can sync from (web: `WikiCatalog::SOURCES`). */
enum class WikiSource(val key: String, val brand: String, val label: String, val wiki: String, private val page: String, val firstYear: Int) {
    HotWheels("hotwheels_wiki", "Hot Wheels", "Hot Wheels Mainline", "hotwheels", "List of %d Hot Wheels", 2015),
    Matchbox("matchbox_wiki", "Matchbox", "Matchbox Mainline", "matchbox", "List of %d Matchbox", 2015);

    fun page(year: Int) = page.format(year)

    fun wikiUrl(page: String) = "https://$wiki.fandom.com/wiki/${page.replace(' ', '_')}"

    companion object {
        fun fromKey(key: String?): WikiSource? = entries.find { it.key == key }
    }
}

/** One numbered release, one row per variant, as read from a wiki year list. */
data class WikiItem(
    val number: String,
    val sort: Int,
    val variant: Int,
    val variantLabel: String?,
    val name: String,
    val series: String?,
    val seriesNumber: String?,
    val toyCode: String?,
    val isNew: Boolean = false,
    val isTh: Boolean = false,
    val isSth: Boolean = false,
    val exclusive: String? = null,
    val imageFile: String?,
)

sealed interface Download {
    class Image(val bytes: ByteArray, val extension: String) : Download

    /** Not on the CDN: don't ask again. */
    data object Missing : Download

    /** Connection trouble: try again later. */
    data object Retry : Download
}

/**
 * Year lists from the fan wikis (Hot Wheels Wiki, Matchbox Wiki) through their MediaWiki API (web: `WikiCatalog.php`).
 * Content is CC BY-SA; requests are identified and paced, and only made when the collector syncs.
 */
class WikiCatalog(
    http: OkHttpClient,
    private val userAgent: String,
    private val apiBase: (wiki: String) -> String = { "https://$it.fandom.com/api.php" },
    private val pauseMs: Long = 400,
    private val retryPauseMs: Long = 2_000,
) {
    private val api = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val cdn = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val pace = Mutex()
    private var called = false

    /** Every numbered release of a year, one row per variant. */
    suspend fun fetch(source: WikiSource, year: Int): Pair<String, List<WikiItem>> {
        val page = source.page(year)
        val response = call(source.wiki, mapOf("action" to "parse", "page" to page, "prop" to "wikitext"))

        response.obj("error")?.let { error ->
            throw DexSyncException(
                if (error.string("code") == "missingtitle") {
                    "Daftar $year belum ada di wiki."
                } else {
                    "Wiki menolak permintaan: " + (error.string("info") ?: error.string("code"))
                },
            )
        }

        val wikitext = response.obj("parse")?.string("wikitext").orEmpty()
        val items = if (source == WikiSource.Matchbox) matchboxItems(wikitext) else hotWheelsItems(wikitext)
        return page to items
    }

    /** Thumbnail URLs for image files (50 per request, the API's limit): file name → URL. */
    suspend fun imageUrls(source: WikiSource, files: List<String>, width: Int = 240): Map<String, String> {
        val urls = mutableMapOf<String, String>()

        for (chunk in files.distinct().chunked(50)) {
            val response = call(
                source.wiki,
                mapOf(
                    "action" to "query",
                    "titles" to chunk.joinToString("|") { "File:$it" },
                    "prop" to "imageinfo",
                    "iiprop" to "url",
                    "iiurlwidth" to width.toString(),
                ),
            )
            val query = response.obj("query") ?: continue

            // The API answers with normalized titles ("File:A_b.jpg" → "File:A b.jpg").
            val original = query.array("normalized").orEmpty().mapNotNull { it as? JsonObject }
                .mapNotNull { entry -> entry.string("to")?.let { to -> to to entry.string("from") } }
                .toMap()

            for (page in query.array("pages").orEmpty().mapNotNull { it as? JsonObject }) {
                val info = page.array("imageinfo")?.firstOrNull() as? JsonObject ?: continue
                val url = info.string("thumburl") ?: info.string("url") ?: continue
                val title = page.string("title") ?: continue
                val requested = original[title] ?: title
                urls[requested.substring(5).replace('_', ' ')] = url
            }
        }

        return urls
    }

    /** Several thumbnails in parallel (one round trip for the batch). */
    suspend fun download(urls: Map<Long, String>): Map<Long, Download> = coroutineScope {
        urls.map { (key, url) -> async { key to download(url) } }.awaitAll().toMap()
    }

    private suspend fun download(url: String): Download {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val response = try {
            cdn.newCall(request).fetch()
        } catch (_: IOException) {
            return Download.Retry
        }

        return response.use {
            val type = it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            val extension = IMAGE_TYPES[type]
            when {
                // Busy or rate-limited: the photo is there, ask again later.
                it.code >= 500 || it.code == 429 -> Download.Retry
                !it.isSuccessful || extension == null -> Download.Missing
                else -> {
                    val bytes = try {
                        it.body.bytes()
                    } catch (_: IOException) {
                        return Download.Retry
                    }
                    if (bytes.size > MAX_IMAGE_BYTES) Download.Missing else Download.Image(bytes, extension)
                }
            }
        }
    }

    /** Hot Wheels Wiki: Toy # | Col.# | Model Name | Series | Series # | Photo. */
    private fun hotWheelsItems(wikitext: String): List<WikiItem> = numberVariants(
        WikiTableParser.rows(wikitext).mapNotNull { row ->
            val number = row["col#"]?.text.orEmpty()
            val name = row["modelname"]
            // Unnumbered specials (store promos, "No #") aren't part of the numbered set.
            if (!HW_NUMBER.matches(number) || name == null) return@mapNotNull null

            item(
                number = number.padStart(3, '0'),
                sort = number.toInt(),
                name = name,
                series = row["series"],
                seriesNumber = row["series#"]?.text,
                toyCode = row["toy#"]?.text,
                photo = row["photo"],
            )
        },
    )

    /** Matchbox Wiki: Model # | Man # | Card # | Sub-series (or Segment) | Model Name | Photo. */
    private fun matchboxItems(wikitext: String): List<WikiItem> = numberVariants(
        WikiTableParser.rows(wikitext).mapNotNull { row ->
            val match = MB_NUMBER.matchEntire(row["model#"]?.text.orEmpty())
            val name = row["modelname"]
            if (match == null || name == null) return@mapNotNull null

            item(
                number = "MB" + match.groupValues[1],
                sort = match.groupValues[1].toInt(),
                name = name,
                series = row["subseries"] ?: row["segment"] ?: row["series"],
                seriesNumber = null,
                // "JHM65 (USA) / JHP00 (ROW)": the first card code.
                toyCode = CARD_CODE.find(row["card#"]?.text.orEmpty())?.value,
                photo = row["photo"] ?: row["photoorcolor"],
            )
        },
    )

    private fun item(
        number: String,
        sort: Int,
        name: WikiCell,
        series: WikiCell?,
        seriesNumber: String?,
        toyCode: String?,
        photo: WikiCell?,
    ): WikiItem {
        var title = name.text
        var variant: Int? = null
        var label: String? = null

        // "Mazda RX-7 (2nd Color - Zamac)" → name + variant 2 + label.
        COLOR_VARIANT.find(title)?.let { match ->
            variant = match.groupValues[2].toInt()
            label = WHITESPACE.replace(match.groupValues[1].trim(), " ")
            title = title.substring(0, title.length - match.value.length).trim()
        }
        title = NEW_SUFFIX.replace(title, "").trim()

        val notes = name.lines.drop(1) + series?.lines.orEmpty()
        val templates = (name.templates + series?.templates.orEmpty()).map { it.name }
        val text = notes.joinToString(" ")
        val lower = text.lowercase()
        val file = photo?.file

        return WikiItem(
            number = number,
            sort = sort,
            variant = variant ?: 0,
            variantLabel = label?.take(255),
            name = title.take(255),
            series = series?.text?.takeIf(String::isNotEmpty)?.take(255),
            seriesNumber = seriesNumber?.takeIf { SERIES_NUMBER.matches(it) }?.replace(WHITESPACE, ""),
            toyCode = toyCode?.takeIf { TOY_CODE.matches(it) },
            isNew = "NM" in templates || NEW_WORD.containsMatchIn(text),
            isSth = "STH" in templates || "super treasure hunt" in lower,
            isTh = "TH" in templates || ("treasure hunt" in lower && "super treasure hunt" !in lower),
            exclusive = templates.firstNotNullOfOrNull { EXCLUSIVES[it] } ?: EXCLUSIVE.find(text)?.groupValues?.get(1),
            imageFile = file?.takeIf { !it.lowercase().startsWith("image not available") }?.take(255),
        )
    }

    /** Variant numbers: from "(2nd Color)" when given, otherwise in page order — unique per number. */
    private fun numberVariants(items: List<WikiItem>): List<WikiItem> {
        val taken = mutableMapOf<String, MutableList<Int>>()
        return items.map { item ->
            val used = taken.getOrPut(item.number) { mutableListOf() }
            var variant = item.variant
            if (variant == 0 || variant in used) {
                variant = 1
                while (variant in used) variant++
            }
            used += variant
            item.copy(variant = variant)
        }
    }

    private suspend fun call(wiki: String, params: Map<String, String>): JsonObject {
        val url = apiBase(wiki).toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
            addQueryParameter("format", "json")
            addQueryParameter("formatversion", "2")
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()

        pace.withLock {
            if (called) delay(pauseMs)
            called = true
        }

        // A dropped connection (phone hotspot) is retried twice before the year counts as failed.
        var attempt = 1
        while (true) {
            val response = try {
                api.newCall(request).fetch()
            } catch (_: IOException) {
                if (attempt == 3) throw DexSyncException("Tidak bisa menghubungi wiki. Cek koneksi internet lalu coba lagi.")
                attempt++
                delay(retryPauseMs)
                continue
            }

            val (code, body) = response.use { it.code to runCatching { it.body.string() }.getOrNull() }
            val json = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            if (code !in 200..299 || json == null) throw DexSyncException("Wiki sedang bermasalah (HTTP $code). Coba lagi nanti.")
            return json
        }
    }

    private suspend fun Call.fetch(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            },
        )
    }

    private companion object {
        /** Store-exclusive templates on the Hot Wheels Wiki. */
        val EXCLUSIVES = mapOf("KR" to "Kroger", "WM" to "Walmart", "TG" to "Target", "DG" to "Dollar General", "DT" to "Dollar Tree")
        val IMAGE_TYPES = mapOf("image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif")
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024

        val HW_NUMBER = Regex("^\\d{1,3}$")
        val MB_NUMBER = Regex("^MB\\s*0*(\\d{1,3})$", RegexOption.IGNORE_CASE)
        val CARD_CODE = Regex("[A-Z0-9]{5}")
        val COLOR_VARIANT = Regex("\\s*\\(((\\d+)(?:st|nd|rd|th)\\s+colou?r[^)]*)\\)\\s*$", RegexOption.IGNORE_CASE)
        val NEW_SUFFIX = Regex("\\s*\\(new\\)\\s*$", RegexOption.IGNORE_CASE)
        val NEW_WORD = Regex("\\bnew\\b", RegexOption.IGNORE_CASE)
        val EXCLUSIVE = Regex("(\\w[\\w ]*?) exclusive", RegexOption.IGNORE_CASE)
        val SERIES_NUMBER = Regex("^\\d+\\s*/\\s*\\d+$")
        val TOY_CODE = Regex("^[A-Z0-9]{4,8}$")
        val WHITESPACE = Regex("\\s+")
    }
}
