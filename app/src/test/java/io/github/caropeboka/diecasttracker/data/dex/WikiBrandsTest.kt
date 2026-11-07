package io.github.caropeboka.diecasttracker.data.dex

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The wikis besides Hot Wheels and Matchbox (Mini GT with Kaido House, Tomica's lineup), trimmed from the real pages. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WikiBrandsTest {
    private val env = TestEnvironment()
    private val fake = FakeWiki(
        mapOf("2021 Models" to MINI_GT_2021, "List of Main Lineup Products" to TOMICA, "Tomica Premium (toyline)" to TOMICA_PREMIUM),
    )
    private val wiki = WikiCatalog(fake.client, "Test/1.0", env.texts, pauseMs = 0, retryPauseMs = 0)
    private val matcher = DexMatcher(env.db.dex())
    private val dexSync = DexSync(env.db, wiki, matcher, env.texts, env.root)

    @After
    fun tearDown() = env.close()

    @Test
    fun `Mini GT - codes, makes as series, exclusives from the photo caption, Kaido House after`() = runBlocking {
        val items = wiki.fetch(WikiSource.MiniGt, 2021).second.sortedBy { it.sort }

        assertEquals(listOf("MGT00193", "MGT00307", "KHMG008"), items.map { it.number })
        assertEquals("HKS GR Supra D1 GP 2020 #77 H. Matsuyama FAT FIVE RACING", items[1].name)
        assertEquals(listOf("Lamborghini", "Toyota", "Kaido House"), items.map { it.series })
        assertEquals(listOf(null, "Japan", "Mijo / USA"), items.map { it.exclusive })
        assertEquals("KHMG008.jpg", items[2].imageFile)
        assertEquals(listOf(1, 1, 1), items.map { it.variant })
    }

    @Test
    fun `Mini GT's years stop where the wiki stops, Tomica has one list`() {
        assertEquals((2022 downTo 2018).toList(), WikiSource.MiniGt.years(2026))
        assertEquals((2026 downTo 1989).toList(), WikiSource.HotWheels.years(2026))
        assertEquals(emptyList<Int>(), WikiSource.Tomica.years(2026))
        assertTrue(WikiSource.MiniGt.covers(" kaido house "))
        assertEquals("List of Main Lineup Products", WikiSource.Tomica.page(null))
    }

    @Test
    fun `Tomica - numbered like the boxes, the section as series, one catalog without a year`() = runBlocking {
        val items = wiki.fetch(WikiSource.Tomica, null).second

        assertEquals(listOf("No.1", "No.2", "No.121"), items.map { it.number })
        assertEquals(listOf(1, 2, 121), items.map { it.sort })
        assertEquals("Nissan Skyline GT-R (BNR34) Patrol Car", items[0].name)
        assertEquals(listOf("Regular Tomica Series", "Regular Tomica Series", "Long Type Tomica"), items.map { it.series })
        assertNull(items[0].imageFile)

        val first = dexSync.sync(WikiSource.Tomica, null)
        val again = dexSync.sync(WikiSource.Tomica, null)
        val catalog = env.db.dex().catalog(first.catalogId)!!
        assertEquals(first.catalogId, again.catalogId)
        assertEquals("Tomica Regular", catalog.name)
        assertNull(catalog.year)
        assertEquals(0, again.added)
    }

    @Test
    fun `a Kaido House diecast finds its entry on Mini GT's list by the code on the box`() = runBlocking {
        dexSync.sync(WikiSource.MiniGt, 2021)
        val id = env.repository.save(DiecastEntity(name = "Datsun 510 Pro Street KDO510", brand = "Kaido House", collectionNumber = "KHMG008"))

        val diecast = env.db.diecasts().get(id)!!
        val entry = env.db.dex().item(diecast.catalogItemId!!)!!
        assertEquals("KHMG008", entry.number)
        assertEquals(CatalogMatch.Auto, diecast.catalogMatch)
        assertEquals("1:64", matcher.prefill(matcher.suggest(diecast).first().row).scale)
    }

    @Test
    fun `Tomica Premium - one page, a list per release year, Unlimited numbered on its own`() = runBlocking {
        val items = wiki.fetch(WikiSource.TomicaPremium, 2024).second.sortedWith(compareBy({ it.sort }, { it.variant }))

        assertEquals(listOf("No.12", "No.12", "No.15", "Unlimited No.1"), items.map { it.number })
        assertEquals(listOf("Morris Mini", "Morris Mini", "Nissan Fairlady Z (Z31)", "Initial AE86 Trueno (Takumi Fujiwara)"), items.map { it.name })
        assertEquals(listOf(null, "Release Commemoration Version", null, null), items.map { it.variantLabel })
        assertEquals(listOf("Tomica Premium", "Tomica Premium", "Tomica Premium", "Tomica Premium Unlimited"), items.map { it.series })
        // Two-digit years count too; unnumbered releases and race numbers ("Racing No.99") don't.
        assertEquals(listOf("No.9"), wiki.fetch(WikiSource.TomicaPremium, 2023).second.map { it.number })
        assertEquals("Tomica Premium", WikiSource.TomicaPremium.shortName)
        assertEquals("Mini GT", WikiSource.MiniGt.shortName)

        dexSync.sync(WikiSource.TomicaPremium, 2024)
        val id = env.repository.save(DiecastEntity(name = "Nissan Fairlady Z (Z31)", brand = "Tomica", series = "Tomica Premium", collectionNumber = "15", year = 2024))
        val diecast = env.db.diecasts().get(id)!!
        assertEquals("No.15", env.db.dex().item(diecast.catalogItemId!!)!!.number)
        assertEquals("Tomica Premium 2024", env.db.dex().catalog(env.db.dex().item(diecast.catalogItemId!!)!!.catalogId)!!.name)
    }

    private companion object {
        val MINI_GT_2021 = """
            Some photos have regional information in the caption.
            {| class="wikitable sortable"
            |+
            !Code
            !Name
            !LHD/RHD
            !Brand
            !Photo
            |-
            | align="center" |KHMG008
            | align="center" |[[Datsun 510 Pro Street KDO510]]
            | align="center" |LHD
            | align="center" |[[Nissan]]
            | align="center" |[[File:KHMG008.jpg|thumb|[[KAIDO★HOUSE|x KAIDO★HOUSE]] <br/> Mijo / USA Exclusive|center]]
            |-
            | align="center" |MGT00193
            | align="center" |[[Lamborghini Huracán EVO Bologna Airport 2020 Follow-Me Car]]
            | align="center" |LHD
            | align="center" |[[Lamborghini]]
            | align="center" |[[File:MGT00193.jpg|thumb|center]]
            |-
            | align="center" |MGT00307
            | align="center" |[[HKS GR Supra D1 GP 2020 77 H. Matsuyama FAT FIVE RACING|HKS GR Supra D1 GP 2020 #77 H. Matsuyama FAT FIVE RACING]]
            | align="center" |RHD
            | align="center" |[[Toyota]]
            | align="center" |[[File:MGT00307.jpg|thumb|Japan Exclusive|center|link=Special:FilePath/MGT00307.jpg]]
            |-
            | align="center" |TBA
            | align="center" |[[Cancelled model]]
            | align="center" |LHD
            | align="center" |[[Ford]]
            | align="center" |
            |}
        """.trimIndent()

        val TOMICA = """
            = '''<big>60% complete</big>'''=

            == Regular Tomica Series (No. 1 - No. 120)==
            {| class="wikitable" style="width:100%"
            |- style="color:white"
            ! style="background-color:#0000ff" width="80%" |Product No. and Names
            ! style="background-color:#0000ff" width="20%" |Release Date
            |- style="text-align:center"
            |[[No. 1 Nissan Skyline GT-R (BNR34) Patrol Car]]
            |11/20/2021
            |- style="text-align:center"
            |[[No. 2 Subaru WRX S4 Unmarked Police Car]]
            |3/18/2017
            |}
            ==[[Long Type Tomica (toyline)#Modern Series|Long Type Tomica]] (No. 121 - No. 150)==
            {| class="wikitable" style="width:100%"
            |- style="color:white"
            ! style="background-color:#0000ff" width="80%" |Product No. and Names
            ! style="background-color:#0000ff" width="20%" |Release Date
            |- style="text-align:center"
            |[[No. 121 Ichihara City Fire Bureau Scrum Force]]
            |12/17/2022
            |}
            ==[[Dream Tomica (toyline)|Dream Tomica]] (No. 151 - No. 182)==

            ==Trivia==
        """.trimIndent()

        val TOMICA_PREMIUM = """
            '''Tomica Premium''' (トミカプレミアム) is a toyline related to Tomica.
            ==Main Line==
            {|class="wikitable" align="center" style="width:700px; text-align:center; margin:0 auto"
            |- style="color:white"
            !style="background-color:#0000ff" width=50%|Product Name
            !style="background-color:#0000ff" width=20%|Release Date
            |-
            |[[Premium No. 09 Nissan Fairlady Z|No. 09 Nissan Fairlady Z]]
            |1/19/2019
            |-
            |[[Premium Volkswagen Type II Pickup|Volkswagen Type II Pickup]]
            |7/2024
            |-
            |[[Premium No. 12 Morris Mini|No. 12 Morris Mini]]
            |1/20/2024
            |-
            |[[Premium No. 12 Morris Mini (Release Commemoration Version)|No. 12 Morris Mini (Release Commemoration Version)]]
            |1/20/2024
            |-
            |[[Premium No. 15 Nissan Fairlady Z (Z31)|No. 15 Nissan Fairlady Z (Z31)]]
            |11/2024
            |-
            |[[Premium No. 09 Toyota Supra|No. 09 Toyota Supra]]
            |3/18/23
            |}
            ==Tomica Premium Unlimited==
            {|class="wikitable" align="center" style="width:700px; text-align:center; margin:0 auto"
            |+
            |- style="color:white"
            ! style="background-color:#0000ff" width="50%" |Product Name
            ! style="background-color:#0000ff" width="20%" |Release Date
            |-
            |[[Tomica Premium Unlimited No. 01 Initial AE86 Trueno (Takumi Fujiwara)|No. 01 Initial AE86 Trueno (Takumi Fujiwara)]]
            |4/2024
            |}
            ==Tomica Premium Racing==
            {|class="wikitable" align="center" style="width:700px; text-align:center; margin:0 auto"
            |- style="color:white"
            !style="background-color:#0000ff" width=50%|Product Name
            !style="background-color:#0000ff" width=20%|Release Date
            |-
            |[[Tomica Premium Racing No.99 NSX-GT]]
            |4/2024
            |}
            ==Trivia==
        """.trimIndent()
    }
}
