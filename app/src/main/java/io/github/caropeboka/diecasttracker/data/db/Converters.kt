package io.github.caropeboka.diecasttracker.data.db

import androidx.room.TypeConverter
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.price.PriceAccuracy
import io.github.caropeboka.diecasttracker.data.price.PriceListing
import io.github.caropeboka.diecasttracker.data.price.PriceStats
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Enums are stored by the web app's keys, dates as ISO `yyyy-MM-dd` (sorts correctly as text), a price check's
 * readings as JSON.
 */
class Converters {
    @TypeConverter fun rarityToKey(value: Rarity): String = value.key

    @TypeConverter fun rarityFromKey(key: String): Rarity = Rarity.fromKey(key) ?: Rarity.Regular

    @TypeConverter fun packagingToKey(value: Packaging): String = value.key

    @TypeConverter fun packagingFromKey(key: String): Packaging = Packaging.fromKey(key) ?: Packaging.Carded

    @TypeConverter fun conditionToKey(value: Condition?): String? = value?.key

    @TypeConverter fun conditionFromKey(key: String?): Condition? = Condition.fromKey(key)

    @TypeConverter fun statusToKey(value: DiecastStatus): String = value.key

    @TypeConverter fun statusFromKey(key: String): DiecastStatus = DiecastStatus.fromKey(key) ?: DiecastStatus.Owned

    @TypeConverter fun sourceToKey(value: ValueSource?): String? = value?.key

    @TypeConverter fun sourceFromKey(key: String?): ValueSource? = ValueSource.fromKey(key)

    @TypeConverter fun matchToKey(value: CatalogMatch?): String? = value?.key

    @TypeConverter fun matchFromKey(key: String?): CatalogMatch? = CatalogMatch.fromKey(key)

    @TypeConverter fun dateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun dateFromText(text: String?): LocalDate? = text?.let(LocalDate::parse)

    // Price checks: what was read from the screenshots, as JSON (web: JSON columns too).

    @TypeConverter fun accuracyToKey(value: PriceAccuracy): String = value.key

    @TypeConverter fun accuracyFromKey(key: String): PriceAccuracy = PriceAccuracy.fromKey(key)

    @TypeConverter fun listingsToJson(value: List<PriceListing>): String = JSON.encodeToString(value)

    @TypeConverter fun listingsFromJson(text: String): List<PriceListing> = JSON.decodeFromString(text)

    @TypeConverter fun statsToJson(value: PriceStats?): String? = value?.let { JSON.encodeToString(it) }

    @TypeConverter fun statsFromJson(text: String?): PriceStats? = text?.let { JSON.decodeFromString<PriceStats>(it) }

    @TypeConverter fun textsToJson(value: List<String>): String = JSON.encodeToString(value)

    @TypeConverter fun textsFromJson(text: String): List<String> = JSON.decodeFromString(text)

    // Bulk scan queue

    @TypeConverter fun scanStatusToKey(value: ScanItemStatus): String = value.key

    @TypeConverter fun scanStatusFromKey(key: String): ScanItemStatus = ScanItemStatus.fromKey(key)

    @TypeConverter fun scanFieldsToJson(value: ScanFields?): String? = value?.let { JSON.encodeToString(it) }

    /** Unreadable (an older shape) counts as not read yet. */
    @TypeConverter fun scanFieldsFromJson(text: String?): ScanFields? =
        text?.let { runCatching { JSON.decodeFromString<ScanFields>(it) }.getOrNull() }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
