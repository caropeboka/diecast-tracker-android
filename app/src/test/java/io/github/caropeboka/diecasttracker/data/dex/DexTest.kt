package io.github.caropeboka.diecasttracker.data.dex

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.ui.edit.DiecastForm
import kotlinx.coroutines.flow.first
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

/** Ports of the web app's `DexTest`, with its real-world wiki fixtures. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DexTest {
    private val env = TestEnvironment()
    private val fake = FakeWiki()
    private val wiki = WikiCatalog(fake.client, "Test/1.0", pauseMs = 0, retryPauseMs = 0)
    private val matcher = DexMatcher(env.db.dex())
    private val dexSync = DexSync(env.db, wiki, matcher, env.root)
    private val dex = DexRepository(env.db, matcher)
    private val dao = env.db.dex()

    @After
    fun tearDown() = env.close()

    private fun sync(year: Int) = runBlocking { dexSync.sync(WikiSource.HotWheels, year) }

    private fun diecast(name: String, change: (DiecastEntity) -> DiecastEntity = { it }): DiecastEntity = runBlocking {
        val id = env.repository.save(change(DiecastEntity(name = name, brand = "Hot Wheels")))
        env.db.diecasts().get(id)!!
    }

    private fun rx7() = diecast("MAZDA RX-7") {
        // As the AI read it: the copyright year (2023) instead of the release year.
        it.copy(series = "HW J-Imports", collectionNumber = "61/250 · 2/10", year = 2023)
    }

    private fun item(catalogId: Long, number: String, variant: Int = 1): CatalogItemEntity =
        runBlocking { dao.items(catalogId).single { it.number == number && it.variant == variant } }

    private fun fresh(diecast: DiecastEntity) = runBlocking { env.db.diecasts().get(diecast.id)!! }

    @Test
    fun `hot wheels list is read with variants, flags and photos`() = runBlocking {
        val items = wiki.fetch(WikiSource.HotWheels, 2026).second.associateBy { "${it.number}|${it.variant}" }

        // The unnumbered promo is not part of the numbered set.
        assertEquals(6, items.size)

        with(items.getValue("061|1")) {
            assertEquals("Mazda RX-7", name)
            assertEquals("HW J-Imports", series)
            assertEquals("2/10", seriesNumber)
            assertEquals("JJJ29", toyCode)
            assertEquals("1978MazdaRX-7.jpg", imageFile)
        }
        with(items.getValue("061|2")) {
            assertEquals("Mazda RX-7", name)
            assertEquals("2nd Color - Zamac", variantLabel)
            assertEquals("Walmart", exclusive)
        }
        assertTrue(items.getValue("009|1").isTh)
        assertNull(items.getValue("009|1").imageFile)
        // Broken <font color=#00CC00"> markup must not swallow the Super Treasure Hunt label.
        assertTrue(items.getValue("013|1").isSth)
        assertFalse(items.getValue("013|1").isTh)
        assertEquals("Truckin' Along", items.getValue("013|1").series)
        // HTML comments are not part of the name.
        assertEquals("Bone Shaker", items.getValue("250|1").name)
        // Non-Latin file names stay intact.
        assertEquals("1280х720.jpg", items.getValue("223|2").imageFile)
    }

    @Test
    fun `matchbox list is read`() = runBlocking {
        val items = wiki.fetch(WikiSource.Matchbox, 2015).second

        assertEquals(2, items.size)
        with(items[0]) {
            assertEquals("MB1", number)
            assertEquals("Porsche Cayman", name)
            assertTrue(isNew)
            assertEquals("CFV75", toyCode)
            assertEquals("MBX Adventure City", series)
            assertEquals("Porsche Cayman 2015 MB1.jpg", imageFile)
        }
        assertEquals("CFV80", items[1].toyCode)
    }

    @Test
    fun `sync builds the dex, links the collection and keeps links on re-sync`() {
        val rx7 = rx7()

        val result = sync(2026)

        assertEquals("Hot Wheels Mainline 2026", result.name)
        assertEquals(6, result.items)
        assertEquals(1, result.linked)
        val main = item(result.catalogId, "061")
        assertEquals(main.id, fresh(rx7).catalogItemId)
        assertEquals(CatalogMatch.Auto, fresh(rx7).catalogMatch)
        assertNotNull(main.imageUrl)

        // Re-sync: same rows, same ids, link kept.
        val again = sync(2026)
        assertEquals(0, again.added)
        assertEquals(0, again.removed)
        assertEquals(main.id, fresh(rx7).catalogItemId)
        assertEquals(1, runBlocking { dao.observeCatalogs().first() }.size)
    }

    @Test
    fun `matcher finds the release year across years`() = runBlocking {
        sync(2023)
        sync(2026)

        val rx7 = rx7()
        val suggestions = matcher.suggest(rx7)

        assertEquals(2026, suggestions[0].row.catalogYear)
        assertEquals("061", suggestions[0].row.item.number)
        assertEquals(2026, suggestions[0].yearFix)
        // 2023's #061 is the Unimog: same number, nothing else in common.
        assertEquals("Mercedes-Benz Unimog 1300", suggestions[1].row.item.name)
        assertTrue(suggestions[0].score - suggestions[1].score >= DexMatcher.AUTO_MARGIN)
        // Linked when saved.
        assertEquals(suggestions[0].row.item.id, fresh(rx7).catalogItemId)

        // A name alone is a suggestion, never an automatic link.
        val loose = diecast("Mazda RX-7")
        assertNull(fresh(loose).catalogItemId)
        assertEquals("061", matcher.suggest(loose)[0].row.item.number)
    }

    @Test
    fun `card numbers are parsed`() {
        assertEquals(CardNumbers(main = 61, series = "2/10"), DexMatcher.numbers("61/250 · 2/10"))
        assertEquals(CardNumbers(series = "2/5"), DexMatcher.numbers("02/05"))
        assertEquals(CardNumbers(main = 12), DexMatcher.numbers("MB12"))
        assertEquals(CardNumbers(code = "MGT00512"), DexMatcher.numbers("MGT00512"))
        assertEquals(listOf("mazda", "rx", "7"), DexMatcher.tokens("'95 Mazda RX-7"))
    }

    @Test
    fun `progress counts each number once`() = runBlocking {
        val catalog = sync(2026).catalogId
        suspend fun linked(item: CatalogItemEntity, status: DiecastStatus) {
            val id = env.repository.save(DiecastEntity(name = item.name, status = status))
            dao.setLink(id, item.id, CatalogMatch.Confirmed.key)
        }

        // Both colours of #061 owned: still one entry.
        linked(item(catalog, "061"), DiecastStatus.Owned)
        linked(item(catalog, "061", 2), DiecastStatus.Owned)
        linked(item(catalog, "009"), DiecastStatus.Spotted)
        linked(item(catalog, "013"), DiecastStatus.Wishlist)
        linked(item(catalog, "250"), DiecastStatus.Sold)

        val summary = dex.observeCatalogs().first().single()
        assertEquals(DexCounts(total = 5, seen = 3, caught = 2, wanted = 1), summary.counts)

        val entries = dex.observeEntries(catalog).first()
        assertEquals(5, entries.size)
        with(entries[2]) {
            assertEquals("061", number)
            assertEquals(2, variants.size)
            assertEquals(2, diecasts.size)
            assertTrue(state.caught)
        }
        assertTrue(entries[0].state.seen)
        assertFalse(entries[0].state.caught)
        assertTrue(entries[1].state.wanted)
        assertEquals(DexCounts(5, 3, 2, 1), DexProgress.counts(entries))
        assertEquals("Nightspeed", DexProgress.series(entries)[0].series)
    }

    @Test
    fun `thumbnails are downloaded once in batches`() = runBlocking {
        val catalog = sync(2026).catalogId

        assertEquals(0, dexSync.downloadImages())

        val main = item(catalog, "061")
        assertTrue(File(env.root, main.imagePath!!).exists())
        assertTrue(main.imagePath!!.startsWith("dex/$catalog/"))
        // "Image Not Available" has nothing to download.
        assertNull(item(catalog, "009").imagePath)

        val before = fake.imageDownloads
        assertEquals(0, dexSync.downloadImages())
        assertEquals(before, fake.imageDownloads)
    }

    @Test
    fun `a busy image CDN is asked again later, a missing photo is not`() = runBlocking {
        val catalog = sync(2026).catalogId

        // Rate-limited: connection trouble, the photos stay queued.
        fake.imageStatus = 429
        assertNull(dexSync.downloadImages())
        assertNotNull(item(catalog, "061").imageUrl)

        // Gone: dropped from the queue until the next sync looks it up again.
        fake.imageStatus = 404
        assertEquals(0, dexSync.downloadImages())
        assertNull(item(catalog, "061").imageUrl)
        assertNull(item(catalog, "061").imagePath)
    }

    @Test
    fun `links can be confirmed, changed and dropped`() = runBlocking {
        val catalog = sync(2026).catalogId
        val rx7 = rx7()
        val zamac = item(catalog, "061", 2)

        val confirmed = dex.link(rx7.id, LinkAction.Confirm, fixYear = true)!!
        assertEquals(CatalogMatch.Confirmed, confirmed.catalogMatch)
        assertEquals(2026, confirmed.year)
        assertEquals(item(catalog, "061").id, confirmed.catalogItemId)

        val changed = dex.link(rx7.id, LinkAction.Link, zamac.id)!!
        assertEquals("2nd Color - Zamac", dex.linkState(changed).linked!!.row.item.variantLabel)

        val ignored = dex.link(rx7.id, LinkAction.Ignore)!!
        assertNull(ignored.catalogItemId)
        assertEquals(CatalogMatch.Ignored, ignored.catalogMatch)
        assertTrue(dex.linkState(ignored).suggestions.isEmpty())

        // A rejected match stays rejected after the next sync.
        sync(2026)
        assertNull(fresh(rx7).catalogItemId)
    }

    @Test
    fun `editing a diecast keeps the link it was given`() = runBlocking {
        val catalog = sync(2026).catalogId
        val rx7 = rx7()
        dex.link(rx7.id, LinkAction.Link, item(catalog, "061", 2).id)

        // The form still holds the diecast as it was loaded (auto-linked to the main colour).
        env.repository.save(rx7.copy(notes = "Kartu mulus"))

        assertEquals(item(catalog, "061", 2).id, fresh(rx7).catalogItemId)
        assertEquals(CatalogMatch.Confirmed, fresh(rx7).catalogMatch)
    }

    @Test
    fun `adding from the dex prefills the form and links it`() = runBlocking {
        val catalog = sync(2026).catalogId
        val zamac = dex.item(item(catalog, "061", 2).id)!!

        val prefill = dex.prefill(zamac)
        assertEquals("Mazda RX-7", prefill.name)
        assertEquals("Hot Wheels", prefill.brand)
        assertEquals("61/250 · 2/10", prefill.collectionNumber)
        assertEquals(2026, prefill.year)
        assertEquals(Rarity.Regular, prefill.rarity)

        val form = DiecastForm.new(DiecastStatus.Wishlist, LocalDate.of(2026, 10, 20)).withDex(prefill, zamac.item.id)
        assertEquals("HW J-Imports", form.series)
        assertEquals("2026", form.year)
        assertEquals("1:64", form.scale)

        val id = env.repository.save(form.toEntity(null))
        assertEquals(zamac.item.id, env.db.diecasts().get(id)!!.catalogItemId)
        assertEquals(CatalogMatch.Confirmed, env.db.diecasts().get(id)!!.catalogMatch)
    }

    @Test
    fun `a scanned card gets the dex entry with its release year`() = runBlocking {
        sync(2026)

        val ref = dex.suggestFor(
            DiecastEntity(name = "MAZDA RX-7", brand = "Hot Wheels", series = "HW J-Imports", collectionNumber = "61/250 · 2/10", year = 2023),
        )!!

        assertEquals("061", ref.row.item.number)
        assertEquals(2026, ref.row.catalogYear)
        assertEquals(2026, ref.yearFix)
    }

    @Test
    fun `a dropped connection is retried, then reported`() = runBlocking {
        fake.offline = true

        try {
            dexSync.sync(WikiSource.HotWheels, 2026)
            fail("Expected a DexSyncException")
        } catch (e: DexSyncException) {
            assertEquals("Tidak bisa menghubungi wiki. Cek koneksi internet lalu coba lagi.", e.message)
        }
        assertEquals(3, fake.apiCalls)
    }

    @Test
    fun `a year missing from the wiki is reported`() = runBlocking {
        try {
            dexSync.sync(WikiSource.HotWheels, 2016)
            fail("Expected a DexSyncException")
        } catch (e: DexSyncException) {
            assertEquals("Daftar 2016 belum ada di wiki.", e.message)
        }
    }

    @Test
    fun `deleting a dex unlinks the collection and removes photos`() = runBlocking {
        val catalog = sync(2026).catalogId
        val rx7 = rx7()
        dexSync.downloadImages()
        assertTrue(File(env.root, "dex/$catalog").list().orEmpty().isNotEmpty())

        dexSync.delete(catalog)

        assertNull(fresh(rx7).catalogItemId)
        assertNull(fresh(rx7).catalogMatch)
        assertTrue(dao.items(catalog).isEmpty())
        assertFalse(File(env.root, "dex/$catalog").exists())
    }

    @Test
    fun `the review list holds what is auto-linked or not linked, auto first`() = runBlocking {
        sync(2026)
        val rx7 = rx7()
        val unknown = diecast("Some Custom Car")
        diecast("Tomica Thing") { it.copy(brand = "Tomica") }

        val review = dex.observeReview().first().map { it.diecast.id }

        assertEquals(listOf(rx7.id, unknown.id), review)
        assertEquals(2, dex.observeReviewCount().first())
    }
}
