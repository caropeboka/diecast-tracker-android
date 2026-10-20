package io.github.caropeboka.diecasttracker.data.dex

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class SheetReaderTest {
    @Test
    fun `csv fields can be quoted, span lines and hold the delimiter`() {
        val csv = "${Char(0xFEFF)}Nama,Seri\r\n\"Skyline \"\"R34\"\"\",\"Road64, Exotics\"\r\n\"Two\nlines\",x\n"

        assertEquals(
            listOf(listOf("Nama", "Seri"), listOf("Skyline \"R34\"", "Road64, Exotics"), listOf("Two\nlines", "x")),
            SheetReader.read(csv.toByteArray()),
        )
    }

    @Test
    fun `the csv delimiter is taken from the first line`() {
        assertEquals(listOf(listOf("Nomor", "Nama"), listOf("1", "A, B")), SheetReader.csv("Nomor;Nama\n1;A, B"))
        assertEquals(listOf(listOf("Nomor", "Nama"), listOf("1", "A; B")), SheetReader.csv("Nomor\tNama\n1\tA; B"))
    }

    @Test
    fun `xlsx is read from the first tab with shared, rich and inline strings`() {
        val xlsx = zip(
            "xl/workbook.xml" to """
                <x:workbook xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <x:sheets><x:sheet name="Daftar" sheetId="2" r:id="rId7"/><x:sheet name="Lain" sheetId="1" r:id="rId1"/></x:sheets>
                </x:workbook>
            """,
            "xl/_rels/workbook.xml.rels" to """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId7" Target="/xl/worksheets/sheet2.xml"/>
                </Relationships>
            """,
            "xl/sharedStrings.xml" to """
                <sst><si><t>Nama</t></si><si><r><t>Nissan </t></r><r><rPr><b/></rPr><t>Skyline</t></r><rPh><t>ニッサン</t></rPh></si></sst>
            """,
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c r="A1" t="inlineStr"><is><t>Salah sheet</t></is></c></row></sheetData></worksheet>""",
            "xl/worksheets/sheet2.xml" to """
                <x:worksheet xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheetData>
                  <x:row r="1"><x:c r="A1" t="s"><x:v>0</x:v></x:c><x:c r="C1" t="inlineStr"><x:is><x:t>Nomor</x:t></x:is></x:c></x:row>
                  <x:row r="3"><x:c r="A3" t="s"><x:v>1</x:v></x:c><x:c r="B3" t="b"><x:v>1</x:v></x:c><x:c r="C3"><x:v>84</x:v></x:c><x:c r="D3"><x:v>1.5</x:v></x:c><x:c r="E3"/></x:row>
                </x:sheetData></x:worksheet>
            """,
        )

        assertEquals(
            listOf(listOf("Nama", "", "Nomor"), listOf("Nissan Skyline", "TRUE", "84", "1.5", "")),
            SheetReader.read(xlsx),
        )
    }

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.trimIndent().toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
