package io.github.caropeboka.diecasttracker.data.price

import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import java.time.LocalDate

/** A price from the collector's own records: what they paid ([bought]) or saw on a shelf. */
data class OwnPrice(
    val price: Long,
    val bought: Boolean,
    val date: LocalDate?,
    val place: String?,
    /** The diecast it is for, when that's another one that looks like this. */
    val other: String?,
)

/** [prices] newest first; [median] over all of them. */
data class OwnPrices(val prices: List<OwnPrice>, val median: Long?)

/**
 * Prices from the collector's own records for a diecast and its look-alikes in the collection: purchase prices and
 * the shelf prices of sightings. No AI and no connection; works for diecasts no marketplace lists.
 */
class OwnPriceFinder(db: AppDatabase, private val matcher: CollectionMatcher) {
    private val diecasts = db.diecasts()
    private val sightings = db.sightings()

    suspend fun find(diecast: DiecastEntity): OwnPrices {
        val fields = ScanFields(name = diecast.name, brand = diecast.brand, collectionNumber = diecast.collectionNumber)
        val ids = (listOf(diecast.id) + matcher.find(fields, limit = LOOK_ALIKES).map { it.id }).distinct()
        val byId = diecasts.getAll(ids).associateBy { it.id }

        fun other(id: Long) = byId[id]?.takeIf { id != diecast.id }?.name
        val bought = ids.mapNotNull { id ->
            val row = byId[id] ?: return@mapNotNull null
            row.purchasePrice?.takeIf { it > 0 }?.let { OwnPrice(it, bought = true, row.purchaseDate, row.purchasePlace, other(id)) }
        }
        val seen = sightings.pricedFor(ids).map { OwnPrice(it.price ?: 0, bought = false, it.seenOn, it.place, other(it.diecastId)) }

        val all = (bought + seen).sortedWith(compareByDescending<OwnPrice> { it.date ?: LocalDate.MIN }.thenBy { it.price })
        return OwnPrices(all, median(all.map { it.price }))
    }

    companion object {
        const val LOOK_ALIKES = 8

        /** The middle price, or the mean of the middle two, rounded half up. */
        fun median(prices: List<Long>): Long? {
            if (prices.isEmpty()) return null
            val sorted = prices.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle] + 1) / 2
        }
    }
}
