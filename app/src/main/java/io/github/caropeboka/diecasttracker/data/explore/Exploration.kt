package io.github.caropeboka.diecasttracker.data.explore

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.PurchaseEventRow
import io.github.caropeboka.diecasttracker.data.db.SightingEventRow
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.places.Region
import io.github.caropeboka.diecasttracker.data.places.RegionLevel
import io.github.caropeboka.diecasttracker.data.places.round6
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class ExploreType(val key: String, @StringRes val label: Int) {
    All("all", R.string.explore_all),
    Sighting("sighting", R.string.explore_sightings),
    Purchase("purchase", R.string.explore_purchases);

    companion object {
        fun fromKey(key: String?): ExploreType = entries.find { it.key == key } ?: All
    }
}

enum class ExploreRange(val key: String, @StringRes val label: Int, val days: Long?) {
    All("all", R.string.explore_all, null),
    Month("30", R.string.explore_30_days, 30),
    Quarter("90", R.string.explore_3_months, 90),
    Year("365", R.string.explore_1_year, 365);

    companion object {
        fun fromKey(key: String?): ExploreRange = entries.find { it.key == key } ?: All
    }
}

enum class EventKind { Sighting, Purchase }

/** Something seen or bought somewhere, on a day. [id] is "s12" for sighting 12, "p7" for diecast 7's purchase. */
data class ExploreEvent(
    val kind: EventKind,
    val id: String,
    val diecastId: Long,
    val name: String,
    val brand: String?,
    val photoFile: String?,
    val place: String?,
    val lat: Double?,
    val lng: Double?,
    val date: LocalDate,
    val price: Long?,
)

/** One marker: the events at a place, newest first. */
data class ExplorePlace(
    val key: String,
    val name: String,
    val lat: Double?,
    val lng: Double?,
    val count: Int,
    val sightings: Int,
    val purchases: Int,
    val visits: Int,
    val first: LocalDate,
    val last: LocalDate,
    /** The latest [MAX_ITEMS]. */
    val items: List<ExploreEvent>,
) {
    val hasPoint get() = lat != null && lng != null

    companion object {
        const val MAX_ITEMS = 12
    }
}

data class ExploreStats(
    val places: Int = 0,
    val mapped: Int = 0,
    val sightings: Int = 0,
    val purchases: Int = 0,
    val diecasts: Int = 0,
    val days: Int = 0,
    val first: LocalDate? = null,
    val last: LocalDate? = null,
    val top: String? = null,
)

data class TimelinePlace(val name: String, val sightings: Int, val purchases: Int)

/** One hunting day: per place, and [items] everything seen or bought that day. */
data class TimelineDay(val date: LocalDate, val places: List<TimelinePlace>, val items: List<ExploreEvent> = emptyList())

/** One bar of [RegionChart]; [name] null = OpenStreetMap has no area at this level there. */
data class RegionRow(val name: String?, val sightings: Int, val purchases: Int, val places: Int) {
    val count get() = sightings + purchases
}

/** The events inside [area] (names from the province down), per area one [level] below it. */
data class RegionChart(
    val area: List<String> = emptyList(),
    /** Null when [area] is a village: nothing below it. */
    val level: RegionLevel? = RegionLevel.Province,
    /** Most events first, the unknown ones last. */
    val rows: List<RegionRow> = emptyList(),
    /** Events without a point on the map, so without a region (only outside a chosen area). */
    val unmapped: Int = 0,
    /** Events whose point hasn't been looked up yet. */
    val pending: Int = 0,
)

