package io.github.caropeboka.diecasttracker.data.explore

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.places.Region
import io.github.caropeboka.diecasttracker.data.places.RegionLevel
import io.github.caropeboka.diecasttracker.data.testTexts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneOffset

/** Ports of the web app's map tests (`ExploreTest`): the database queries and the grouping together. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExplorationTest {
    private val today = LocalDate.of(2026, 10, 19)
    private val env = TestEnvironment { today.atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli() }

    @After
    fun tearDown() = env.close()

    private fun add(diecast: DiecastEntity) = runBlocking { env.repository.save(diecast) }

    private fun seen(diecastId: Long, place: String, date: String, lat: Double? = null, lng: Double? = null, price: Long? = 40_000) =
        runBlocking {
            env.repository.addSighting(
                SightingEntity(diecastId = diecastId, place = place, seenOn = LocalDate.parse(date), latitude = lat, longitude = lng, price = price),
            )
        }

    private fun explore(type: ExploreType = ExploreType.All, range: ExploreRange = ExploreRange.All) = runBlocking {
        val (sightings, purchases) = env.repository.observeExploreEvents().first()
        Exploration.build(sightings, purchases, type, range, today, testTexts(), ZoneOffset.UTC)
    }

    @Test
    fun `map groups sightings by place with dates and items`() {
        val a = add(DiecastEntity(name = "Datsun 510", status = DiecastStatus.Spotted))
        val b = add(DiecastEntity(name = "RX-7", status = DiecastStatus.Wishlist))
        seen(a, "Toys Kingdom GI", "2026-09-01", -6.1950, 106.8218)
        seen(b, "toys kingdom gi", "2026-10-01", -6.1952, 106.8220)
        seen(a, "Pasar Gembrong", "2026-10-05")

        val map = explore()

        assertEquals(2, map.stats.places)
        assertEquals(1, map.stats.mapped)
        assertEquals(3, map.stats.sightings)
        assertEquals(2, map.stats.diecasts)
        assertEquals(LocalDate.of(2026, 9, 1), map.stats.first)
        assertEquals("Pasar Gembrong", map.places[0].name)
        assertNull(map.places[0].lat)
        // The spelling typed first wins a tie.
        assertEquals("Toys Kingdom GI", map.places[1].name)
        assertEquals(2, map.places[1].count)
        assertEquals(2, map.places[1].visits)
        assertEquals(-6.1951, map.places[1].lat!!, 0.0)
        assertEquals(3, map.timeline.size)

        val month = explore(range = ExploreRange.Month)
        assertEquals(2, month.stats.sightings)
    }

    @Test
    fun `map shows purchases next to sightings`() {
        val bought = add(
            DiecastEntity(
                name = "Datsun 510", purchasePlace = "Toys Kingdom GI", purchasePrice = 35_000,
                purchaseDate = LocalDate.of(2026, 9, 15), purchaseLatitude = -6.1950, purchaseLongitude = 106.8218,
            ),
        )
        seen(add(DiecastEntity(name = "RX-7", status = DiecastStatus.Spotted)), "toys kingdom gi", "2026-10-01", -6.1952, 106.8220, price = null)
        // No purchase date: dated by when it was added.
        add(DiecastEntity(name = "Supra", status = DiecastStatus.Sold, purchasePlace = "Tokopedia"))
        // Not bought yet, or bought somewhere unknown: not on the map.
        add(DiecastEntity(name = "Wish", status = DiecastStatus.Wishlist, purchasePlace = "Somewhere"))
        add(DiecastEntity(name = "Unknown"))

        val map = explore()

        assertEquals(2, map.stats.places)
        assertEquals(1, map.stats.mapped)
        assertEquals(2, map.stats.purchases)
        assertEquals(1, map.stats.sightings)
        assertEquals(3, map.stats.diecasts)
        assertEquals("Tokopedia", map.places[0].name)
        assertEquals(today, map.places[0].items[0].date)
        with(map.places[1]) {
            assertEquals("Toys Kingdom GI", name)
            assertEquals(2, count)
            assertEquals(1, purchases)
            assertEquals(1, sightings)
            assertEquals(EventKind.Purchase, items[1].kind)
            assertEquals("p$bought", items[1].id)
            assertEquals(LocalDate.of(2026, 9, 15), items[1].date)
            assertEquals(35_000L, items[1].price)
        }
        assertEquals(1, map.timeline[2].places[0].purchases)
        // A day in the history opens to what was seen or bought that day, and where.
        assertEquals(listOf("Datsun 510" to "Toys Kingdom GI"), map.timeline[2].items.map { it.name to it.place })
        assertEquals(listOf("RX-7"), map.timeline[1].items.map { it.name })

        with(explore(type = ExploreType.Purchase).stats) {
            assertEquals(0, sightings)
            assertEquals(2, purchases)
        }
        with(explore(type = ExploreType.Sighting)) {
            assertEquals(1, stats.places)
            assertEquals(0, stats.purchases)
            assertEquals(1, places[0].sightings)
        }
        assertEquals(ExploreType.All, ExploreType.fromKey("bogus"))
    }

    @Test
    fun `a sighting shows its own photo, else the diecast's cover`() {
        val cover = env.pendingPhoto()
        val id = runBlocking { env.repository.save(DiecastEntity(name = "RX-7", status = DiecastStatus.Spotted), listOf(cover)) }
        val own = env.pendingPhoto()
        runBlocking {
            env.repository.addSighting(SightingEntity(diecastId = id, place = "A", seenOn = today, photoFile = own))
            env.repository.addSighting(SightingEntity(diecastId = id, place = "B", seenOn = today))
        }

        val items = explore().places.flatMap { it.items }.associate { it.place to it.photoFile }

        assertEquals(own, items["A"])
        assertEquals(cover, items["B"])
    }

    @Test
    fun `unnamed points group by a 100 m cell`() {
        val id = add(DiecastEntity(name = "X", status = DiecastStatus.Spotted))
        runBlocking {
            env.repository.addSighting(SightingEntity(diecastId = id, seenOn = today, latitude = -6.20011, longitude = 106.80011))
            env.repository.addSighting(SightingEntity(diecastId = id, seenOn = today, latitude = -6.20014, longitude = 106.80014))
            env.repository.addSighting(SightingEntity(diecastId = id, seenOn = today))
        }

        val names = explore().places.map { it.name to it.count }.toSet()

        assertEquals(setOf("Titik tanpa nama" to 2, "Tanpa nama tempat" to 1), names)
    }

    @Test
    fun `regions - a bar per area one level down, and the page narrowed to the one picked`() {
        val a = add(DiecastEntity(name = "Datsun 510", status = DiecastStatus.Spotted))
        add(
            DiecastEntity(
                name = "RX-7", purchasePlace = "Toys Kingdom GI", purchaseDate = LocalDate.of(2026, 10, 1),
                purchaseLatitude = -6.1951, purchaseLongitude = 106.8219,
            ),
        )
        seen(a, "Toys Kingdom GI", "2026-10-02", -6.1951, 106.8219)
        seen(a, "Hobby Shop Cibinong", "2026-10-03", -6.4815, 106.8540)
        seen(a, "Pasar Gembrong", "2026-10-04")
        // Not looked up yet.
        seen(a, "Tunjungan Plaza", "2026-10-05", -7.2625, 112.7393)
        // OpenStreetMap has no kecamatan or desa there.
        seen(a, "Soreang", "2026-10-06", -7.0250, 107.5300)
        val regions = mapOf(
            Region.cell(-6.1951, 106.8219) to Region("Daerah Khusus Ibukota Jakarta", "Jakarta Pusat", "Tanah Abang", "Kebon Melati"),
            Region.cell(-6.4815, 106.8540) to Region("Jawa Barat", "Bogor", "Cibinong", "Cibinong"),
            Region.cell(-7.0250, 107.5300) to Region("Jawa Barat", "Kabupaten Bandung"),
        )
        fun explore(vararg area: String) = runBlocking {
            val (sightings, purchases) = env.repository.observeExploreEvents().first()
            Exploration.build(sightings, purchases, ExploreType.All, ExploreRange.All, today, testTexts(), ZoneOffset.UTC, regions, area.toList())
        }

        val all = explore().regions
        assertEquals(RegionLevel.Province, all.level)
        // Two each: the name decides.
        assertEquals(listOf(RegionRow("Daerah Khusus Ibukota Jakarta", 1, 1, 1), RegionRow("Jawa Barat", 2, 0, 2)), all.rows)
        assertEquals(1, all.unmapped)
        assertEquals(1, all.pending)

        val west = explore("Jawa Barat")
        assertEquals(RegionLevel.City, west.regions.level)
        assertEquals(listOf("Bogor", "Kabupaten Bandung"), west.regions.rows.map { it.name })
        assertEquals(2, west.stats.sightings)
        assertEquals(setOf("Hobby Shop Cibinong", "Soreang"), west.places.map { it.name }.toSet())
        assertEquals(0, west.regions.unmapped)
        assertEquals(0, west.regions.pending)

        assertEquals(listOf(RegionRow(null, 1, 0, 1)), explore("Jawa Barat", "Kabupaten Bandung").regions.rows)

        val village = explore("Daerah Khusus Ibukota Jakarta", "Jakarta Pusat", "Tanah Abang", "Kebon Melati")
        assertNull(village.regions.level)
        assertEquals(emptyList<RegionRow>(), village.regions.rows)
        assertEquals(2, village.places.single().count)
        assertEquals(1, village.stats.purchases)
        assertEquals(2, village.timeline.size)
    }
}
