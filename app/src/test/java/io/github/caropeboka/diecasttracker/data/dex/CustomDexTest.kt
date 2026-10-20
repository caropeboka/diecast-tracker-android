package io.github.caropeboka.diecasttracker.data.dex

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ai.FakeGemini
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.ai.MemoryModelCache
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Ports of the web app's `CustomDexTest`, plus editing entries (which the web can't do). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomDexTest {
    private val env = TestEnvironment()
    private val matcher = DexMatcher(env.db.dex())
    private val custom = DexCustom(env.db, matcher, env.photos, env.root, today = { LocalDate.of(2026, 10, 20) })
    private val dao = env.db.dex()

    @After
    fun tearDown() = env.close()

    private suspend fun dex(brand: String = "Mini GT") = custom.create(brand, "$brand list", "")

    private suspend fun diecast(id: Long) = env.db.diecasts().get(id)!!

    private fun dexFiles(catalogId: Long) = File(env.root, "dex/$catalogId").list().orEmpty().toList()

    /** The message of the [DexCustomException] [block] throws. */
    private suspend fun error(block: suspend () -> Unit): String? {
        try {
            block()
        } catch (e: DexCustomException) {
            return e.message
        }
        fail("Expected a DexCustomException")
        return null
    }

    @Test
    fun `an own dex is filled by hand`() = runBlocking {
        val id = custom.create("Mini GT", "Mini GT 2026", "2026")
        assertEquals("custom", dao.catalog(id)!!.source)
        assertEquals(2026, dao.catalog(id)!!.year)

        assertNotNull(custom.addOne(id, DexRow("mgt00512", "Nissan Skyline GT-R (R34)", variantLabel = "Bayside Blue"), env.pendingPhoto()))
        // Same number, another color: a variant of that entry.
        custom.addOne(id, DexRow("MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Millennium Jade"), null)
        // No number: numbered in order.
        custom.addOne(id, DexRow(name = "Toyota Supra"), null)
        // Exact duplicate: skipped, and its photo isn't kept.
        val photo = env.pendingPhoto()
        assertNull(custom.addOne(id, DexRow("#MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Bayside Blue"), photo))

        val items = dao.items(id)
        assertEquals(3, items.size)
        assertEquals(Triple("MGT00512", 1, "Bayside Blue"), Triple(items[0].number, items[0].variant, items[0].variantLabel))
        assertEquals("MGT00512" to 2, items[1].number to items[1].variant)
        assertEquals("2", items[2].number)
        assertTrue(items[0].imagePath!!.startsWith("dex/$id/"))
        assertTrue(File(env.root, items[0].imagePath!!).exists())
        assertEquals(1, dexFiles(id).size)
        assertFalse(photo in env.pendingFiles)

        val entries = DexProgress.entries(items, emptyList())
        assertEquals(2, entries.size)
        assertEquals(2, entries[0].variants.size)

        // Names are unique among own dexes; a dex can be renamed.
        assertEquals(setOf(CatalogField.Name), custom.checkCatalog("Mini GT", "mini gt 2026", "").keys)
        assertEquals(setOf(CatalogField.Brand, CatalogField.Year), custom.checkCatalog(" ", "Lain", "1900").keys)
        custom.update(id, "Mini GT", "Mini GT 2026 lengkap", "2026")
        assertEquals("Mini GT 2026 lengkap", dao.catalog(id)!!.name)
    }

    @Test
    fun `spreadsheet import reads an Indonesian CSV`() = runBlocking {
        val id = dex()
        val csv = "Nomor;Nama;Seri;Varian\n#T64-084;Porsche 911 GT3 RS;Road64;Silver\n;Toyota GR86;;\n;;;\n"

        val result = custom.fromSpreadsheet(id, csv.toByteArray())

        assertEquals(2 to 0, result.added to result.skipped)
        val items = dao.items(id)
        assertEquals(listOf("T64-084", "Porsche 911 GT3 RS", "Road64", "Silver"), items[0].let { listOf(it.number, it.name, it.series, it.variantLabel) })
        assertEquals("Toyota GR86", items[1].name)
        assertEquals(2, items.size)

        assertEquals("Kolom \"Nama\" tidak ditemukan di baris pertama.", error { custom.fromSpreadsheet(id, "Foo,Bar\n1,2\n".toByteArray()) })
    }

    @Test
    fun `spreadsheet import reads Excel files`() = runBlocking {
        // Made by OpenSpout (tools/make-dex-xlsx-fixture.php): with inline strings, and with a shared string table.
        for (kind in listOf("inline", "shared")) {
            val id = custom.create("Mini GT", "Excel $kind", "")
            val bytes = javaClass.getResourceAsStream("/dex-list-$kind.xlsx")!!.readBytes()

            val result = custom.fromSpreadsheet(id, bytes)

            assertEquals(kind, 4 to 0, result.added to result.skipped)
            val items = dao.items(id)
            assertEquals(kind, listOf("MGT00512" to 1, "MGT00512" to 2, "84" to 1, "85" to 1), items.map { it.number to it.variant })
            assertEquals(kind, "Mini GT 2026", items[0].series)
            assertEquals(kind, "Millennium Jade", items[1].variantLabel)
            assertEquals(kind, "3/5", items[2].seriesNumber)
            assertNull(kind, items[2].series)
            assertEquals(kind, "Honda NSX-R — Type R", items[3].name)
        }
    }

    @Test
    fun `checklist screenshots are read by the AI and matched to the collection`() = runBlocking {
        val fake = FakeGemini().on("models", 200, FakeGemini.models()).otherwise(
            200,
            FakeGemini.answer(
                """
                {"items": [
                  {"number": "MGT00512", "name": "Nissan Skyline GT-R (R34) Nismo", "series": null, "series_number": null, "variant": "LHD"},
                  {"number": "MGT00513", "name": "Honda NSX-R", "series": null, "series_number": null, "variant": null},
                  {"number": null, "name": "", "series": null, "series_number": null, "variant": null}
                ]}
                """.trimIndent(),
            ),
        )
        val reader = ChecklistReader(
            MutableStateFlow(GeminiSettings(key = "test-key")),
            GeminiClient(fake.client, MemoryModelCache(), baseUrl = "https://gemini.test/v1beta"),
        )
        val id = dex()
        val mine = env.repository.save(DiecastEntity(name = "Nissan Skyline GT-R R34", brand = "Mini GT", collectionNumber = "MGT00512"))

        val rows = reader.read(dao.catalog(id)!!, listOf(TestEnvironment.fixtureJpeg))

        // The nameless row is left out; the dex is named in the question.
        assertEquals(listOf("MGT00512", "MGT00513"), rows.map { it.number })
        assertEquals("LHD", rows[0].variantLabel)
        assertTrue("Mini GT — Mini GT list" in fake.bodies.last())

        // Reviewed and kept: added, and the collection is matched.
        assertEquals(listOf(false, false), custom.known(id, rows))
        assertEquals(2, custom.add(id, rows).added)
        assertEquals(listOf(true, true), custom.known(id, rows))
        assertEquals(dao.items(id).first().id, diecast(mine).catalogItemId)
        assertEquals(CatalogMatch.Auto, diecast(mine).catalogMatch)
    }

    @Test
    fun `the collection can seed its own dex`() = runBlocking {
        val id = dex("Roll Speed")
        val police = env.repository.save(DiecastEntity(name = "Polisi Lalu Lintas", brand = "Roll Speed", color = "Putih"), listOf(env.pendingPhoto()))
        env.repository.save(DiecastEntity(name = "Ambulans", brand = "Roll Speed", collectionNumber = "No. 7"))
        env.repository.save(DiecastEntity(name = "Other brand", brand = "Majorette"))

        assertEquals(2 to 2, custom.fromCollection(id).let { it.added to it.linked })

        val items = dao.items(id).associateBy { it.name }
        assertEquals(2, items.size)
        assertEquals("7", items["Ambulans"]!!.number)
        assertEquals("Putih", items["Polisi Lalu Lintas"]!!.variantLabel)
        val photo = items["Polisi Lalu Lintas"]!!.imagePath!!
        assertTrue(File(env.root, photo).exists())
        assertEquals(items["Polisi Lalu Lintas"]!!.id, diecast(police).catalogItemId)
        assertEquals(CatalogMatch.Confirmed, diecast(police).catalogMatch)
        // The diecast keeps its own photo; the entry has a copy.
        assertEquals(1, env.savedFiles.size)

        // Running it again adds nothing.
        assertEquals(0, custom.fromCollection(id).added)
        assertEquals(2, dao.items(id).size)
        assertEquals(1, dexFiles(id).size)

        // Removing an entry unlinks the diecast, and the entry's photo goes.
        custom.delete(items["Polisi Lalu Lintas"]!!.id)
        assertNull(diecast(police).catalogItemId)
        assertFalse(File(env.root, photo).exists())
        assertEquals(1, env.savedFiles.size)
    }

    @Test
    fun `the collection joins entries already on the list instead of doubling them`() = runBlocking {
        val id = dex("Inno64")
        custom.add(id, listOf(DexRow("IN64-FD3S", "Mazda RX-7 FD3S", variantLabel = "Rotary Red")))
        val plain = env.repository.save(DiecastEntity(name = "mazda rx-7 fd3s", brand = "INNO64"))
        val blue = env.repository.save(DiecastEntity(name = "Mazda RX-7 FD3S", brand = "Inno64", color = "Midnight Blue"))

        assertEquals(1 to 2, custom.fromCollection(id).let { it.added to it.linked })

        val items = dao.items(id)
        // No colour to tell: the entry on the list. Another colour: a variant of it.
        assertEquals(listOf("IN64-FD3S" to "Rotary Red", "IN64-FD3S" to "Midnight Blue"), items.map { it.number to it.variantLabel })
        assertEquals(items[0].id, diecast(plain).catalogItemId)
        assertEquals(items[1].id, diecast(blue).catalogItemId)
        assertEquals(1, DexProgress.entries(items, emptyList()).size)
    }

    @Test
    fun `an entry can be corrected and keeps its links`() = runBlocking {
        val id = dex()
        val r34 = custom.addOne(id, DexRow("MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Bayside Blue"), null)!!
        val typo = custom.addOne(id, DexRow("MGT0513", "Skyline GTR R34", variantLabel = "Midnight Purple"), env.pendingPhoto())!!
        val mine = env.repository.save(DiecastEntity(name = "Skyline", brand = "Mini GT"))
        dao.setLink(mine, typo.id, CatalogMatch.Confirmed.key)
        val oldPhoto = typo.imagePath!!

        // It was another colour of MGT00512: it becomes a variant of that entry, with a new photo.
        custom.edit(typo.id, DexRow("MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Midnight Purple"), PhotoEdit.Replace(env.pendingPhoto()))

        val fixed = dao.item(typo.id)!!
        assertEquals(Triple("MGT00512", r34.sort, 2), Triple(fixed.number, fixed.sort, fixed.variant))
        assertEquals("Nissan Skyline GT-R (R34)", fixed.name)
        assertEquals(typo.id, diecast(mine).catalogItemId)
        assertFalse(File(env.root, oldPhoto).exists())
        assertTrue(File(env.root, fixed.imagePath!!).exists())

        // Into a duplicate of another entry: refused, nothing changes.
        val duplicate = DexRow("MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Bayside Blue")
        assertEquals("Entri itu sudah ada di dex ini.", error { custom.edit(typo.id, duplicate, PhotoEdit.Remove) })
        assertEquals(fixed, dao.item(typo.id))

        custom.edit(typo.id, DexRow("MGT00512", "Nissan Skyline GT-R (R34)", variantLabel = "Midnight Purple"), PhotoEdit.Remove)
        assertNull(dao.item(typo.id)!!.imagePath)
        assertTrue(dexFiles(id).isEmpty())
        assertEquals(mapOf(RowField.Name to "Nama model wajib diisi."), DexCustom.checkRow(DexRow(number = "1", name = " ")))
    }

    @Test
    fun `wiki lists cannot be edited by hand`() = runBlocking {
        val wiki = dao.insertCatalog(CatalogEntity(brand = "Hot Wheels", name = "Hot Wheels Mainline 2026", year = 2026, source = "hotwheels_wiki"))
        val item = dao.insertItem(CatalogItemEntity(catalogId = wiki, number = "001", sort = 1, name = "X"))
        val refused = "Daftar dari wiki diubah lewat sinkron, bukan manual."

        assertEquals(refused, error { custom.addOne(wiki, DexRow(name = "Y"), null) })
        assertEquals(refused, error { custom.edit(item, DexRow(name = "Z"), PhotoEdit.Keep) })
        assertEquals(refused, error { custom.delete(item) })
        assertEquals(refused, error { custom.fromCollection(wiki) })
        assertEquals(listOf("X"), dao.items(wiki).map { it.name })
    }
}
