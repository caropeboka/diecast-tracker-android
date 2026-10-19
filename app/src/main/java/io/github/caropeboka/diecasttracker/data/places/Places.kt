package io.github.caropeboka.diecasttracker.data.places

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point taken longer ago than this is probably from another store (web: `location-field.tsx`). */
const val FRESH_PLACE_MS = 3 * 60 * 60 * 1000L

/**
 * A place as a field holds it: the name, and the point when one was chosen on the map or by GPS.
 * [accuracy] (meters) is only known while the point is where GPS put it; [at] is when the point was chosen.
 */
@Serializable
data class PlaceValue(
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracy: Int? = null,
    val at: Long? = null,
) {
    val hasPoint get() = lat != null && lng != null

    fun isFresh(now: Long) = hasPoint && at != null && now - at < FRESH_PLACE_MS

    fun withoutPoint() = copy(lat = null, lng = null, accuracy = null, at = null)
}

data class GpsFix(val lat: Double, val lng: Double, val accuracy: Int)

enum class PlaceSource { History, Osm, None }

/** A suggested name for a point: the user's own name for a spot nearby, else OpenStreetMap's. */
data class PlaceName(
    val name: String?,
    val address: String? = null,
    val source: PlaceSource = PlaceSource.None,
    /** Meters to the known spot ([PlaceSource.History] only). */
    val distance: Int? = null,
)

/** A search hit to jump to. */
data class PlaceResult(
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val source: PlaceSource,
)

/** Great-circle distance in meters (web: `PlaceLookup::distance`). */
fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earth = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * earth * asin(min(1.0, sqrt(a)))
}

/** Coordinates as stored and sent: six decimals (≈ 10 cm), no trailing zeros, never a locale's comma. */
fun round6(value: Double): Double = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).toDouble()

internal fun plain(value: Double): String =
    BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
