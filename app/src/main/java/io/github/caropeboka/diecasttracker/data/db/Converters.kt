package io.github.caropeboka.diecasttracker.data.db

import androidx.room.TypeConverter
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ValueSource
import java.time.LocalDate

/** Enums are stored by the web app's keys, dates as ISO `yyyy-MM-dd` (sorts correctly as text). */
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
}