/** "Peta jelajah": every place where diecasts were spotted or bought, when, and what was there (web: `ExploreController`). */
data class Exploration(
    val places: List<ExplorePlace> = emptyList(),
    val stats: ExploreStats = ExploreStats(),
    val timeline: List<TimelineDay> = emptyList(),
    val regions: RegionChart = RegionChart(),
) {
    companion object {
        /**
         * [regions]: what's known per [Region.cell]; [area]: only the events inside it count (so nothing without a
         * point or not looked up yet).
         */
        fun build(
            sightings: List<SightingEventRow>,
            purchases: List<PurchaseEventRow>,
            type: ExploreType,
            range: ExploreRange,
            today: LocalDate,
            texts: Texts,
            zone: ZoneId = ZoneId.systemDefault(),
            regions: Map<String, Region> = emptyMap(),
            area: List<String> = emptyList(),
        ): Exploration {
            val since = range.days?.let { today.minusDays(it) }
            fun regionOf(event: ExploreEvent) = if (event.lat != null && event.lng != null) regions[Region.cell(event.lat, event.lng)] else null

            val sightingEvents = if (type == ExploreType.Purchase) emptyList() else sightings.map { it.toEvent() }
            // Dated by purchase date, or when it was added.
            val purchaseEvents = if (type == ExploreType.Sighting) emptyList() else purchases.map { it.toEvent(zone) }

            val events = (sightingEvents + purchaseEvents)
                .filter { since == null || it.date >= since }
                .filter { area.isEmpty() || regionOf(it)?.within(area) == true }
                .sortedByDescending { it.date }
            val places = groupByPlace(events, texts)

            return Exploration(
                places = places,
                stats = ExploreStats(
                    places = places.size,
                    mapped = places.count { it.hasPoint },
                    sightings = events.count { it.kind == EventKind.Sighting },
                    purchases = events.count { it.kind == EventKind.Purchase },
                    diecasts = events.map { it.diecastId }.distinct().size,
                    days = events.map { it.date }.distinct().size,
                    first = events.lastOrNull()?.date,
                    last = events.firstOrNull()?.date,
                    top = places.sortedByDescending { it.count }.firstOrNull()?.name,
                ),
                timeline = events
                    .groupBy { it.date }
                    .entries
                    .map { (date, day) ->
                        TimelineDay(
                            date,
                            day.groupBy { it.place ?: texts(R.string.place_unnamed) }.map { (name, items) ->
                                TimelinePlace(
                                    name,
                                    sightings = items.count { it.kind == EventKind.Sighting },
                                    purchases = items.count { it.kind == EventKind.Purchase },
                                )
                            },
                            items = day,
                        )
                    },
                regions = regionChart(events, area, ::regionOf),
            )
        }

        private fun regionChart(events: List<ExploreEvent>, area: List<String>, regionOf: (ExploreEvent) -> Region?): RegionChart {
            val level = RegionLevel.entries.getOrNull(area.size)
            val rows = if (level == null) {
                emptyList()
            } else {
                events
                    .mapNotNull { event -> regionOf(event)?.let { event to it.at(level) } }
                    .groupBy({ (_, name) -> name }, { (event, _) -> event })
                    .map { (name, items) ->
                        RegionRow(
                            name,
                            sightings = items.count { it.kind == EventKind.Sighting },
                            purchases = items.count { it.kind == EventKind.Purchase },
                            places = items.map(::placeKey).distinct().size,
                        )
                    }
                    .sortedWith(compareBy<RegionRow> { it.name == null }.thenByDescending { it.count }.thenBy { it.name })
            }

            return RegionChart(
                area = area,
                level = level,
                rows = rows,
                unmapped = events.count { it.lat == null || it.lng == null },
                pending = events.count { it.lat != null && it.lng != null && regionOf(it) == null },
            )
        }

        private fun placeKey(event: ExploreEvent) = when {
            !event.place.isNullOrBlank() -> "name:" + event.place.trim().lowercase()
            event.lat != null && event.lng != null -> String.format(Locale.ROOT, "cell:%.3f,%.3f", event.lat, event.lng)
            else -> "unknown"
        }

        /** One marker per place: grouped by (case-insensitive) name; unnamed ones by ~100 m cell. [events] newest first. */
        private fun groupByPlace(events: List<ExploreEvent>, texts: Texts): List<ExplorePlace> = events
            .groupBy(::placeKey)
            .map { (key, items) ->
                val located = items.filter { it.lat != null && it.lng != null }
                // Most used spelling of the name; on a tie, the one typed first.
                val spellings = items.asReversed().mapNotNull { it.place?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
                val top = spellings.maxOfOrNull { it.value }

                ExplorePlace(
                    key = key,
                    name = spellings.entries.firstOrNull { it.value == top }?.key
                        ?: texts(if (key == "unknown") R.string.place_unnamed else R.string.place_unnamed_spot),
                    lat = located.takeIf { it.isNotEmpty() }?.let { round6(it.map { e -> e.lat!! }.average()) },
                    lng = located.takeIf { it.isNotEmpty() }?.let { round6(it.map { e -> e.lng!! }.average()) },
                    count = items.size,
                    sightings = items.count { it.kind == EventKind.Sighting },
                    purchases = items.count { it.kind == EventKind.Purchase },
                    visits = items.map { it.date }.distinct().size,
                    first = items.last().date,
                    last = items.first().date,
                    items = items.take(ExplorePlace.MAX_ITEMS),
                )
            }
            .sortedByDescending { it.last }

        private fun SightingEventRow.toEvent() = ExploreEvent(
            kind = EventKind.Sighting,
            id = "s$id",
            diecastId = diecastId,
            name = name,
            brand = brand,
            photoFile = photoFile,
            place = place,
            lat = latitude,
            lng = longitude,
            date = seenOn,
            price = price,
        )

        private fun PurchaseEventRow.toEvent(zone: ZoneId) = ExploreEvent(
            kind = EventKind.Purchase,
            id = "p$id",
            diecastId = id,
            name = name,
            brand = brand,
            photoFile = photoFile,
            place = place,
            lat = latitude,
            lng = longitude,
            date = purchaseDate ?: Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate(),
            price = price,
        )
    }
}
