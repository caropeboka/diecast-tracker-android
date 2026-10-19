package io.github.caropeboka.diecasttracker.data.explore

import io.github.caropeboka.diecasttracker.data.db.PurchaseEventRow
import io.github.caropeboka.diecasttracker.data.db.SightingEventRow
import io.github.caropeboka.diecasttracker.data.places.round6
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class ExploreType(val key: String, val label: String) {
    All("all", "Semua"),
    Sighting("sighting", "Temuan"),
    Purchase("purchase", "Pembelian");

    companion object {
        fun fromKey(key: String?): ExploreType = entries.find { it.key == key } ?: All
    }
}

enum class ExploreRange(val key: String, val label: String, val days: Long?) {
    All("all", "Semua", null),
    Month("30", "30 hari", 30),
    Quarter("90", "3 bulan", 90),
    Year("365", "1 tahun", 365);

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

data class TimelineDay(val date: LocalDate, val places: List<TimelinePlace>)

/** "Peta jelajah": every place where diecasts were spotted or bought, when, and what was there (web: `ExploreController`). */
data class Exploration(
    val places: List<ExplorePlace> = emptyList(),
    val stats: ExploreStats = ExploreStats(),
    val timeline: List<TimelineDay> = emptyList(),
) {
    companion object {
        const val UNNAMED = "Tanpa nama tempat"
        private const val TIMELINE_DAYS = 30

        fun build(
            sightings: List<SightingEventRow>,
            purchases: List<PurchaseEventRow>,
            type: ExploreType,
            range: ExploreRange,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Exploration {
            val since = range.days?.let { today.minusDays(it) }

            val sightingEvents = if (type == ExploreType.Purchase) emptyList() else sightings.map { it.toEvent() }
            // Dated by purchase date, or when it was added.
            val purchaseEvents = if (type == ExploreType.Sighting) emptyList() else purchases.map { it.toEvent(zone) }

            val events = (sightingEvents + purchaseEvents)
                .filter { since == null || it.date >= since }
                .sortedByDescending { it.date }
            val places = groupByPlace(events)

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
                    .take(TIMELINE_DAYS)
                    .map { (date, day) ->
                        TimelineDay(
                            date,
                            day.groupBy { it.place ?: UNNAMED }.map { (name, items) ->
                                TimelinePlace(
                                    name,
                                    sightings = items.count { it.kind == EventKind.Sighting },
                                    purchases = items.count { it.kind == EventKind.Purchase },
                                )
                            },
                        )
                    },
            )
        }

        /** One marker per place: grouped by (case-insensitive) name; unnamed ones by ~100 m cell. [events] newest first. */
        private fun groupByPlace(events: List<ExploreEvent>): List<ExplorePlace> = events
            .groupBy { event ->
                when {
                    !event.place.isNullOrBlank() -> "name:" + event.place.trim().lowercase()
                    event.lat != null && event.lng != null -> String.format(Locale.ROOT, "cell:%.3f,%.3f", event.lat, event.lng)
                    else -> "unknown"
                }
            }
            .map { (key, items) ->
                val located = items.filter { it.lat != null && it.lng != null }
                // Most used spelling of the name; on a tie, the one typed first.
                val spellings = items.asReversed().mapNotNull { it.place?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
                val top = spellings.maxOfOrNull { it.value }

                ExplorePlace(
                    key = key,
                    name = spellings.entries.firstOrNull { it.value == top }?.key
                        ?: if (key == "unknown") UNNAMED else "Titik tanpa nama",
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
