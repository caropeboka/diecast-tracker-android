package io.github.caropeboka.diecasttracker.data.places

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset

/** Ports of the web app's place tests (`ExploreTest`: lookup, search, distance). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaceLookupTest {
    private val env = TestEnvironment()
    private val osm = FakeNominatim()
    private val nominatim = Nominatim(osm.client, env.db.places(), "Test/1.0 (tests)", baseUrl = "https://osm.test", minIntervalMs = 0)
    private val places = PlaceLookup(env.db.places(), nominatim, ZoneOffset.UTC)

    @After
    fun tearDown() = env.close()

    private fun spotted(place: String, lat: Double?, lng: Double?, seenOn: String = "2026-10-01") = runBlocking {
        env.repository.save(
            DiecastEntity(name = "Spotted", status = DiecastStatus.Spotted),
            firstSighting = SightingEntity(diecastId = 0, place = place, latitude = lat, longitude = lng, seenOn = LocalDate.parse(seenOn)),
        )
    }

    private fun bought(place: String, lat: Double?, lng: Double?, date: String? = null) = runBlocking {
        env.repository.save(
            DiecastEntity(
                name = "Bought",
                purchasePlace = place,
                purchaseLatitude = lat,
                purchaseLongitude = lng,
                purchaseDate = date?.let(LocalDate::parse),
            ),
        )
    }

    @Test
    fun `lookup prefers the collector's own name for a nearby spot`() = runBlocking {
        spotted("Toys Kingdom GI", -6.195100, 106.821900)

        // ~60 m away.
        val name = places.lookup(-6.1956, 106.8221)

        assertEquals("Toys Kingdom GI", name.name)
        assertEquals(PlaceSource.History, name.source)
        assertTrue(name.distance!! in 55..65)
        assertTrue(osm.requests.isEmpty())
    }

    @Test
    fun `lookup recognises a place where something was bought`() = runBlocking {
        bought("Hobby Shop Blok M", -6.244300, 106.800600)

        // ~40 m away.
        assertEquals("Hobby Shop Blok M", places.lookup(-6.2446, 106.8008).name)
        assertTrue(osm.requests.isEmpty())
    }

    @Test
    fun `lookup falls back to OpenStreetMap and caches the answer`() = runBlocking {
        osm.on(
            "reverse",
            """{"name": "Grand Indonesia",
                "display_name": "Grand Indonesia, Jalan M.H. Thamrin, Menteng, Jakarta Pusat, Indonesia",
                "address": {"mall": "Grand Indonesia", "suburb": "Menteng"}}""",
        )

        val name = places.lookup(-6.1951, 106.8219)

        assertEquals(PlaceName("Grand Indonesia", "Grand Indonesia, Jalan M.H. Thamrin, Menteng, Jakarta Pusat", PlaceSource.Osm), name)
        val request = osm.requests.single()
        assertEquals("18", request.queryParameter("zoom"))
        assertEquals("-6.1951", request.queryParameter("lat"))
        assertEquals("Test/1.0 (tests)", osm.userAgents.single())

        // Same ~10 m cell: from the cache.
        places.lookup(-6.19512, 106.82188)
        assertEquals(1, osm.requests.size)
    }

    @Test
    fun `lookup without a named place uses road and area`() = runBlocking {
        osm.on(
            "reverse",
            """{"name": "", "display_name": "Jalan Kemang Raya, Bangka, Jakarta Selatan",
                "address": {"road": "Jalan Kemang Raya", "suburb": "Bangka"}}""",
        )

        assertEquals("Jalan Kemang Raya, Bangka", places.lookup(-6.26, 106.81).name)
    }

    @Test
    fun `lookup on an unnamed street uses the neighbourhood`() = runBlocking {
        osm.on(
            "reverse",
            """{"name": "", "display_name": "RW 05, Bangka, Mampang Prapatan, Jakarta Selatan, Indonesia",
                "address": {"city_block": "RW 05", "village": "Bangka"}}""",
        )

        val name = places.lookup(-6.2601, 106.8135)

        assertEquals("RW 05, Bangka", name.name)
        assertEquals(PlaceSource.Osm, name.source)
    }

    @Test
    fun `lookup survives OpenStreetMap being down, and does not cache it`() = runBlocking {
        osm.on("reverse", "[]", code = 503)

        assertEquals(PlaceName(null), places.lookup(-6.26, 106.81))
        places.lookup(-6.26, 106.81)
        assertEquals(2, osm.requests.size)
    }

    @Test
    fun `search without a query lists recent places with a point`() = runBlocking {
        bought("Hobby Shop Blok M", -6.2443, 106.8006, date = "2026-09-01")
        spotted("Toys Kingdom GI", -6.1951, 106.8219, seenOn = "2026-10-01")
        // No point: can't be jumped to.
        bought("Tokopedia", null, null)

        val found = places.search("")

        assertEquals(listOf("Toys Kingdom GI", "Hobby Shop Blok M"), found.map { it.name })
        assertEquals(PlaceSource.History, found[0].source)
        assertEquals(-6.2443, found[1].lat, 0.0)
        assertTrue(osm.requests.isEmpty())
    }

    @Test
    fun `search puts the collector's own places before OpenStreetMap`() = runBlocking {
        osm.on(
            "search",
            """[
                {"name": "Toys Kingdom", "lat": "-6.1580", "lon": "106.9080",
                 "display_name": "Toys Kingdom, Mall Kelapa Gading, Kelapa Gading, Jakarta Utara, Indonesia",
                 "address": {"shop": "Toys Kingdom"}},
                {"name": "", "lat": "1", "lon": "2", "display_name": "x", "address": {}},
                {"name": "Toys R Us", "lat": "19.2", "lon": "72.97", "display_name": "Toys R Us, Thane, India", "address": {}}
            ]""",
        )
        bought("Toys Kingdom GI", -6.1951, 106.8219)
        bought("Toys Kingdom GI", -6.1953, 106.8221)
        // Same name in another part of town: a different branch.
        bought("Toys Kingdom GI", -6.2600, 106.7800)

        val found = places.search("toys", lat = -6.2, lng = 106.82)

        assertEquals(listOf(PlaceSource.History, PlaceSource.History, PlaceSource.Osm), found.map { it.source })
        assertEquals("Toys Kingdom", found[2].name)
        assertEquals(-6.158, found[2].lat, 0.0)
        assertEquals("Toys Kingdom, Mall Kelapa Gading, Kelapa Gading, Jakarta Utara", found[2].address)

        val request = osm.requests.single()
        assertEquals("toys", request.queryParameter("q"))
        assertEquals("106.52,-5.9,107.12,-6.5", request.queryParameter("viewbox"))
        assertEquals("Test/1.0 (tests)", osm.userAgents.single())
    }

    @Test
    fun `short queries stay offline and OpenStreetMap trouble gives no results`() = runBlocking {
        assertTrue(places.search("gr").isEmpty())
        assertTrue(osm.requests.isEmpty())

        osm.on("search", "{}", code = 503)
        assertTrue(places.search("gramedia").isEmpty())
    }

    @Test
    fun `distance helper`() {
        // Monas to Bundaran HI is roughly 2.1 km.
        assertEquals(2200.0, distanceMeters(-6.1754, 106.8272, -6.1950, 106.8231), 150.0)
    }

    @Test
    fun `a chosen point stays fresh for three hours`() {
        val place = PlaceValue("Toys Kingdom GI", -6.1951, 106.8219, at = 1_000L)

        assertTrue(place.isFresh(1_000L + FRESH_PLACE_MS - 1))
        assertFalse(place.isFresh(1_000L + FRESH_PLACE_MS))
        assertFalse(place.copy(at = null).isFresh(1_000L))
        assertNull(place.withoutPoint().lat)
    }
}
