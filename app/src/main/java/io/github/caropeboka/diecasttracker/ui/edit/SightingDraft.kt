package io.github.caropeboka.diecasttracker.ui.edit

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import kotlinx.serialization.Serializable
import java.time.LocalDate

enum class SightingField { Place, Date, Price, Notes }

/**
 * A "Catat temuan" entry being typed (web: `sightings-panel.tsx`). Serializable so it survives the camera app.
 */
@Serializable
data class SightingDraft(
    val place: PlaceValue = PlaceValue(),
    /** ISO date. */
    val seenOn: String,
    /** Digits only. */
    val price: String = "",
    val notes: String = "",
    /** A pending photo in the photo store. */
    val photo: String? = null,
) {
    /** Web: `SightingLog::rules()`. */
    fun validate(today: LocalDate, texts: Texts): Map<SightingField, String> = buildMap {
        if (place.name.trim().length > 255) put(SightingField.Place, texts(R.string.valid_max_length, 255))
        if (notes.trim().length > 500) put(SightingField.Notes, texts(R.string.valid_max_length, 500))
        if ((price.toLongOrNull() ?: 0) > MAX_MONEY) put(SightingField.Price, texts(R.string.number_too_big))
        seenOn.toLocalDateOrNull()?.let { if (it > today) put(SightingField.Date, texts(R.string.date_not_future)) }
    }

    /** A point older than three hours is probably from another store, so only a fresh one is kept. */
    fun toEntity(diecastId: Long, today: LocalDate, now: Long): SightingEntity {
        val fresh = place.isFresh(now)
        return SightingEntity(
            diecastId = diecastId,
            place = place.name.trim().ifEmpty { null },
            latitude = place.lat.takeIf { fresh },
            longitude = place.lng.takeIf { fresh },
            seenOn = seenOn.toLocalDateOrNull() ?: today,
            price = price.toLongOrNull(),
            notes = notes.trim().ifEmpty { null },
            photoFile = photo,
        )
    }
}
