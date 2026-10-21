package io.github.caropeboka.diecasttracker.data.dashboard

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.CollectionFilter
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MissingField
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardTest {
    /** Every save is a moment later, so "newest" is the order of saving. */
    private var clock = 1_000L
    private val env = TestEnvironment(now = { clock++ })
    private val repository = env.repository
    private val dex = DexRepository(env.db, DexMatcher(env.db.dex()))

    @After
    fun tearDown() = env.close()

    private suspend fun save(diecast: DiecastEntity, photo: Boolean = false, tags: List<String> = emptyList()) =
        repository.save(diecast, if (photo) listOf(env.pendingPhoto()) else emptyList(), tags)

    private suspend fun dashboard() = Dashboard.build(repository.observeDashboard().first(), dex.observeCatalogs().first())

    /** Owned: Datsun (2 pcs, gain), AE86 (loss), Skyline (no purchase price), Civic (no market value, no year). */
    private suspend fun seed() {
        save(
            DiecastEntity(
                name = "'71 Datsun 510", brand = "Hot Wheels", make = "Nissan", year = 2024, rarity = Rarity.TreasureHunt,
                quantity = 2, purchasePrice = 35_000, marketValue = 150_000, storageLocation = "Rak A",
            ),
            photo = true,
            tags = listOf("JDM"),
        )
        save(
            DiecastEntity(name = "AE86", brand = "Tomica", make = "Toyota", year = 2023, purchasePrice = 89_000, marketValue = 80_000),
            photo = true,
        )
        save(DiecastEntity(name = "Skyline", brand = "hot wheels", make = "nissan", year = 2020, rarity = Rarity.SuperTreasureHunt, marketValue = 500_000))
        save(DiecastEntity(name = "Civic", brand = "Mini GT", scale = "1:43", purchasePrice = 120_000))
        save(DiecastEntity(name = "Porsche", status = DiecastStatus.Wishlist, targetPrice = 250_000))
        save(DiecastEntity(name = "Supra", status = DiecastStatus.Spotted, marketValue = 700_000))
        save(DiecastEntity(name = "Old car", status = DiecastStatus.Sold, marketValue = 900_000), photo = true)
    }

    @Test
    fun `stats count owned diecasts, gain only where both prices are known`() = runBlocking {
        seed()
        val stats = dashboard().stats

        assertEquals(5, stats.totalItems)
        assertEquals(4, stats.uniqueItems)
        assertEquals(279_000L, stats.totalSpent)
        assertEquals(3, stats.pricedItems)
        assertEquals(880_000L, stats.totalValue)
        assertEquals(3, stats.valuedItems)
        assertEquals(221_000L, stats.valueGain)
        assertEquals(2, stats.gainItems)
        // Against what the Datsuns and the AE86 cost (159.000), not the value of the Skyline too.
        assertEquals(139, stats.gainPercent)
        assertEquals(293_333L, stats.averageValue)
        assertEquals(3, stats.specialItems)
        assertEquals(1, stats.superTreasureHunts)
        assertEquals(1, stats.wishlist)
        assertEquals(1, stats.spotted)
        // "Hot Wheels" and "hot wheels" are one brand.
        assertEquals(3, stats.brands)
    }

    @Test
    fun `completeness and breakdowns`() = runBlocking {
        seed()
        val dashboard = dashboard()

        assertEquals(
            listOf(MissingField.Photo to 2, MissingField.PurchasePrice to 3, MissingField.MarketValue to 3, MissingField.StorageLocation to 1, MissingField.Tags to 1),
            dashboard.completeness.map { it.field to it.done },
        )
        assertEquals(
            listOf(BrandCount("Hot Wheels", "Hot Wheels", 3, 800_000), BrandCount("Mini GT", "Mini GT", 1, 0), BrandCount("Tomica", "Tomica", 1, 80_000)),
            dashboard.brands,
        )
        assertEquals(listOf(Count("Nissan", 3), Count("Toyota", 1)), dashboard.makes)
        assertEquals(
            listOf(Rarity.TreasureHunt to 2, Rarity.SuperTreasureHunt to 1, Rarity.Regular to 2),
            dashboard.rarities.map { it.rarity to it.total },
        )
        assertEquals(listOf("'20" to 1, "'21" to 0, "'22" to 0, "'23" to 1, "'24" to 2), dashboard.years.map { it.label to it.total })
        assertEquals(1, dashboard.unknownYears)
        assertEquals(listOf(Count("1:64", 4), Count("1:43", 1)), dashboard.scales)
    }

    @Test
    fun `lists of diecasts`() = runBlocking {
        seed()
        val dashboard = dashboard()

        assertEquals(listOf("Skyline", "'71 Datsun 510", "AE86"), dashboard.mostValuable.map { it.name })
        assertEquals(listOf("'71 Datsun 510"), dashboard.topGainers.map { it.name })
        // Sold ones with a photo are on show too, the most valuable first.
        assertEquals(listOf("Old car", "'71 Datsun 510", "AE86"), dashboard.showcase.map { it.name })
        assertEquals(listOf("Porsche"), dashboard.wishlist.map { it.name })
        assertEquals(listOf("Old car", "Civic", "Skyline", "AE86", "'71 Datsun 510"), dashboard.recent.map { it.name })
    }

    @Test
    fun `years more than 25 apart are counted five at a time`() = runBlocking {
        save(DiecastEntity(name = "Old", year = 1990))
        save(DiecastEntity(name = "New", year = 2024))
        save(DiecastEntity(name = "Newer", year = 2022))

        val years = dashboard().years

        assertEquals(listOf("1990an", "1995an", "2000an", "2005an", "2010an", "2015an", "2020an"), years.map { it.label })
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 2), years.map { it.total })
    }

    @Test
    fun `a garage without owned diecasts is empty`() = runBlocking {
        save(DiecastEntity(name = "Porsche", status = DiecastStatus.Wishlist))
        save(DiecastEntity(name = "Single year", status = DiecastStatus.Spotted, year = 2024))

        val dashboard = dashboard()

        assertTrue(dashboard.isEmpty)
        assertEquals(emptyList<Completeness>(), dashboard.completeness)
        assertEquals(emptyList<YearColumn>(), dashboard.years)
        assertEquals(1, dashboard.stats.wishlist)
    }

    /** Web: `DashboardTest::test_dashboard_shows_the_dex_lists_furthest_along`. */
    @Test
    fun `dashboard shows the dex lists furthest along`() = runBlocking {
        val dao = env.db.dex()
        dao.insertCatalog(CatalogEntity(brand = "Mini GT", name = "Mini GT 2026", source = DexMatcher.CUSTOM))
        val catalogId = dao.insertCatalog(CatalogEntity(brand = "Hot Wheels", name = "Hot Wheels Mainline 2026", year = 2026, source = "hotwheels_wiki"))
        val seen = dao.insertItem(CatalogItemEntity(catalogId = catalogId, number = "001", sort = 1, name = "A"))
        dao.insertItem(CatalogItemEntity(catalogId = catalogId, number = "002", sort = 2, name = "B"))
        val diecast = save(DiecastEntity(name = "A", status = DiecastStatus.Spotted))
        dao.setLink(diecast, seen, CatalogMatch.Confirmed.key)

        val lists = dashboard().dex

        // A dex with no entries yet isn't shown.
        assertEquals(listOf("Hot Wheels Mainline 2026"), lists.map { it.catalog.name })
        assertEquals(2, lists[0].counts.total)
        assertEquals(1, lists[0].counts.seen)
        assertEquals(0, lists[0].counts.caught)
    }

    @Test
    fun `the collection lists what a diecast still lacks`() = runBlocking {
        seed()

        suspend fun missing(field: MissingField) = repository
            .observeCollection(CollectionFilter(status = DiecastStatus.Owned, missing = field))
            .first()
            .map { it.name }
            .toSet()

        assertEquals(setOf("Skyline", "Civic"), missing(MissingField.Photo))
        assertEquals(setOf("Skyline"), missing(MissingField.PurchasePrice))
        assertEquals(setOf("Civic"), missing(MissingField.MarketValue))
        assertEquals(setOf("AE86", "Skyline", "Civic"), missing(MissingField.StorageLocation))
        assertEquals(setOf("AE86", "Skyline", "Civic"), missing(MissingField.Tags))
        assertFalse(CollectionFilter().isFiltered)
        assertTrue(CollectionFilter(missing = MissingField.Tags).isFiltered)
    }
}
