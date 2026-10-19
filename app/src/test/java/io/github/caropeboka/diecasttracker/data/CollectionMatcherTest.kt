package io.github.caropeboka.diecasttracker.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CollectionMatcher.Companion.normalize
import io.github.caropeboka.diecasttracker.data.CollectionMatcher.Companion.similarText
import io.github.caropeboka.diecasttracker.data.CollectionMatcher.Companion.similarity
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionMatcherTest {
    private val env = TestEnvironment()
    private val matcher = CollectionMatcher(env.repository)

    @After
    fun tearDown() = env.close()

    private fun add(name: String, change: (DiecastEntity) -> DiecastEntity = { it }) =
        runBlocking { env.repository.save(change(DiecastEntity(name = name))) }

    private fun find(name: String, brand: String? = null, number: String? = null) =
        runBlocking { matcher.find(ScanFields(name = name, brand = brand, collectionNumber = number)) }

    @Test
    fun `similar_text gives what PHP gives`() {
        // php -r 'similar_text($a, $b, $p);' for each pair.
        val expected = listOf(
            Triple("71datsun510", "datsun510", 9),
            Triple("datsun510", "fordmustang", 3),
            Triple("nissanskylinegtrbnr34", "nissanskylinegtrr34", 19),
            Triple("toyotasupra", "toyotasupramkiv", 11),
            Triple("hondacivictyper", "hondacivicsi", 10),
            Triple("abcxyzdef", "defxyzabc", 3),
            Triple("porsche911gt3rs", "porsche911carrera", 11),
        )
        expected.forEach { (a, b, common) -> assertEquals("$a / $b", common, similarText(a, b)) }

        assertEquals(74.0741, similarity("hondacivictyper", "hondacivicsi"), 0.0001)
        assertEquals(87.5, similarity("mazdarx7", "mazdarx8"), 0.0001)
        // Containment is a strong match.
        assertEquals(90 + 10.0 * 9 / 11, similarity("71datsun510", "datsun510"), 0.0001)
    }

    @Test
    fun `names are compared by letters and digits only`() {
        assertEquals("71datsun510", normalize("'71 Datsun 510"))
        assertEquals("nissanskylinegtrbnr34", normalize("Nissan Skyline GT-R (BNR34)"))
        assertEquals("", normalize(null))
    }

    @Test
    fun `finds look-alikes of the same brand or without brand, best first`() {
        add("Datsun 510") { it.copy(brand = "Hot Wheels") }
        add("'71 Datsun 510 Wagon") { it.copy(brand = null) }
        add("Datsun 510") { it.copy(brand = "Tomica") }
        add("Ford Mustang") { it.copy(brand = "Hot Wheels") }

        val names = find("'71 Datsun 510", brand = "hot wheels").map { it.name }

        assertEquals(listOf("Datsun 510", "'71 Datsun 510 Wagon"), names)
    }

    @Test
    fun `the same collection number lifts a near miss over the line`() {
        add("Honda Civic Si") { it.copy(collectionNumber = "61/250") }

        // "hondacivictyper" vs "hondacivicsi" is 74.1, just short of 75.
        assertEquals(0, find("Honda Civic Type R").size)
        assertEquals(0, find("Honda Civic Type R", number = "62/250").size)
        assertEquals(1, find("Honda Civic Type R", number = "61 / 250").size)
    }

    @Test
    fun `match cards carry what the warning shows`() = runBlocking {
        val id = add("Nissan Skyline GT-R (R34)") { it.copy(status = DiecastStatus.Spotted) }
        env.db.sightings().insert(SightingEntity(diecastId = id, seenOn = LocalDate.of(2026, 10, 1), price = 45_000))
        env.db.sightings().insert(SightingEntity(diecastId = id, seenOn = LocalDate.of(2026, 10, 5), price = 35_000))

        val match = find("Nissan Skyline GT-R (BNR34)").single()

        assertEquals(DiecastStatus.Spotted, match.status)
        assertEquals(2, match.sightingsCount)
        assertEquals(35_000L, match.sightingsMinPrice)
    }

    @Test
    fun `no name, no matches`() {
        add("Datsun 510")

        assertEquals(0, find("").size)
    }
}
