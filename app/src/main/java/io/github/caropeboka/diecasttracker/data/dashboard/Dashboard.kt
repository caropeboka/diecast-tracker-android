package io.github.caropeboka.diecasttracker.data.dashboard

import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MissingField
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.DashboardRow
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.dex.CatalogSummary
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The headline numbers; all but [wishlist] and [spotted] count owned diecasts only. Money is whole Rupiah. */
data class DashboardStats(
    /** Pieces ([DiecastListItem.quantity] added up). */
    val totalItems: Int = 0,
    /** Diecasts (models). */
    val uniqueItems: Int = 0,
    val totalSpent: Long = 0,
    val pricedItems: Int = 0,
    val totalValue: Long = 0,
    val valuedItems: Int = 0,
    /** Only diecasts with both prices count, so a missing value doesn't look like a loss. */
    val valueGain: Long = 0,
    val gainItems: Int = 0,
    /** What those [gainItems] cost. */
    val gainCost: Long = 0,
    val specialItems: Int = 0,
    val superTreasureHunts: Int = 0,
    val wishlist: Int = 0,
    val spotted: Int = 0,
    val brands: Int = 0,
) {
    /**
     * [valueGain] against what the same diecasts cost. The web divides by total value minus gain, which counts the
     * value of diecasts without a purchase price as cost too.
     */
    val gainPercent: Int? get() = if (gainItems > 0 && gainCost > 0) (valueGain * 100.0 / gainCost).roundToInt() else null

    /** Per diecast (not per piece), like the web. */
    val averageValue: Long? get() = if (valuedItems > 0) (totalValue.toDouble() / valuedItems).roundToLong() else null
}

/** How many owned diecasts have [field] filled in. */
data class Completeness(val field: MissingField, val done: Int)

/** Owned pieces of one brand ([brand] null: none given) and their market value. */
data class BrandCount(val brand: String?, val label: String, val total: Int, val value: Long)

data class Count(val label: String, val total: Int)

data class RarityCount(val rarity: Rarity, val total: Int)

/** Owned pieces released in [start] (or the five years from it): [label] "'24" or "1990an". */
data class YearColumn(val start: Int, val label: String, val longLabel: String, val total: Int)

