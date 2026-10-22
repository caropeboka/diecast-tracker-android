package io.github.caropeboka.diecasttracker.ui.scan

import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.ui.edit.DiecastForm
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** A queued photo's quick fields as the collector changed them (web: `Edits`); null takes the AI's reading. */
@Serializable
data class QuickEdits(
    val name: String? = null,
    val brand: String? = null,
    val series: String? = null,
    val collectionNumber: String? = null,
    val rarity: Rarity? = null,
    /** Digits: the purchase price, target price or asking price, by [QueueDefaults.status]. */
    val price: String? = null,
)

/** What every photo in the queue shares (web: `Defaults`): saved as, when, where, stored where, tags. */
@Serializable
data class QueueDefaults(
    val status: DiecastStatus = DiecastStatus.Owned,
    /** ISO date: bought on, or seen on for Ditemui. */
    val date: String = "",
    val place: PlaceValue = PlaceValue(),
    val storageLocation: String = "",
    val tags: List<String> = emptyList(),
)

/** The quick price field's label for [status]. */
fun priceLabel(status: DiecastStatus) = when (status) {
    DiecastStatus.Wishlist -> "Target harga"
    DiecastStatus.Spotted -> "Harga di sana"
    else -> "Harga beli"
}

/**
 * The form a queued photo becomes (web: `BulkScanController::save`): the AI's reading, the quick edits on top, and the
 * shared defaults, under the same rules as the add form. Ditemui entries get their first sighting there (place, date,
 * price) instead of a purchase.
 */
fun queuedForm(item: ScanItemEntity, edits: QuickEdits, defaults: QueueDefaults, today: LocalDate): DiecastForm {
    val base = DiecastForm.new(defaults.status, today).copy(photos = listOf(item.photoFile), tags = defaults.tags)
    val read = item.fields?.let { base.withScan(it).first } ?: base
    val form = read.copy(
        name = edits.name ?: read.name,
        brand = edits.brand ?: read.brand,
        series = edits.series ?: read.series,
        collectionNumber = edits.collectionNumber ?: read.collectionNumber,
        rarity = edits.rarity ?: read.rarity,
    )
    val price = edits.price.orEmpty()
    val date = defaults.date.ifBlank { today.toString() }

    return when (defaults.status) {
        DiecastStatus.Wishlist -> form.copy(targetPrice = price)
        DiecastStatus.Spotted -> form.copy(sightingPrice = price, sightingDate = date).withSightingLocation(defaults.place)
        else -> form.copy(purchasePrice = price, purchaseDate = date, storageLocation = defaults.storageLocation)
            .withPurchaseLocation(defaults.place)
    }
}
