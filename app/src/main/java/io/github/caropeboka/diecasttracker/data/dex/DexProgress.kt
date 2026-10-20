package io.github.caropeboka.diecasttracker.data.dex

import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.db.LinkedDiecastRow
import io.github.caropeboka.diecasttracker.data.db.ProgressRow

/** Seen once spotted or owned (sold still counts: it was caught once); wanted = on the wishlist and not seen yet. */
data class EntryState(val seen: Boolean = false, val caught: Boolean = false, val wanted: Boolean = false) {
    companion object {
        private val SEEN = setOf(DiecastStatus.Owned, DiecastStatus.Spotted, DiecastStatus.Sold)
        private val CAUGHT = setOf(DiecastStatus.Owned, DiecastStatus.Sold)

        fun of(statuses: Collection<DiecastStatus>): EntryState {
            val seen = statuses.any { it in SEEN }
            return EntryState(seen = seen, caught = statuses.any { it in CAUGHT }, wanted = !seen && DiecastStatus.Wishlist in statuses)
        }
    }
}

data class DexCounts(val total: Int = 0, val seen: Int = 0, val caught: Int = 0, val wanted: Int = 0) {
    operator fun plus(other: DexCounts) = DexCounts(total + other.total, seen + other.seen, caught + other.caught, wanted + other.wanted)

    companion object {
        fun of(states: Collection<EntryState>) = DexCounts(
            total = states.size,
            seen = states.count { it.seen },
            caught = states.count { it.caught },
            wanted = states.count { it.wanted },
        )
    }
}

data class DexVariant(val item: CatalogItemEntity, val state: EntryState)

/** One number with its variants and the diecasts linked to any of them. */
data class DexEntry(
    val number: String,
    val sort: Int,
    val name: String,
    val series: String?,
    val seriesNumber: String?,
    /** The first variant's downloaded thumbnail. */
    val imagePath: String?,
    val isNew: Boolean,
    val isTh: Boolean,
    val isSth: Boolean,
    val exclusive: String?,
    val state: EntryState,
    val variants: List<DexVariant>,
    val diecasts: List<LinkedDiecastRow>,
)

data class SeriesProgress(val series: String, val total: Int, val seen: Int, val caught: Int)

/**
 * Pokédex-style progress (web: `CatalogProgress.php`): an entry (number) is seen once a diecast linked to any of
 * its variants was spotted or owned, and caught once owned.
 */
object DexProgress {
    /** Counts per catalog id. */
    fun perCatalog(rows: List<ProgressRow>): Map<Long, DexCounts> = rows
        .groupBy { it.catalogId }
        .mapValues { (_, catalogRows) ->
            DexCounts.of(catalogRows.groupBy { it.number }.values.map { entry -> EntryState.of(entry.mapNotNull { it.status }) })
        }

    /** One entry per number in dex order; [items] are in dex order (sort, variant, id). */
    fun entries(items: List<CatalogItemEntity>, linked: List<LinkedDiecastRow>): List<DexEntry> {
        val byItem = linked.groupBy { it.catalogItemId }

        return items.groupBy { it.number }.map { (number, variants) ->
            val main = variants.first()
            val diecasts = variants.flatMap { byItem[it.id].orEmpty() }

            DexEntry(
                number = number,
                sort = main.sort,
                name = main.name,
                series = main.series,
                seriesNumber = main.seriesNumber,
                imagePath = variants.firstNotNullOfOrNull { it.imagePath },
                isNew = variants.any { it.isNew },
                isTh = variants.any { it.isTh },
                isSth = variants.any { it.isSth },
                exclusive = variants.lastOrNull { it.exclusive != null }?.exclusive,
                state = EntryState.of(diecasts.map { it.status }),
                variants = variants.map { DexVariant(it, EntryState.of(byItem[it.id].orEmpty().map { d -> d.status })) },
                diecasts = diecasts,
            )
        }
    }

    fun counts(entries: List<DexEntry>) = DexCounts.of(entries.map { it.state })

    /** Completion per series ("HW J-Imports 3/10"), in the order the series first appear. */
    fun series(entries: List<DexEntry>): List<SeriesProgress> = entries
        .groupBy { it.series ?: "Lainnya" }
        .map { (series, group) ->
            SeriesProgress(series, group.size, group.count { it.state.seen }, group.count { it.state.caught })
        }
}
