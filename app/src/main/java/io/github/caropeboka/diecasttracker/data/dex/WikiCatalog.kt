package io.github.caropeboka.diecasttracker.data.dex

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.Texts
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
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A sync failed for a reason the collector should read. */
class DexSyncException(message: String) : Exception(message)

/** A wiki the Dex can sync from (web: `WikiCatalog::SOURCES`, which has the first two). */
enum class WikiSource(
    val key: String,
    val brand: String,
    val label: String,
    val wiki: String,
    private val page: String,
    /** The first year with a list; null for a wiki with one list and no years. */
    val firstYear: Int?,
    /** The last year the wiki keeps a list for, when it stopped. */
    val lastYear: Int? = null,
    /** The whole line's scale; null when it varies. */
    val scale: String? = "1:64",
    /** Other brands whose releases are on these lists too. */
    val includes: List<String> = emptyList(),
    @StringRes val note: Int? = null,
) {
    // From the first years whose lists have a collector number per release (web: 2015).
    HotWheels("hotwheels_wiki", "Hot Wheels", "Hot Wheels Mainline", "hotwheels", "List of %d Hot Wheels", 1989),
    Matchbox("matchbox_wiki", "Matchbox", "Matchbox Mainline", "matchbox", "List of %d Matchbox", 2000),
    MiniGt(
        "minigt_wiki", "Mini GT", "Mini GT", "minigt", "%d Models", 2018, lastYear = 2022,
        includes = listOf("Kaido House", "KaidoHouse", "Kaido★House"), note = R.string.wiki_note_minigt,
    ),
    // The lineup on sale now, numbered No. 1–150 like the boxes.
    Tomica("tomica_wiki", "Tomica", "Tomica Regular", "tomica", "List of Main Lineup Products", firstYear = null, scale = null, note = R.string.wiki_note_tomica),
    // One page for every year: a year's list is what came out that year (the numbers are used again for other models).
    TomicaPremium(
        "tomica_premium_wiki", "Tomica", "Tomica Premium", "tomica", "Tomica Premium (toyline)", 2015, scale = null,
        note = R.string.wiki_note_tomica_premium,
    );

    /** One list, no years. */
    val single get() = firstYear == null

    // Locale.ROOT: in some languages "%d" writes other digits than the wiki's.
    fun page(year: Int?) = if (year == null || "%d" !in page) page else String.format(Locale.ROOT, page, year)

    /** On the Dex's chips: the brand, or the line when the brand has more than one. */
    val shortName get() = if (entries.count { it.brand == brand } > 1) label else brand

    /** The years to choose from, newest first. */
    fun years(current: Int): List<Int> = firstYear?.let { first -> (minOf(current, lastYear ?: current) downTo first).toList() }.orEmpty()

    /** Whether diecasts of [brand] can be on these lists. */
    fun covers(brand: String): Boolean = brand.trim().let { name -> this.brand.equals(name, ignoreCase = true) || includes.any { it.equals(name, ignoreCase = true) } }

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
    private val texts: Texts,
    private val apiBase: (wiki: String) -> String = { "https://$it.fandom.com/api.php" },
    private val pauseMs: Long = 400,
    private val retryPauseMs: Long = 2_000,
) {
    private val api = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val cdn = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val pace = Mutex()
    private var called = false

    /** Every numbered release of a year (or of a [WikiSource.single] list: null), one row per variant. */
    suspend fun fetch(source: WikiSource, year: Int?): Pair<String, List<WikiItem>> {
        val page = source.page(year)
        val response = call(source.wiki, mapOf("action" to "parse", "page" to page, "prop" to "wikitext"))

        response.obj("error")?.let { error ->
            throw DexSyncException(
                when {
                    error.string("code") != "missingtitle" -> texts(R.string.wiki_refused, error.string("info") ?: error.string("code").orEmpty())
                    year == null -> texts(R.string.wiki_list_missing)
                    else -> texts(R.string.wiki_year_missing, year)
                },
            )
        }

        val wikitext = response.obj("parse")?.string("wikitext").orEmpty()
        val items = when (source) {
            WikiSource.HotWheels -> hotWheelsItems(wikitext)
            WikiSource.Matchbox -> matchboxItems(wikitext)
            WikiSource.MiniGt -> miniGtItems(wikitext)
            WikiSource.Tomica -> tomicaItems(wikitext)
            WikiSource.TomicaPremium -> tomicaPremiumItems(wikitext, checkNotNull(year))
        }
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

    /**
     * Hot Wheels Wiki: Toy # | Col.# | Model Name | Series | Series # | Photo. 1990–1994 name the column "Casting
     * Name" or "Car name" and give a row per colour; 1997–1999 write each row as a template (see [templateRows]).
     */
    private fun hotWheelsItems(wikitext: String): List<WikiItem> = numberVariants(
        (WikiTableParser.rows(wikitext) + WikiTableParser.rows(templateRows(wikitext))).mapNotNull { row ->
            val number = row["col#"]?.text.orEmpty()
            val name = row["modelname"] ?: row["castingname"] ?: row["carname"]
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
                colour = row["color"]?.text,
            )
        },
    )

    /**
     * The rows 1997–1999 write as `{{List0000White|toy|col|name|series|series #|}}`, each followed by its
     * `|[[Image:…]]` cell, as a table the parser reads.
     */
    private fun templateRows(wikitext: String): String {
        val rows = ROW_TEMPLATE.findAll(wikitext).mapNotNull { match ->
            val cells = topLevelSplit(match.groupValues[1])
            if (cells.size < 5) return@mapNotNull null
            (cells.take(5) + match.groupValues[2]).joinToString("\n", "|-\n") { "|" + it.trim() }
        }.toList()
        if (rows.isEmpty()) return ""
        return "{|\n!Toy #!!Col #!!Model Name!!Series!!Series #!!Photo\n" + rows.joinToString("\n") + "\n|}"
    }

    /** "a|[[b|c]]|{{d|e}}" → a, [[b|c]], {{d|e}}: split on the bars outside links and templates. */
    private fun topLevelSplit(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("[[", i) || text.startsWith("{{", i) -> { depth++; i++ }
                text.startsWith("]]", i) || text.startsWith("}}", i) -> { depth = maxOf(0, depth - 1); i++ }
                text[i] == '|' && depth == 0 -> { parts += text.substring(start, i); start = i + 1 }
            }
            i++
        }
        parts += text.substring(start)
        return parts
    }

    /**
     * Matchbox Wiki: Model # | Man # | Card # | Sub-series (or Segment) | Model Name | Photo. 2000 heads them "Model"
     * and "Card", with the region after the number ("MB02 (USA)"), which then names the variant.
     */
    private fun matchboxItems(wikitext: String): List<WikiItem> = numberVariants(
        WikiTableParser.rows(wikitext).mapNotNull { row ->
            val model = (row["model#"] ?: row["model"])?.lines.orEmpty().joinToString(" ")
            val match = MB_NUMBER.matchEntire(model) ?: MB_NUMBER_REGION.matchEntire(model)
            val name = row["modelname"]
            if (match == null || name == null) return@mapNotNull null

            item(
                number = "MB" + match.groupValues[1],
                sort = match.groupValues[1].toInt(),
                name = name,
                series = row["subseries"] ?: row["segment"] ?: row["series"],
                seriesNumber = null,
                // "JHM65 (USA) / JHP00 (ROW)": the first card code.
                toyCode = CARD_CODE.find((row["card#"] ?: row["card"])?.text.orEmpty())?.value,
                photo = row["photo"] ?: row["photoorcolor"],
                colour = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() && !it.equals("core", ignoreCase = true) },
            )
        },
    )

    /**
     * Mini GT Wiki: Code | Name | LHD/RHD | Brand (the car's make) | Photo, whose caption names exclusives ("Mijo / USA
     * Exclusive") and Kaido House's releases. The make stands in for a series, so the Dex groups by it.
     */
    private fun miniGtItems(wikitext: String): List<WikiItem> = numberVariants(
        WikiTableParser.rows(wikitext).mapNotNull { row ->
            val code = row["code"]?.text.orEmpty().uppercase()
            val match = MINI_GT_CODE.matchEntire(code)
            val name = row["name"]?.text?.takeIf(String::isNotEmpty)
            if (match == null || name == null) return@mapNotNull null

            val caption = row["photo"]?.let { fileCaption(it.raw) }.orEmpty()
            val kaido = match.groupValues[1] == "KHMG"
            WikiItem(
                number = code,
                // Mini GT's own numbers first, the sub-lines after them.
                sort = match.groupValues[2].toInt() + if (match.groupValues[1] == "MGT") 0 else 100_000,
                variant = 0,
                variantLabel = null,
                name = name.take(255),
                series = (if (kaido) "Kaido House" else row["brand"]?.text?.takeIf(String::isNotEmpty))?.take(255),
                seriesNumber = null,
                toyCode = null,
                exclusive = caption.firstNotNullOfOrNull { MINI_GT_EXCLUSIVE.find(it)?.groupValues?.get(1) }?.take(255),
                imageFile = row["photo"]?.file?.take(255),
            )
        },
    )

    /**
     * Tomica Wiki's lineup page: "No. 12 Toyota Alphard" | release date, one table per section (regular, long type),
     * which becomes the series.
     */
    private fun tomicaItems(wikitext: String): List<WikiItem> = numberVariants(
        sections(wikitext).flatMap { (heading, text) ->
            val series = TOMICA_RANGE.replace(heading, "").trim().takeIf(String::isNotEmpty)
            WikiTableParser.rows(text).mapNotNull { row ->
                val cell = row.entries.firstOrNull { (key, _) -> key.startsWith("productno") }?.value?.text.orEmpty()
                val match = TOMICA_NUMBER.matchEntire(cell) ?: return@mapNotNull null
                WikiItem(
                    number = "No." + match.groupValues[1],
                    sort = match.groupValues[1].toInt(),
                    variant = 0,
                    variantLabel = null,
                    name = match.groupValues[2].trim().take(255),
                    series = series?.take(255),
                    seriesNumber = null,
                    toyCode = null,
                    imageFile = null,
                )
            }
        },
    )

    /**
     * Tomica Wiki's Tomica Premium page: "No. 15 Nissan Fairlady Z (Z31)" | release date ("11/2024", "4/2/2015",
     * "1/21/23"), the main line and Unlimited (numbered on its own, so "Unlimited No.1" and after the main line). The
     * sets, RS, Racing, reprints and exclusives have no numbers and are left out. Only what came out in [year].
     */
    private fun tomicaPremiumItems(wikitext: String, year: Int): List<WikiItem> = numberVariants(
        commemorations(
            sections(wikitext).flatMap { (heading, text) ->
                val unlimited = heading.contains("Unlimited", ignoreCase = true)
                if (!unlimited && !heading.equals("Main Line", ignoreCase = true)) return@flatMap emptyList()
                WikiTableParser.rows(text).mapNotNull { row ->
                    val cell = row.entries.firstOrNull { (key, _) -> key.startsWith("productname") }?.value?.text.orEmpty()
                    val date = row.entries.firstOrNull { (key, _) -> key.startsWith("releasedate") }?.value?.text.orEmpty()
                    val released = RELEASE_YEAR.find(date.trim())?.groupValues?.get(1)?.toInt()?.let { if (it < 100) 2000 + it else it }
                    val match = TOMICA_NUMBER.matchEntire(cell)
                    if (released != year || match == null) return@mapNotNull null
                    val number = match.groupValues[1].toInt()
                    WikiItem(
                        number = (if (unlimited) "Unlimited No." else "No.") + number,
                        sort = number + if (unlimited) 1_000 else 0,
                        variant = 0,
                        variantLabel = null,
                        name = match.groupValues[2].trim().take(255),
                        series = if (unlimited) "Tomica Premium Unlimited" else "Tomica Premium",
                        seriesNumber = null,
                        toyCode = null,
                        imageFile = null,
                    )
                }
            },
        ),
    )

    /**
     * "Morris Mini (Release Commemoration Version)" next to "Morris Mini" under the same number: the same model, so the
     * bracket becomes its variant label.
     */
    private fun commemorations(items: List<WikiItem>): List<WikiItem> {
        val names = items.groupBy { it.number }.mapValues { (_, group) -> group.map { it.name }.toSet() }
        return items.map { item ->
            val bracket = TRAILING_BRACKET.find(item.name) ?: return@map item
            val base = item.name.substring(0, bracket.range.first).trim()
            if (base in names.getValue(item.number)) item.copy(name = base, variantLabel = bracket.groupValues[1].take(255)) else item
        }
    }

    /** The page cut at its `== headings ==`: (heading as plain text, the text under it). */
    private fun sections(wikitext: String): List<Pair<String, String>> {
        val headings = HEADING.findAll(wikitext).toList()
        return headings.mapIndexed { i, heading ->
            val end = headings.getOrNull(i + 1)?.range?.first ?: wikitext.length
            WikiTableParser.clean(heading.groupValues[1]).text to wikitext.substring(heading.range.last + 1, end)
        }
    }

    /** `[[File:x.jpg|thumb|[[A|b]] <br/> Japan Exclusive|center]]` → "b", "Japan Exclusive": the lines that aren't layout options. */
    private fun fileCaption(raw: String): List<String>? {
        val start = raw.indexOf("[[File:", ignoreCase = true).takeIf { it >= 0 } ?: raw.indexOf("[[Image:", ignoreCase = true).takeIf { it >= 0 } ?: return null
        var depth = 0
        var end = raw.length
        var i = start
        while (i < raw.length - 1) {
            val pair = raw.substring(i, i + 2)
            if (pair == "[[") depth++
            if (pair == "]]" && --depth == 0) {
                end = i
                break
            }
            if (pair == "[[" || pair == "]]") i++
            i++
        }
        val parts = topLevelSplit(raw.substring(start + 2, end)).drop(1)
        return parts.filterNot { LAYOUT_OPTION.matches(it.trim()) }.flatMap { WikiTableParser.clean(it).lines }
    }

    private fun item(
        number: String,
        sort: Int,
        name: WikiCell,
        series: WikiCell?,
        seriesNumber: String?,
        toyCode: String?,
        photo: WikiCell?,
        colour: String? = null,
    ): WikiItem {
        var title = name.text
        var variant: Int? = null
        var label: String? = colour?.trim()?.ifEmpty { null }

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
                if (attempt == 3) throw DexSyncException(texts(R.string.wiki_offline))
                attempt++
                delay(retryPauseMs)
                continue
            }

            val (code, body) = response.use { it.code to runCatching { it.body.string() }.getOrNull() }
            val json = body?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject
            if (code !in 200..299 || json == null) throw DexSyncException(texts(R.string.wiki_trouble, code))
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
        val MB_NUMBER_REGION = Regex("^MB\\s*0*(\\d{1,3})\\s*\\(([^)]*)\\)$", RegexOption.IGNORE_CASE)

        // A 1997–1999 row: the template's parameters, then the photo cell after it.
        val ROW_TEMPLATE = Regex("\\{\\{\\s*List[0-9A-Za-z]*\\s*\\|((?:[^{}]|\\{\\{[^{}]*\\}\\})*)\\}\\}\\s*(?:\\|\\s*(\\[\\[(?:File|Image):[^\\]]*\\]\\]))?")
        val CARD_CODE = Regex("[A-Z0-9]{5}")
        val COLOR_VARIANT = Regex("\\s*\\(((\\d+)(?:st|nd|rd|th)\\s+colou?r[^)]*)\\)\\s*$", RegexOption.IGNORE_CASE)
        val NEW_SUFFIX = Regex("\\s*\\(new\\)\\s*$", RegexOption.IGNORE_CASE)
        val NEW_WORD = Regex("\\bnew\\b", RegexOption.IGNORE_CASE)
        val EXCLUSIVE = Regex("(\\w[\\w ]*?) exclusive", RegexOption.IGNORE_CASE)
        val SERIES_NUMBER = Regex("^\\d+\\s*/\\s*\\d+$")
        val TOY_CODE = Regex("^[A-Z0-9]{4,8}$")
        val MINI_GT_EXCLUSIVE = Regex("^(.+?)\\s+exclusive\\b", RegexOption.IGNORE_CASE)
        val MINI_GT_CODE = Regex("^(MGT|MGTS|KHMG)(\\d{2,6})$")
        val TOMICA_NUMBER = Regex("^No\\.?\\s*(\\d{1,3})\\s+(.+)$", RegexOption.IGNORE_CASE)
        val TOMICA_RANGE = Regex("\\(\\s*No\\.[^)]*\\)", RegexOption.IGNORE_CASE)
        // "4/2/2015", "11/2024", "1/21/23": the year at the end.
        val RELEASE_YEAR = Regex("(?:^|/)(\\d{4}|\\d{2})$")
        val TRAILING_BRACKET = Regex("\\(([^()]+)\\)\\s*$")
        val HEADING = Regex("^==+\\s*(.+?)\\s*==+\\s*$", RegexOption.MULTILINE)
        val LAYOUT_OPTION = Regex("^(thumb|thumbnail|frame|frameless|border|center|centre|left|right|none|upright.*|\\d*x?\\d+px|(link|alt|class|page|lang)=.*)$", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("\\s+")
    }
}
