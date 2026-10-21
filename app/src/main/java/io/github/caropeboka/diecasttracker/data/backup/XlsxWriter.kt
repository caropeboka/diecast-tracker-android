package io.github.caropeboka.diecasttracker.data.backup

import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a one-sheet .xlsx with the fewest parts Excel, Google Sheets and WPS open: strings inline (no shared-string
 * table), numbers as numbers, a bold shaded header row frozen at the top. Read back by
 * [io.github.caropeboka.diecasttracker.data.dex.SheetReader].
 */
object XlsxWriter {
    /**
     * [rows] hold [Number]s, text or nulls (empty cells). [out] is left open, so the sheet can sit inside another zip.
     */
    fun write(out: OutputStream, sheetName: String, header: List<String>, rows: List<List<Any?>>, widths: List<Double>) {
        val zip = ZipOutputStream(KeepOpen(out))
        fun part(name: String, xml: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }

        part("[Content_Types].xml", CONTENT_TYPES)
        part("_rels/.rels", ROOT_RELS)
        part("xl/workbook.xml", WORKBOOK.replace("{name}", escape(sheetName)))
        part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        part("xl/styles.xml", STYLES)

        zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8).buffered()
        writer.write(XML_HEAD)
        writer.write("<worksheet xmlns=\"$MAIN_NS\"><sheetViews><sheetView workbookViewId=\"0\">")
        writer.write("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>")
        widths.forEachIndexed { i, width -> writer.write("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$width\" customWidth=\"1\"/>") }
        writer.write("</cols><sheetData>")
        writer.row(1, header, style = HEADER_STYLE)
        rows.forEachIndexed { i, row -> writer.row(i + 2, row, style = null) }
        writer.write("</sheetData></worksheet>")
        writer.flush()
        zip.closeEntry()
        zip.close()
    }

    /** Closing it only flushes, so closing the sheet's zip ends its compressor but not [out]. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = flush()
    }

    private fun Appendable.row(number: Int, cells: List<Any?>, style: Int?) {
        append("<row r=\"$number\">")
        cells.forEachIndexed { i, value ->
            val ref = "${column(i)}$number"
            val s = style?.let { " s=\"$it\"" }.orEmpty()
            when (value) {
                null -> if (style != null) append("<c r=\"$ref\"$s/>")
                is Double, is Float -> append("<c r=\"$ref\"$s><v>${value.toString().toBigDecimal().toPlainString()}</v></c>")
                is Number -> append("<c r=\"$ref\"$s><v>$value</v></c>")
                else -> append("<c r=\"$ref\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(value.toString())}</t></is></c>")
            }
        }
        append("</row>")
    }

    /** 0 → "A", 25 → "Z", 26 → "AA". */
    internal fun column(index: Int): String {
        var n = index + 1
        val name = StringBuilder()
        while (n > 0) {
            name.append('A' + (n - 1) % 26)
            n = (n - 1) / 26
        }
        return name.reverse().toString()
    }

    /** Control characters other than tab and line breaks are not allowed in XML at all, so they are dropped. */
    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    private const val HEADER_STYLE = 1
    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    private const val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"$PACKAGE_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK = XML_HEAD +
        "<workbook xmlns=\"$MAIN_NS\" xmlns:r=\"$REL_NS\">" +
        "<sheets><sheet name=\"{name}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"$PACKAGE_REL_NS\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL_NS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL_NS/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** Style 0: plain; style 1: the header (bold on light grey, like the web's export). */
    private const val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"$MAIN_NS\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE5E7EB\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
