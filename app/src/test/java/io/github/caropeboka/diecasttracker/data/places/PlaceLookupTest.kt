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
@Config(application = Application::class, qualifiers = "in")
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
    fun `a receipt's store is found on its street or closer, never just in its town`() = runBlocking {
        // Nominatim finds nothing for a full Indonesian address, a street for the street and a kelurahan for the area.
        osm.onSearch("Ruko Cibinong City, Cibinong", "[]")
        osm.onSearch(
            "Jalan Tegar Beriman, Tengah, Cibinong",
            """[{"place_rank": 26, "lat": "-6.4812501", "lon": "106.8235227", "name": "Jalan Tegar Beriman",
                 "display_name": "Jalan Tegar Beriman, Tengah, Cibinong, Kab Bogor, Jawa Barat, 16914, Indonesia",
                 "address": {"road": "Jalan Tegar Beriman", "village": "Tengah"}}]""",
        )
        osm.onSearch("Tengah, Cibinong", """[{"place_rank": 16, "lat": "-6.48", "lon": "106.83", "name": "Tengah", "display_name": "Tengah, Cibinong"}]""")

        val found = places.locate("Indomaret Tegar Beriman", listOf(" Ruko Cibinong City, Cibinong ", "Jalan Tegar Beriman, Tengah, Cibinong", "Tengah, Cibinong"))!!
        assertEquals(-6.4812501, found.lat, 0.0)
        assertEquals(106.8235227, found.lng, 0.0)
        assertEquals(PlaceSource.Osm, found.source)
        // Found at the second: the third isn't asked.
        assertEquals(listOf("Ruko Cibinong City, Cibinong", "Jalan Tegar Beriman, Tengah, Cibinong"), osm.requests.map { it.queryParameter("q") })

        // A kelurahan's middle says too little; no answer at all is the same.
        assertNull(places.locate("Indomaret", listOf("Tengah, Cibinong")))
        assertNull(places.locate("Indomaret", listOf("Jalan Entah, Bogor")))
        assertNull(places.locate("Indomaret", emptyList()))
    }

    @Test
    fun `only a place named like the search counts, and a shop's search only when it finds one`() = runBlocking {
        // Nominatim lacks the street and answers with any "Jalan Raya" around.
        osm.onSearch(
            "Jalan Raya Teluk Naga, Pangkalan, Teluknaga",
            """[{"place_rank": 26, "lat": "-6.07", "lon": "106.63", "name": "Jalan Raya Kampung Melayu", "display_name": "Jalan Raya Kampung Melayu, Teluknaga"},
                {"place_rank": 26, "lat": "-6.08", "lon": "106.62", "name": "", "display_name": "Teluknaga"}]""",
        )
        osm.onSearch(
            "Indomaret, Pangkalan, Teluknaga",
            """[{"place_rank": 30, "lat": "-6.0649631", "lon": "106.6438872", "name": "Indomaret", "display_name": "Indomaret, Jalan Kalibaru, Pangkalan, Teluknaga",
                 "address": {"shop": "Indomaret", "road": "Jalan Kalibaru"}},
                {"place_rank": 16, "lat": "-6.06", "lon": "106.64", "name": "Pangkalan", "display_name": "Pangkalan, Teluknaga"}]""",
        )
        osm.onSearch(
            "Indomaret, Teluknaga",
            """[{"place_rank": 30, "lat": "-6.0649", "lon": "106.6438", "name": "Indomaret", "display_name": "Indomaret, Pangkalan"},
                {"place_rank": 30, "lat": "-6.0900", "lon": "106.6100", "name": "Indomaret Teluknaga 2", "display_name": "Indomaret Teluknaga 2"}]""",
        )
        // The same street under another spelling ("Teluknaga") is still it.
        osm.onSearch(
            "Jalan Teluk Naga, Teluknaga",
            """[{"place_rank": 26, "lat": "-6.071", "lon": "106.632", "name": "Jalan Raya Teluknaga", "display_name": "Jalan Raya Teluknaga, Teluknaga"}]""",
        )

        assertNull(places.locate("Indomaret Teluk Naga II", listOf("Jalan Raya Teluk Naga, Pangkalan, Teluknaga")))
        val shop = places.locate("Indomaret Teluk Naga II", listOf("Jalan Raya Teluk Naga, Pangkalan, Teluknaga"), "Indomaret, Pangkalan, Teluknaga")!!
        assertEquals(-6.0649631, shop.lat, 0.0)
        assertEquals(PlaceSource.Osm, shop.source)
        // Two branches in the area: neither.
        assertNull(places.locate("Indomaret", emptyList(), "Indomaret, Teluknaga"))
        assertEquals(-6.071, places.locate("Indomaret", listOf("Jalan Teluk Naga, Teluknaga"))!!.lat, 0.0)
        assertEquals("teluknaga", PlaceLookup.searchedName("Jalan Raya Teluk Naga, Pangkalan"))
        assertEquals("grandindonesia", PlaceLookup.searchedName("Grand Indonesia, Jakarta Pusat"))
    }

    @Test
    fun `a receipt's store moves onto the collector's own pin of it nearby`() = runBlocking {
        osm.onSearch(
            "Jalan Tegar Beriman, Tengah, Cibinong",
            """[{"place_rank": 26, "lat": "-6.4812501", "lon": "106.8235227", "name": "Jalan Tegar Beriman", "display_name": "Jalan Tegar Beriman, Tengah"}]""",
        )
        // ~300 m along the street, and a branch of the same name across town.
        bought("Indomaret Tegar Beriman", -6.4835, 106.8250)
        bought("Indomaret", -6.4600, 106.8500)

        val own = places.locate("indomaret tegar beriman", listOf("Jalan Tegar Beriman, Tengah, Cibinong"))!!
        assertEquals(PlaceSource.History, own.source)
        assertEquals(-6.4835, own.lat, 0.0)

        // Another name, or the same one over a kilometre away: the street's point.
        assertEquals(PlaceSource.Osm, places.locate("Indomaret", listOf("Jalan Tegar Beriman, Tengah, Cibinong"))!!.source)
    }

    @Test
    fun `regions come from OpenStreetMap's admin levels, cached per 100 m cell for a year`() = runBlocking {
        osm.on(
            "reverse",
            """{"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {"geocoding": {
                "type": "house", "city": "Daerah Khusus Ibukota Jakarta", "district": "Setiabudi",
                "admin": {"level9": "RW 02", "level7": "Kuningan Timur", "level6": "Setiabudi", "level5": "Jakarta Selatan",
                          "level4": "Daerah Khusus Ibukota Jakarta"}}}}]}""",
        )

        assertNull(nominatim.cachedRegion(-6.2297, 106.8296))
        val region = nominatim.region(-6.2297, 106.8296)

        assertEquals(Region("Daerah Khusus Ibukota Jakarta", "Jakarta Selatan", "Setiabudi", "Kuningan Timur"), region)
        assertEquals("geocodejson", osm.requests.single().queryParameter("format"))
        // Same ~100 m cell: from the cache.
        assertEquals(region, nominatim.region(-6.2299, 106.8297))
        assertEquals(region, nominatim.cachedRegion(-6.2299, 106.8297))
        assertEquals(1, osm.requests.size)

        // A missing level stays empty; at sea there's nothing, and that's remembered too.
        osm.on("reverse", """{"type": "FeatureCollection", "features": [{"properties": {"geocoding": {"admin": {"level5": "Kabupaten Bandung", "level4": "Jawa Barat"}}}}]}""")
        assertEquals(Region("Jawa Barat", "Kabupaten Bandung"), nominatim.region(-7.025, 107.53))
        osm.on("reverse", """{"error": "Unable to geocode"}""")
        assertEquals(Region.NONE, nominatim.region(-5.9, 106.9))
        assertEquals(Region.NONE, nominatim.cachedRegion(-5.9, 106.9))

        // Out of reach: unknown, and asked again next time.
        osm.on("reverse", "{}", code = 503)
        assertNull(nominatim.region(-7.2575, 112.7521))
        assertNull(nominatim.cachedRegion(-7.2575, 112.7521))

        // Regions outlive the other answers when the cache is pruned.
        env.db.places().pruneCache(before = Long.MAX_VALUE, regionsBefore = 0)
        assertEquals(region, nominatim.cachedRegion(-6.2297, 106.8296))
        env.db.places().pruneCache(before = 0, regionsBefore = Long.MAX_VALUE)
        assertNull(nominatim.cachedRegion(-6.2297, 106.8296))
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
