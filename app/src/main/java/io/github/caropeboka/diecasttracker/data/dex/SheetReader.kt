package io.github.caropeboka.diecasttracker.data.dex

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class SheetException(message: String) : Exception(message)

/**
 * The rows of a spreadsheet: the first sheet of an .xlsx (Excel, Google Sheets, LibreOffice) or a .csv
 * (web: OpenSpout in `CatalogImport::fromSpreadsheet`). A small reader of its own: a full spreadsheet library
 * would weigh more than the app.
 */
object SheetReader {
    /** Excel's "CSV UTF-8" starts with a byte-order mark. */
    private val BOM = Char(0xFEFF).toString()

    /** Reads [bytes] as .xlsx when they're a zip, otherwise as CSV text. */
    fun read(bytes: ByteArray): List<List<String>> =
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) xlsx(bytes) else csv(text(bytes))

    /** Comma, semicolon (Excel in Indonesian) or tab separated, as the first line suggests (web: `detectDelimiter`). */
    fun csv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        fun endField() {
            row += field.toString().trim()
            field.clear()
        }

        fun endRow() {
            endField()
            rows += row
            row = mutableListOf()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' && (quoted || field.isBlank()) -> quoted = !quoted
                quoted -> field.append(c)
                c == delimiter -> endField()
                c == '\r' -> {
                    if (text.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    fun xlsx(bytes: ByteArray): List<List<String>> {
        val parts = unzip(bytes, setOf("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/sharedStrings.xml")) { it.startsWith("xl/worksheets/") }
        val sheet = firstSheetPath(parts)?.let(parts::get)
            ?: parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.minOrNull()?.let(parts::get)
            ?: throw SheetException("File Excel tidak berisi sheet.")
        val strings = parts["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        return cells(sheet, strings)
    }

    private fun text(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        return text.removePrefix(BOM)
    }

    private fun unzip(bytes: ByteArray, names: Set<String>, also: (String) -> Boolean): Map<String, ByteArray> {
        val parts = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    if (!entry.isDirectory && (entry.name in names || also(entry.name))) {
                        parts[entry.name] = zip.readBytes()
                    }
                }
            }
        } catch (_: Exception) {
            throw SheetException("File Excel tidak terbaca.")
        }
        return parts
    }

    /** The first sheet in the workbook's tab order, which isn't always `sheet1.xml`. */
    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        val workbook = parts["xl/workbook.xml"] ?: return null
        val rels = parts["xl/_rels/workbook.xml.rels"] ?: return null

        var relationId: String? = null
        parse(workbook) { parser ->
            if (relationId == null && parser.eventType == XmlPullParser.START_TAG && parser.tag == "sheet") {
                relationId = (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it).endsWith("id") && parser.getAttributeName(it) != "sheetId" }
                    ?.let(parser::getAttributeValue)
            }
        }

        var target: String? = null
        parse(rels) { parser ->
            if (parser.eventType == XmlPullParser.START_TAG && parser.tag == "Relationship" && parser.getAttributeValue(null, "Id") == relationId) {
                target = parser.getAttributeValue(null, "Target")
            }
        }
        return target?.let { if (it.startsWith("/")) it.removePrefix("/") else "xl/$it" }
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        // Phonetic guides (<rPh>) hold readings, not the text itself.
        var inPhonetic = false

        parse(xml) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.tag) {
                    "si" -> current.clear()
                    "rPh" -> inPhonetic = true
                    "t" -> inText = !inPhonetic
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.tag) {
                    "t" -> inText = false
                    "rPh" -> inPhonetic = false
                    "si" -> strings += current.toString()
                }
            }
        }
        return strings
    }

    private fun cells(xml: ByteArray, strings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableMapOf<Int, String>()
        var column = 0
        var type: String? = null
        val value = StringBuilder()
        var inValue = false

        parse(xml) { parser ->
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.tag) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        column = parser.getAttributeValue(null, "r")?.let(::columnIndex) ?: (row.keys.maxOrNull()?.plus(1) ?: 0)
                        type = parser.getAttributeValue(null, "t")
                        value.clear()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.tag) {
                    "v", "t" -> inValue = false
                    "c" -> row[column] = when (type) {
                        "s" -> value.toString().trim().toIntOrNull()?.let(strings::getOrNull).orEmpty()
                        "b" -> if (value.toString() == "1") "TRUE" else "FALSE"
                        "inlineStr", "str", "e" -> value.toString()
                        else -> number(value.toString())
                    }.trim()
                    "row" -> rows += List((row.keys.maxOrNull() ?: -1) + 1) { row[it].orEmpty() }
                }
            }
        }
        return rows
    }

    /** "B12" → 1. */
    private fun columnIndex(reference: String): Int =
        reference.takeWhile(Char::isLetter).uppercase().fold(0) { index, letter -> index * 26 + (letter - 'A' + 1) } - 1

    /** Numbers as typed: "84", not "84.0". */
    private fun number(raw: String): String {
        val value = raw.trim().toDoubleOrNull() ?: return raw
        return if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else raw.trim()
    }

    /** The tag without a namespace prefix (some writers use `<x:sheet>`). */
    private val XmlPullParser.tag: String? get() = name?.substringAfter(':')

    private fun parse(xml: ByteArray, onEvent: (XmlPullParser) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) onEvent(parser)
        } catch (_: Exception) {
            throw SheetException("File Excel tidak terbaca.")
        }
    }
}
