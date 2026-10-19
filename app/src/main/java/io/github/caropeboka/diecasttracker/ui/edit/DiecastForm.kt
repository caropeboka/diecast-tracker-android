package io.github.caropeboka.diecasttracker.ui.edit

import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DEFAULT_SCALE
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.MAX_PHOTOS
import io.github.caropeboka.diecasttracker.data.MAX_TAGS
import io.github.caropeboka.diecasttracker.data.MIN_YEAR
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastWithDetails
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import kotlinx.serialization.Serializable
import java.time.LocalDate

enum class Field {
    Name, Brand, Make, Series, CollectionNumber, Year, Scale, Color, Rarity, Packaging, Condition, Quantity,
    PurchasePrice, MarketValue, TargetPrice, PurchasePlace, StorageLocation, Notes, Tags, Photos,
    SightingPlace, SightingDate, SightingPrice,
}

/**
 * What the add/edit form holds, as typed (numbers stay text until saved). Serializable so it survives the
 * process being killed while the camera app is open.
 */
@Serializable
data class DiecastForm(
    val status: DiecastStatus = DiecastStatus.Owned,
    val name: String = "",
    val brand: String = "",
    val make: String = "",
    val series: String = "",
    val collectionNumber: String = "",
    val year: String = "",
    val scale: String = DEFAULT_SCALE,
    val color: String = "",
    val rarity: Rarity = Rarity.Regular,
    val packaging: Packaging = Packaging.Carded,
    val condition: Condition? = null,
    val quantity: String = "1",
    /** Digits only, like all money fields. */
    val purchasePrice: String = "",
    val marketValue: String = "",
    val targetPrice: String = "",
    /** ISO date. */
    val purchaseDate: String? = null,
    val purchasePlace: String = "",
    val purchaseLatitude: Double? = null,
    val purchaseLongitude: Double? = null,
    val storageLocation: String = "",
    val notes: String = "",
    /** Photo file names in order; the first is the cover. */
    val photos: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val aiScanned: Boolean = false,
    // First sighting, only when adding a new "Ditemui" diecast.
    val sightingPlace: String = "",
    val sightingDate: String = "",
    val sightingPrice: String = "",
) {
    /** Moving a new entry out of the wishlist: it was bought today unless told otherwise. */
    fun withStatus(status: DiecastStatus, isNew: Boolean, today: LocalDate) = copy(
        status = status,
        purchaseDate = if (isNew && status != DiecastStatus.Wishlist && purchaseDate == null) today.toString() else purchaseDate,
    )

    /**
     * The form with what the AI read filled in, and the fields it filled (web: `fieldsFromScan`). A "regular"
     * rarity is applied but not marked: it is the default, not a finding.
     */
    fun withScan(fields: ScanFields): Pair<DiecastForm, Set<Field>> {
        val filled = mutableSetOf<Field>()

        fun <T : Any> take(value: T?, field: Field): T? = value?.also { filled += field }

        val next = copy(
            name = take(fields.name, Field.Name) ?: name,
            brand = take(fields.brand, Field.Brand) ?: brand,
            make = take(fields.make, Field.Make) ?: make,
            series = take(fields.series, Field.Series) ?: series,
            collectionNumber = take(fields.collectionNumber, Field.CollectionNumber) ?: collectionNumber,
            year = take(fields.year, Field.Year)?.toString() ?: year,
            scale = take(fields.scale, Field.Scale) ?: scale,
            color = take(fields.color, Field.Color) ?: color,
            rarity = fields.rarity?.also { if (it != Rarity.Regular) filled += Field.Rarity } ?: rarity,
            packaging = take(fields.packaging, Field.Packaging) ?: packaging,
            condition = take(fields.condition, Field.Condition) ?: condition,
            notes = take(fields.notes, Field.Notes) ?: notes,
            aiScanned = true,
        )
        return next to filled
    }

    fun validate(today: LocalDate): Map<Field, String> = buildMap {
        fun length(field: Field, value: String, max: Int) {
            if (value.trim().length > max) put(field, "Maksimal $max karakter.")
        }

        fun money(field: Field, value: String) {
            if ((value.toLongOrNull() ?: 0) > MAX_MONEY) put(field, "Angkanya terlalu besar.")
        }

        if (name.isBlank()) put(Field.Name, "Nama model wajib diisi.")
        length(Field.Name, name, 255)
        length(Field.Brand, brand, 100)
        length(Field.Make, make, 100)
        length(Field.Series, series, 255)
        length(Field.CollectionNumber, collectionNumber, 100)
        length(Field.Scale, scale, 20)
        length(Field.Color, color, 100)
        length(Field.PurchasePlace, purchasePlace, 255)
        length(Field.StorageLocation, storageLocation, 255)
        length(Field.Notes, notes, 5000)
        length(Field.SightingPlace, sightingPlace, 255)

        if (year.isNotBlank()) {
            val value = year.trim().toIntOrNull()
            if (value == null || value < MIN_YEAR || value > today.year + 1) {
                put(Field.Year, "Tahun antara $MIN_YEAR dan ${today.year + 1}.")
            }
        }

        val count = quantity.trim().toIntOrNull()
        if (status != DiecastStatus.Spotted && (count == null || count !in 1..9999)) {
            put(Field.Quantity, "Jumlah 1 sampai 9999.")
        }

        money(Field.PurchasePrice, purchasePrice)
        money(Field.MarketValue, marketValue)
        money(Field.TargetPrice, targetPrice)
        money(Field.SightingPrice, sightingPrice)

        if (tags.size > MAX_TAGS) put(Field.Tags, "Maksimal $MAX_TAGS tag.")
        if (photos.size > MAX_PHOTOS) put(Field.Photos, "Maksimal $MAX_PHOTOS foto.")

        sightingDate.toLocalDateOrNull()?.let {
            if (it > today) put(Field.SightingDate, "Tanggal tidak boleh setelah hari ini.")
        }
    }

    /**
     * The record to save. Wishlist and Ditemui entries are not in the garage, so they keep no purchase or
     * storage details; only wishlist entries keep a target price (web: `diecast-form.tsx` submit).
     */
    fun toEntity(existing: DiecastEntity?): DiecastEntity {
        val owned = status.isOwnedOrSold
        val base = existing ?: DiecastEntity(name = "")

        return base.copy(
            name = name.trim(),
            brand = brand.clean(),
            make = make.clean(),
            series = series.clean(),
            collectionNumber = collectionNumber.clean(),
            year = year.trim().toIntOrNull(),
            scale = scale.clean(),
            color = color.clean(),
            rarity = rarity,
            packaging = packaging,
            condition = condition,
            status = status,
            quantity = quantity.trim().toIntOrNull()?.coerceIn(1, 9999) ?: base.quantity,
            purchasePrice = purchasePrice.toLongOrNull().takeIf { owned },
            marketValue = marketValue.toLongOrNull(),
            targetPrice = targetPrice.toLongOrNull().takeIf { status == DiecastStatus.Wishlist },
            purchaseDate = purchaseDate?.toLocalDateOrNull().takeIf { owned },
            purchasePlace = purchasePlace.clean().takeIf { owned },
            purchaseLatitude = purchaseLatitude.takeIf { owned },
            purchaseLongitude = purchaseLongitude.takeIf { owned },
            storageLocation = storageLocation.clean().takeIf { owned },
            notes = notes.clean(),
            aiScanned = aiScanned,
        )
    }

    /** The first "Ditemui" entry of a new spotted diecast; the diecast id is filled in on save. */
    fun firstSighting(today: LocalDate) = SightingEntity(
        diecastId = 0,
        place = sightingPlace.clean(),
        seenOn = sightingDate.toLocalDateOrNull() ?: today,
        price = sightingPrice.toLongOrNull(),
    )

    companion object {
        fun new(status: DiecastStatus, today: LocalDate) = DiecastForm(
            status = status,
            purchaseDate = if (status == DiecastStatus.Wishlist) null else today.toString(),
            sightingDate = today.toString(),
        )

        fun from(details: DiecastWithDetails, today: LocalDate): DiecastForm = with(details.diecast) {
            DiecastForm(
                status = status,
                name = name,
                brand = brand.orEmpty(),
                make = make.orEmpty(),
                series = series.orEmpty(),
                collectionNumber = collectionNumber.orEmpty(),
                year = year?.toString().orEmpty(),
                scale = scale.orEmpty(),
                color = color.orEmpty(),
                rarity = rarity,
                packaging = packaging,
                condition = condition,
                quantity = quantity.toString(),
                purchasePrice = purchasePrice?.toString().orEmpty(),
                marketValue = marketValue?.toString().orEmpty(),
                targetPrice = targetPrice?.toString().orEmpty(),
                purchaseDate = purchaseDate?.toString(),
                purchasePlace = purchasePlace.orEmpty(),
                purchaseLatitude = purchaseLatitude,
                purchaseLongitude = purchaseLongitude,
                storageLocation = storageLocation.orEmpty(),
                notes = notes.orEmpty(),
                photos = details.orderedPhotos.map { it.fileName },
                tags = details.orderedTags.map { it.name },
                aiScanned = aiScanned,
                sightingDate = today.toString(),
            )
        }
    }
}

private fun String.clean(): String? = trim().ifEmpty { null }

fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
