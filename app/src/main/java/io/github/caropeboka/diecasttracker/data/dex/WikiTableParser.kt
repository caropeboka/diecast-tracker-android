package io.github.caropeboka.diecasttracker.data.dex

/** A cell's plain text plus what's useful inside the markup: templates (flags) and the image file. */
data class WikiCell(
    /** The first line. */
    val text: String,
    val lines: List<String>,
    val templates: List<WikiTemplate>,
    val file: String?,
)

data class WikiTemplate(val name: String, val args: List<String>)

/**
 * Reads MediaWiki tables (`{| … |}`) into rows keyed by their (normalized) column headers, so each wiki's year
 * lists can be read the same way whatever their column order or styling (web: `WikiTableParser.php`).
 */
object WikiTableParser {
    private val TABLE = Regex("^\\{\\|.*?^\\|\\}", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))

    // Not a "any line break" class: byte 0x85 sits inside letters like Cyrillic "х" in file names (it bit the web).
    private val LINE_BREAK = Regex("\r\n|\r|\n")

    fun rows(wikitext: String): List<Map<String, WikiCell>> {
        val rows = mutableListOf<Map<String, WikiCell>>()

        for (table in TABLE.findAll(wikitext).map { it.value }) {
            val headers = mutableListOf<String>()
            var cells: MutableList<String>? = null

            for (line in table.split(LINE_BREAK)) {
                when {
                    line.startsWith("{|") || line.startsWith("|}") || line.startsWith("|+") -> Unit
                    line.startsWith("|-") -> {
                        cells?.let { rows += keyed(headers, it) }
                        cells = mutableListOf()
                    }
                    line.startsWith("!") -> line.substring(1).split("!!").forEach { headers += headerKey(it) }
                    line.startsWith("|") -> (cells ?: mutableListOf<String>().also { cells = it }) += line.substring(1)
                    // Continuation of a multi-line cell.
                    !cells.isNullOrEmpty() -> cells!![cells!!.lastIndex] += "\n" + line
                }
            }

            if (!cells.isNullOrEmpty()) rows += keyed(headers, cells!!)
        }

        return rows.filter { it.isNotEmpty() }
    }

    fun clean(raw: String): WikiCell {
        val templates = mutableListOf<WikiTemplate>()
        var file: String? = null

        var text = COMMENTS_AND_REFS.replace(raw, "")
        text = withoutAttributes(text)

        FILE_LINK.find(text)?.let { file = it.groupValues[1].replace('_', ' ').trim() }

        text = FILE.replace(text, "")
        text = TEMPLATE.replace(text) { match ->
            val parts = match.groupValues[1].split('|').map(String::trim)
            templates += WikiTemplate(parts.first().uppercase(), parts.drop(1))
            ""
        }
        text = PIPED_LINK.replace(text, "$1")
        text = LINK.replace(text, "$1")
        text = EXTERNAL_LINK.replace(text, "$1")
        text = BR.replace(text, "\n")
        // Not an HTML parser: wiki markup has broken tags like <font color=#0C0"> that would swallow the text after them.
        text = TAG.replace(text, "")
        text = decodeEntities(text.replace("'''", "").replace("''", ""))

        val lines = text.split('\n').map { WHITESPACE.replace(it, " ").trim() }.filter { it.isNotEmpty() }

        return WikiCell(lines.firstOrNull().orEmpty(), lines, templates, file)
    }

    /** "Col. #" → "col#", "Model Name" → "modelname", "Sub-series" → "subseries". */
    private fun headerKey(raw: String): String = NOT_KEY.replace(clean(raw).text.lowercase(), "")

    private fun keyed(headers: List<String>, cells: List<String>): Map<String, WikiCell> = buildMap {
        cells.forEachIndexed { i, cell ->
            val header = headers.getOrNull(i)
            if (!header.isNullOrEmpty()) put(header, clean(cell))
        }
    }

    /** Drops a leading `bgcolor="…" |` / `style=… |` from a cell (the first `|` outside links and templates). */
    private fun withoutAttributes(cell: String): String {
        var depth = 0
        var i = 0
        while (i < cell.length) {
            val pair = cell.substring(i, minOf(i + 2, cell.length))
            when {
                pair == "[[" || pair == "{{" -> {
                    depth++
                    i++
                }
                (pair == "]]" || pair == "}}") && depth > 0 -> {
                    depth--
                    i++
                }
                cell[i] == '|' && depth == 0 -> {
                    val prefix = cell.substring(0, i)
                    return if ('=' in prefix) cell.substring(i + 1) else cell
                }
            }
            i++
        }
        return cell
    }

    private val COMMENTS_AND_REFS = Regex("<!--.*?-->|<ref[^>]*/>|<ref[^>]*>.*?</ref>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val FILE_LINK = Regex("\\[\\[(?:File|Image):([^|\\]]+)", RegexOption.IGNORE_CASE)
    private val FILE = Regex("\\[\\[(?:File|Image):[^\\]]*\\]\\]", RegexOption.IGNORE_CASE)
    private val TEMPLATE = Regex("\\{\\{([^{}]*)\\}\\}")
    private val PIPED_LINK = Regex("\\[\\[[^|\\]]*\\|([^\\]]*)\\]\\]")
    private val LINK = Regex("\\[\\[([^\\]]*)\\]\\]")
    private val EXTERNAL_LINK = Regex("\\[https?://\\S+\\s*([^\\]]*)\\]")
    private val BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("</?[a-z][^>]*>", RegexOption.IGNORE_CASE)
    // Unicode spaces too (`&nbsp;` decodes to U+00A0). Not `(?U)`: Android's ICU regex rejects that flag.
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")
    private val NOT_KEY = Regex("[^a-z0-9#]")

    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»", "rsquo" to "’",
        "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "times" to "×", "eacute" to "é", "uuml" to "ü",
    )

    /** PHP's html_entity_decode for what wiki tables use. */
    internal fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val code = match.groupValues[1]
        when {
            code.startsWith("#x") || code.startsWith("#X") -> code.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            code.startsWith("#") -> code.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[code]
        } ?: match.value
    }
}