/** Everything the "Garasi" tab shows (web: `DashboardController` and the sums in `dashboard.tsx`). */
data class Dashboard(
    val stats: DashboardStats = DashboardStats(),
    /** Empty without owned diecasts. */
    val completeness: List<Completeness> = emptyList(),
    /** Most pieces first. */
    val brands: List<BrandCount> = emptyList(),
    /** The 8 makes with most pieces. */
    val makes: List<Count> = emptyList(),
    /** In the web's fixed order, rarities without pieces left out. */
    val rarities: List<RarityCount> = emptyList(),
    /** Every year (or 5-year span) between the first and the last, gaps included; empty below two known years. */
    val years: List<YearColumn> = emptyList(),
    val unknownYears: Int = 0,
    val scales: List<Count> = emptyList(),
    val mostValuable: List<DiecastListItem> = emptyList(),
    val topGainers: List<DiecastListItem> = emptyList(),
    /** Owned or sold, with a photo: the most valuable, then the newest. */
    val showcase: List<DiecastListItem> = emptyList(),
    val wishlist: List<DiecastListItem> = emptyList(),
    val recent: List<DiecastListItem> = emptyList(),
    /** The dex lists furthest along (seen share), with entries. */
    val dex: List<CatalogSummary> = emptyList(),
) {
    val isEmpty get() = stats.uniqueItems == 0

    companion object {
        /** Fixed identity colours; this order passed the web's colour-blind checks. */
        val RARITY_ORDER = listOf(
            Rarity.TreasureHunt, Rarity.SuperTreasureHunt, Rarity.Chase, Rarity.Exclusive, Rarity.Limited, Rarity.Regular,
        )

        const val NO_BRAND = "Tanpa merek"

        fun build(rows: List<DashboardRow>, catalogs: List<CatalogSummary>): Dashboard {
            val owned = rows.filter { it.item.status == DiecastStatus.Owned }
            val valued = owned.filter { it.item.marketValue != null }
            val bothPrices = valued.filter { it.item.purchasePrice != null }
            val newest = compareByDescending<DashboardRow> { it.createdAt }.thenByDescending { it.item.id }

            val stats = DashboardStats(
                totalItems = owned.sumOf { it.item.quantity },
                uniqueItems = owned.size,
                totalSpent = owned.sumOf { (it.item.purchasePrice ?: 0L) * it.item.quantity },
                pricedItems = owned.count { it.item.purchasePrice != null },
                totalValue = valued.sumOf { it.item.marketValue!! * it.item.quantity },
                valuedItems = valued.size,
                valueGain = bothPrices.sumOf { (it.item.marketValue!! - it.item.purchasePrice!!) * it.item.quantity },
                gainItems = bothPrices.size,
                gainCost = bothPrices.sumOf { it.item.purchasePrice!! * it.item.quantity },
                specialItems = owned.filter { it.item.rarity != Rarity.Regular }.sumOf { it.item.quantity },
                superTreasureHunts = owned.filter { it.item.rarity == Rarity.SuperTreasureHunt }.sumOf { it.item.quantity },
                wishlist = rows.count { it.item.status == DiecastStatus.Wishlist },
                spotted = rows.count { it.item.status == DiecastStatus.Spotted },
                brands = owned.mapNotNull { it.item.brand?.lowercase() }.distinct().size,
            )

            val completeness = if (owned.isEmpty()) emptyList() else listOf(
                Completeness(MissingField.Photo, owned.count { it.item.coverFile != null }),
                Completeness(MissingField.PurchasePrice, stats.pricedItems),
                Completeness(MissingField.MarketValue, stats.valuedItems),
                Completeness(MissingField.StorageLocation, owned.count { it.storageLocation != null }),
                Completeness(MissingField.Tags, owned.count { it.hasTags }),
            )

            // Grouped regardless of case (the web's MySQL collation); the spelling typed first names the group.
            val brands = owned.groupBy { it.item.brand?.lowercase() }.values
                .map { group ->
                    val brand = group.first().item.brand
                    BrandCount(
                        brand = brand,
                        label = brand ?: NO_BRAND,
                        total = group.sumOf { it.item.quantity },
                        value = group.sumOf { (it.item.marketValue ?: 0L) * it.item.quantity },
                    )
                }
                .sortedWith(compareByDescending<BrandCount> { it.total }.thenBy { it.label.lowercase() })

            val makes = owned.filter { it.make != null }.groupBy { it.make!!.lowercase() }.values
                .map { group -> Count(group.first().make!!, group.sumOf { it.item.quantity }) }
                .sortedWith(compareByDescending<Count> { it.total }.thenBy { it.label.lowercase() })
                .take(8)

            val rarities = RARITY_ORDER
                .map { rarity -> RarityCount(rarity, owned.filter { it.item.rarity == rarity }.sumOf { it.item.quantity }) }
                .filter { it.total > 0 }

            val scales = owned.groupBy { it.scale ?: "?" }
                .map { (scale, group) -> Count(scale, group.sumOf { it.item.quantity }) }
                .sortedWith(compareByDescending<Count> { it.total }.thenBy { it.label })

            val items = { list: List<DashboardRow> -> list.map { it.item } }

            return Dashboard(
                stats = stats,
                completeness = completeness,
                brands = brands,
                makes = makes,
                rarities = rarities,
                years = yearColumns(owned),
                unknownYears = owned.filter { it.item.year == null }.sumOf { it.item.quantity },
                scales = scales,
                mostValuable = items(
                    valued.sortedWith(compareByDescending<DashboardRow> { it.item.marketValue }.thenByDescending { it.item.id }).take(5),
                ),
                topGainers = items(
                    bothPrices.filter { it.item.marketValue!! > it.item.purchasePrice!! }
                        .sortedWith(
                            compareByDescending<DashboardRow> { it.item.marketValue!! - it.item.purchasePrice!! }
                                .thenByDescending { it.item.id },
                        )
                        .take(5),
                ),
                showcase = items(
                    rows.filter { it.item.status.isOwnedOrSold && it.item.coverFile != null }
                        .sortedWith(
                            compareBy<DashboardRow> { it.item.marketValue == null }
                                .thenByDescending { it.item.marketValue }
                                .thenByDescending { it.item.id },
                        )
                        .take(6),
                ),
                wishlist = items(rows.filter { it.item.status == DiecastStatus.Wishlist }.sortedWith(newest).take(5)),
                recent = items(rows.filter { it.item.status.isOwnedOrSold }.sortedWith(newest).take(6)),
                dex = catalogs.filter { it.counts.total > 0 }
                    .sortedByDescending { it.counts.seen.toDouble() / it.counts.total }
                    .take(4),
            )
        }

        /** Every year in the range, so gaps show; past 25 years, five at a time. */
        private fun yearColumns(owned: List<DashboardRow>): List<YearColumn> {
            val counts = owned.filter { it.item.year != null }
                .groupBy { it.item.year!! }
                .mapValues { (_, group) -> group.sumOf { it.item.quantity } }
            if (counts.size < 2) return emptyList()

            val first = counts.keys.min()
            val last = counts.keys.max()
            val step = if (last - first > 25) 5 else 1

            return (first - first % step..last step step).map { start ->
                val total = (start until start + step).sumOf { counts[it] ?: 0 }
                if (step == 1) {
                    YearColumn(start, "'${start.toString().takeLast(2)}", start.toString(), total)
                } else {
                    YearColumn(start, "${start}an", "${start}an", total)
                }
            }
        }
    }
}
