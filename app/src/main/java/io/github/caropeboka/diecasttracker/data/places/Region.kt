package io.github.caropeboka.diecasttracker.data.places

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** The levels of [Region], largest first. */
enum class RegionLevel(@StringRes val label: Int, @StringRes val by: Int) {
    Province(R.string.region_province, R.string.explore_by_province),
    City(R.string.region_city, R.string.explore_by_city),
    District(R.string.region_district, R.string.explore_by_district),
    Village(R.string.region_village, R.string.explore_by_village),
}

/**
 * The administrative areas a point lies in, from OpenStreetMap's admin levels: in Indonesia 4 = provinsi,
 * 5 = kabupaten/kota, 6 = kecamatan, 7 = desa/kelurahan. A level OpenStreetMap doesn't have there is null.
 */
data class Region(
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val village: String? = null,
) {
    private val names get() = listOf(province, city, district, village)

    fun at(level: RegionLevel): String? = names[level.ordinal]

    /** Whether this lies in [area]: names from the province down. */
    fun within(area: List<String>): Boolean = area.withIndex().all { (i, name) -> names.getOrNull(i) == name }

    companion object {
        val NONE = Region()

        /** ~100 m cells, like the map's unnamed spots: neighbours share one lookup. */
        fun cell(lat: Double, lng: Double): String = String.format(Locale.ROOT, "%.3f,%.3f", lat, lng)

        /** From Nominatim's GeocodeJSON (`features[0].properties.geocoding.admin.levelN`); an error answer has none. */
        fun from(json: JsonObject): Region {
            val admin = ((json["features"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.obj("properties")?.obj("geocoding")?.obj("admin")
                ?: return NONE
            fun level(n: Int) = admin.string("level$n")?.trim()?.takeIf(String::isNotEmpty)
            return Region(level(4), level(5), level(6), level(7))
        }
    }
}
