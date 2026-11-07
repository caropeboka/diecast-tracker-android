package io.github.caropeboka.diecasttracker.data.dex

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.testTexts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The older year lists (Hot Wheels from 1989, Matchbox from 2000) as the wikis write them, trimmed from the real pages. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class WikiOldYearsTest {
    private val wiki = WikiCatalog(
        FakeWiki(mapOf("List of 1990 Hot Wheels" to HW_1990, "List of 1998 Hot Wheels" to HW_1998, "List of 2000 Matchbox" to MB_2000)).client,
        "Test/1.0",
        testTexts(),
        pauseMs = 0,
        retryPauseMs = 0,
    )

    @Test
    fun `the early nineties name the casting and give a row per colour, and unnumbered rows stay out`() = runBlocking {
        val items = wiki.fetch(WikiSource.HotWheels, 1990).second

        assertEquals(listOf("084", "086", "086"), items.map { it.number })
        assertEquals(listOf("Probe Funny Car", "Propper Chopper", "Propper Chopper"), items.map { it.name })
        assertEquals(listOf(1, 1, 2), items.map { it.variant })
        assertEquals(listOf("Red", "White", "Black"), items.map { it.variantLabel })
        assertEquals("9112", items[1].toyCode)
        assertEquals("Propper Chopper Wht.JPG", items[1].imageFile)
    }

    @Test
    fun `the late nineties rows written as templates are read with the photo after each`() = runBlocking {
        val items = wiki.fetch(WikiSource.HotWheels, 1998).second

        assertEquals(listOf("637", "640", "635"), items.map { it.number })
        assertEquals(listOf("Escort Rally", "Slideout", "'65 Impala Lowrider"), items.map { it.name })
        assertEquals("1998 First Editions", items[0].series)
        assertEquals("01/40", items[0].seriesNumber)
        assertEquals("18143", items[0].toyCode)
        assertEquals(listOf("Escort Rally Wht.JPG", "SlideOut Prp.JPG", "'65 Chevy Impala Prpl.JPG"), items.map { it.imageFile })
    }

    @Test
    fun `Matchbox 2000 heads its columns Model and Card, with the region as the variant`() = runBlocking {
        val items = wiki.fetch(WikiSource.Matchbox, 2000).second

        assertEquals(listOf("MB1", "MB2", "MB2"), items.map { it.number })
        assertEquals(listOf(null, "USA", "ROW"), items.map { it.variantLabel })
        assertEquals(listOf(1, 1, 2), items.map { it.variant })
        assertEquals("Camaro SS Convertible", items[1].name)
        assertEquals("Open Road", items[1].series)
        assertEquals("96004", items[1].toyCode)
    }

    private companion object {
        const val HW_1990 = """==New Castings==
{| class="wikitable sortable" style="font-size: 85%; text-align: left;" width="100%"
! style="border-style: none none solid solid" |'''Col #'''
! style="border-style: none none solid solid" |'''Casting Name'''
! style="border-style: none none solid solid" |'''Series'''
! style="border-style: none none solid solid" |'''Color'''
! style="border-style: none none solid solid" |'''Toy #'''
! style="border-style: none none solid solid" |'''Photo'''
|-
|84
|[[Probe Funny Car]]
|
|Red
|9113
|[[Image:Probe_Funny_Car_Red.JPG|75px]]
|-
|86
|[[Propper Chopper]]
|
|White
|9112
|[[Image:Propper_Chopper_Wht.JPG|75px]]
|-
|86
|[[Propper Chopper]]
|
|Black
|9112
|[[Image:Propper_Chopper_Blk.JPG|75px]]
|}
==Re-releases==
{| class="wikitable sortable"
! '''Casting Name''' !! '''Color''' !! '''Toy #''' !! '''Photo'''
|-
|[[Baja Bug]]
|Red
|
|[[Image:Baja Bug Red BW.JPG|75px]]
|}"""

        const val HW_1998 = """{| class="wikitable" background="#C0C0C0" style="border:1px solid #C0C0C0;" border="1" cellspacing="1" width="60%"
| bgcolor="#C0C0C0"| '''Toy #'''
| bgcolor="#C0C0C0"| '''Col. #'''
| bgcolor="#C0C0C0"| '''Model Name'''
| bgcolor="#C0C0C0"| '''Series'''
| bgcolor="#C0C0C0"| '''Series #'''
| bgcolor="#C0C0C0"| '''Photo'''
{{List0000White|18143|637|[[Escort Rally]]|1998 First Editions|01/40|}}
|[[Image:Escort_Rally_Wht.JPG|75px]]{{List0000White|18293|640|[[Slideout]]|1998 First Editions|02/40|}}
|[[Image:SlideOut_Prp.JPG|75px]]{{List00Green|18207|635|[['65 Chevy Impala|'65 Impala Lowrider]]|1998 First Editions|08/40|}}
|[[Image:'65_Chevy_Impala_Prpl.JPG|75px]]
|}"""

        const val MB_2000 = """{|style="font-size: 100%;  text-align: center" class="wikitable sortable"  width="90%"
!style="border-style: none none  solid solid"|'''Model'''
!style="border-style: none none  solid solid"|'''Card'''
!style="border-style: none none  solid solid"|'''Model Name'''
!style="border-style: none none  solid solid"|'''Brand'''
!style="border-style: none none  solid solid"|'''Sub-series'''
!style="border-style: none none  solid solid"|'''Sub-series #'''
!style="border-style: none none  solid solid"|'''Photo'''
|-
|MB01 <br> (Core)
|96003
|[[Mercedes-Benz CLK Convertible]]
|Mercedes-Benz
|Open Road
|1/5
|[[File:Mercedes-Benz_CLK_Convertible_(2000_1-75).jpg|100px]]
|-
|MB02 <br> (USA)
|96004
|[[1998 Chevrolet Camaro SS  Convertible|Camaro SS Convertible]]<br>(New)
|Chevrolet
|Open Road
|2/5
|[[File:Camaro_SS_Convertible_(2000_1-75).jpg|100px]]
|-
|MB02 <br> (ROW)
|96004
|[[Ford Focus]]
|Ford
|Open Road
|2/5
|[[File:Ford_Focus_(2000_1-75).jpg|100px]]
|}"""
    }
}
