package io.github.caropeboka.diecasttracker.data.places

import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.db.PlaceDao
import io.github.caropeboka.diecasttracker.data.db.SpotRow
import io.github.caropeboka.diecasttracker.util.obj
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Turns GPS coordinates into a place name: the collector's own name for a spot they logged before (so
 * "Toys Kingdom GI" only has to be typed once), otherwise OpenStreetMap's (web: `PlaceLookup.php`).
 */
class PlaceLookup(
    private val dao: PlaceDao,
    private val osm: Nominatim,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun lookup(lat: Double, lng: Double): PlaceName =
        nearestKnown(lat, lng) ?: osm.reverse(lat, lng)?.let { result ->
            val (name, address) = describe(result)
            PlaceName(name, address, PlaceSource.Osm)
        } ?: PlaceName(null)

    /**
     * Places matching [query]: the collector's own spots first, then OpenStreetMap, biased towards [lat]/[lng]
     * (where the map is) so "Toys Kingdom" finds the nearby branch. An empty query lists recent spots.
     */
    suspend fun search(query: String, lat: Double? = null, lng: Double? = null): List<PlaceResult> {
        val needle = query.trim().lowercase()

        val known = knownSpots()
            .filter { needle.isEmpty() || needle in it.place.lowercase() }
            // Same name ~1 km apart = same place; chains with many branches stay separate.
            .groupBy { String.format(Locale.ROOT, "%s|%.2f,%.2f", it.place.trim().lowercase(), it.lat, it.lng) }
            .values
            .map { spots ->
                val place = PlaceResult(
                    name = spots.sortedByDescending { it.date }.first().place,
                    address = null,
                    lat = round6(spots.map { it.lat }.average()),
                    lng = round6(spots.map { it.lng }.average()),
                    source = PlaceSource.History,
                )
                place to spots.maxOf { it.date }
            }
            .sortedByDescending { (_, last) -> last }
            .take(if (needle.isEmpty()) 8 else 5)
            .map { (place, _) -> place }

        if (needle.length < 3) return known

        return known + forwardGeocode(needle, lat, lng)
    }

    /**
     * Where a shop is, from searches the AI made of its address ("Jalan Tegar Beriman, Tengah, Cibinong"): the first
     * that OpenStreetMap places on a street or closer, as a kelurahan's or a city's middle says too little, and only a
     * place named like what was searched (Nominatim answers a street it lacks with any "Jalan Raya" in the area). Then
     * [shop], the chain's name in its area ("Indomaret, Pangkalan, Teluknaga"), when exactly one such shop comes back.
     * The collector's own pin of the shop's [name] within a kilometre is closer still. Null when nothing that close is
     * found or OpenStreetMap can't be reached.
     */
    suspend fun locate(name: String?, queries: List<String>, shop: String? = null): PlaceResult? {
        val found = queries.asSequence()
            .map(String::trim)
            .filter { it.length >= 3 }
            .distinct()
            .take(MAX_LOCATE_QUERIES)
            .firstNotNullOfOrNull { query -> located(query, STREET_RANK).firstOrNull() }
            ?: shop?.trim()?.takeIf { it.length >= 3 }?.let { located(it, SHOP_RANK).singleOrNull() }
            ?: return null

        val own = name?.trim()?.lowercase()?.ifEmpty { null }?.let { needle ->
            knownSpots()
                .filter { it.place.trim().lowercase() == needle }
                .map { it to distanceMeters(found.lat, found.lng, it.lat, it.lng) }
                .filter { (_, meters) -> meters <= OWN_PIN_METERS }
                .minByOrNull { (_, meters) -> meters }
                ?.first
        }
        return own?.let { PlaceResult(it.place, found.address, it.lat, it.lng, PlaceSource.History) } ?: found
    }

    /** What OpenStreetMap finds for [query] of at least [rank], named like the query's first part. */
    private suspend fun located(query: String, rank: Int): List<PlaceResult> {
        val wanted = searchedName(query)
        return osm.search(query, null, null)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { result ->
                val placeRank = (result["place_rank"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
                val lat = result.number("lat")
                val lng = result.number("lon")
                if (placeRank < rank || lat == null || lng == null) return@mapNotNull null
                val own = searchedName(result.string("name").orEmpty())
                if (wanted.isEmpty() || own.isEmpty() || CollectionMatcher.similarity(wanted, own) < NAME_SIMILARITY) return@mapNotNull null
                val (osmName, address) = describe(result)
                PlaceResult(osmName ?: query, address, lat, lng, PlaceSource.Osm)
            }
            .orEmpty()
    }

    private suspend fun nearestKnown(lat: Double, lng: Double): PlaceName? {
        // ~0.002° ≈ 220 m: a cheap box first, exact distance after.
        val nearest = dao.spots(lat - BOX, lat + BOX, lng - BOX, lng + BOX)
            .map { it.place to distanceMeters(lat, lng, it.latitude, it.longitude) }
            .filter { (_, meters) -> meters <= NEARBY_METERS }
            .minByOrNull { (_, meters) -> meters }
            ?: return null

        return PlaceName(nearest.first, source = PlaceSource.History, distance = Math.round(nearest.second).toInt())
    }

    private suspend fun forwardGeocode(query: String, lat: Double?, lng: Double?): List<PlaceResult> {
        val results = osm.search(query, lat, lng) ?: return emptyList()

        var places = results
            .mapNotNull { it as? JsonObject }
            .mapNotNull { result ->
                val placeLat = result.number("lat") ?: return@mapNotNull null
                val placeLng = result.number("lon") ?: return@mapNotNull null
                if (result.string("name").isNullOrBlank()) return@mapNotNull null
                val (name, address) = describe(result)
                PlaceResult(name ?: return@mapNotNull null, address, placeLat, placeLng, PlaceSource.Osm)
            }

        if (lat != null && lng != null) {
            // The viewbox only biases: nearest first, and drop other countries when something is close.
            val byDistance = places.map { it to distanceMeters(lat, lng, it.lat, it.lng) / 1000 }.sortedBy { it.second }
            places = byDistance
                .filter { (_, km) -> byDistance.none { it.second <= NEARBY_KM } || km <= NEARBY_KM }
                .map { it.first }
        }

        return places
    }

    /** Every named place with coordinates: sightings and purchases, dated. */
    private suspend fun knownSpots(): List<Spot> = dao.spots(-90.0, 90.0, -180.0, 180.0).map { it.toSpot() }

    private fun SpotRow.toSpot() = Spot(
        place = place,
        lat = latitude,
        lng = longitude,
        date = seenOn ?: purchaseDate ?: Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate(),
    )

    private data class Spot(val place: String, val lat: Double, val lng: Double, val date: LocalDate)

    companion object {
        /** A previously logged place counts as "here" within this distance. */
        const val NEARBY_METERS = 150

        /** Search results within this distance of the map beat ones further away. */
        const val NEARBY_KM = 150

        private const val BOX = 0.002

        /** Nominatim's place_rank of a street; buildings and shops are 30, a kelurahan or a town 16–20. */
        const val STREET_RANK = 26

        /** A shop, a building or an address point. */
        const val SHOP_RANK = 30

        /** A receipt's searches tried at most, at Nominatim's pace of one a second. */
        const val MAX_LOCATE_QUERIES = 3

        /** How alike a place's name and what was searched must be (`CollectionMatcher.similarity`, 0–100). */
        private const val NAME_SIMILARITY = 80.0

        /** Words a street's name may or may not carry. */
        private val ROAD_WORDS = setOf("jalan", "jl", "jln", "raya", "gang", "gg")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** A search's first part as a name, without the words for a road: "Jalan Raya Teluk Naga, …" → "teluknaga". */
        fun searchedName(text: String): String =
            text.substringBefore(',').lowercase(Locale.ROOT).split(NOT_WORD).filter { it.isNotEmpty() && it !in ROAD_WORDS }.joinToString("")

        /** The collector's own pin of the same shop counts within this distance of where its address is. */
        const val OWN_PIN_METERS = 1_000

        /** Short name + address for a Nominatim result. */
        fun describe(result: JsonObject): Pair<String?, String?> {
            val address = result.obj("address") ?: JsonObject(emptyMap())
            val area = listOf("suburb", "city_district", "village", "city").firstNotNullOfOrNull { address.string(it) }
            val ownName = result.string("name")?.takeIf(String::isNotBlank)

            var name = ownName ?: listOf("shop", "amenity", "mall", "building", "tourism", "leisure", "road")
                .firstNotNullOfOrNull { key -> address.string(key)?.takeIf(String::isNotBlank) }

            if (name != null && area != null && area !in name && ownName == null) {
                name += ", $area"
            }

            val parts = result.string("display_name")?.takeIf(String::isNotBlank)?.split(", ")
            // Nothing named here (e.g. a small unnamed street): the neighbourhood, like "RW 05, Bangka".
            if (name == null && parts != null) {
                name = parts.take(2).joinToString(", ")
            }

            return name to parts?.take(4)?.joinToString(", ")
        }

        /** Nominatim sends coordinates as strings. */
        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
    }
}
