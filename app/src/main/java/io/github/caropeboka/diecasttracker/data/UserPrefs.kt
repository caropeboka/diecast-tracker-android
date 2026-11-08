package io.github.caropeboka.diecasttracker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.caropeboka.diecasttracker.data.backup.BackupTimes
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.util.Currency
import io.github.caropeboka.diecasttracker.util.DateStyle
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

enum class CollectionLayout { Grid, List }

private val Context.prefs by preferencesDataStore("prefs")

/** Small per-device choices that should survive restarts. */
class UserPrefs(context: Context) {
    private val store = context.prefs

    val layout: Flow<CollectionLayout> = store.data.map { prefs ->
        CollectionLayout.entries.find { it.name == prefs[LAYOUT] } ?: CollectionLayout.Grid
    }

    val sort: Flow<CollectionSort> = store.data.map { CollectionSort.fromKey(it[SORT]) }

    suspend fun setLayout(layout: CollectionLayout) {
        store.edit { it[LAYOUT] = layout.name }
    }

    suspend fun setSort(sort: CollectionSort) {
        store.edit { it[SORT] = sort.key }
    }

    /** Dex thumbnails may be downloaded over mobile data too, not only Wi-Fi (off by default). */
    val dexCellular: Flow<Boolean> = store.data.map { it[DEX_CELLULAR] ?: false }

    suspend fun setDexCellular(allowed: Boolean) {
        store.edit { it[DEX_CELLULAR] = allowed }
    }

    /** The bulk scan queue's reading is paused. */
    val bulkScanPaused: Flow<Boolean> = store.data.map { it[BULK_SCAN_PAUSED] ?: false }

    suspend fun setBulkScanPaused(paused: Boolean) {
        store.edit { it[BULK_SCAN_PAUSED] = paused }
    }

    /** When the last backup was written on this phone (epoch millis), and until when its reminder is put off. */
    val backupTimes: Flow<BackupTimes> = store.data.map { BackupTimes(it[LAST_BACKUP], it[BACKUP_SNOOZED_UNTIL]) }

    suspend fun setLastBackup(at: Long) {
        store.edit { it[LAST_BACKUP] = at }
    }

    suspend fun snoozeBackupReminder(until: Long) {
        store.edit { it[BACKUP_SNOOZED_UNTIL] = until }
    }

    /** The last place chosen on the map or by GPS: "where I'm hunting now" (web: Cek koleksi's saved place). */
    suspend fun rememberPlace(place: PlaceValue) {
        store.edit { it[HUNT_PLACE] = Json.encodeToString(place) }
    }

    /** That place while its point is fresh (under 3 hours): new diecasts and sightings start there. */
    suspend fun recentPlace(now: Long = System.currentTimeMillis()): PlaceValue? {
        val json = store.data.first()[HUNT_PLACE] ?: return null
        val place = runCatching { Json.decodeFromString<PlaceValue>(json) }.getOrNull() ?: return null
        return place.takeIf { it.name.isNotBlank() && it.isFresh(now) }
    }

    /** What prices are shown in; they stay stored in Rupiah. */
    val currency: Flow<Currency> = store.data.map { Currency.of(it[CURRENCY]) }

    suspend fun setCurrency(currency: Currency) {
        store.edit { it[CURRENCY] = currency.key }
    }

    val dateStyle: Flow<DateStyle> = store.data.map { DateStyle.of(it[DATE_STYLE]) }

    suspend fun setDateStyle(style: DateStyle) {
        store.edit { it[DATE_STYLE] = style.key }
    }

    /** The last exchange rates fetched, kept for when there's no connection. */
    val rates: Flow<ExchangeRates?> = store.data.map { prefs ->
        prefs[RATES]?.let { runCatching { Json.decodeFromString<ExchangeRates>(it) }.getOrNull() }
    }

    suspend fun setRates(rates: ExchangeRates) {
        store.edit { it[RATES] = Json.encodeToString(rates) }
    }

    /**
     * Where the nanoblock dog stands on the 3D garage's desk: millimetres across from its left edge and in from its
     * back edge; null until it is moved.
     */
    val garageDog: Flow<Pair<Int, Int>?> = store.data.map { prefs ->
        prefs[GARAGE_DOG]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }

    suspend fun setGarageDog(x: Int, depth: Int) {
        store.edit { it[GARAGE_DOG] = "$x,$depth" }
    }

    /** How many cards a lane of the 3D garage's boards takes, as the collector set it. */
    val laneCards: Flow<Int> = store.data.map { (it[LANE_CARDS] ?: ShelfLayout.LANE_CARDS).coerceIn(ShelfLayout.LANE_CARDS_RANGE) }

    suspend fun setLaneCards(cards: Int) {
        store.edit { it[LANE_CARDS] = cards.coerceIn(ShelfLayout.LANE_CARDS_RANGE) }
    }

    /** The loose cars the 3D garage shows facing the other way than in their photo, by diecast id. */
    val garageFlipped: Flow<Set<Long>> = store.data.map { prefs -> prefs[GARAGE_FLIPPED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun setGarageFlipped(id: Long, flipped: Boolean) {
        store.edit { prefs ->
            val now = prefs[GARAGE_FLIPPED].orEmpty()
            prefs[GARAGE_FLIPPED] = if (flipped) now + id.toString() else now - id.toString()
        }
    }

    /** The first-run tour was finished or skipped. */
    val tourDone: Flow<Boolean> = store.data.map { it[TOUR_DONE] ?: false }

    suspend fun setTourDone(done: Boolean) {
        store.edit { it[TOUR_DONE] = done }
    }

    private companion object {
        val CURRENCY = stringPreferencesKey("currency")
        val DATE_STYLE = stringPreferencesKey("date_style")
        val RATES = stringPreferencesKey("exchange_rates")
        val TOUR_DONE = booleanPreferencesKey("tour_done")
        val LAYOUT = stringPreferencesKey("collection_layout")
        val SORT = stringPreferencesKey("collection_sort")
        val HUNT_PLACE = stringPreferencesKey("hunt_place")
        val DEX_CELLULAR = booleanPreferencesKey("dex_cellular")
        val LAST_BACKUP = longPreferencesKey("last_backup_at")
        val BACKUP_SNOOZED_UNTIL = longPreferencesKey("backup_snoozed_until")
        val BULK_SCAN_PAUSED = booleanPreferencesKey("bulk_scan_paused")
        val GARAGE_DOG = stringPreferencesKey("garage_dog")
        val LANE_CARDS = intPreferencesKey("garage_lane_cards")
        val GARAGE_FLIPPED = stringSetPreferencesKey("garage_flipped")
    }
}
