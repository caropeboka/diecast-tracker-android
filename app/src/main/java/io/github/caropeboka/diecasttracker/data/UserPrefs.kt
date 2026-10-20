package io.github.caropeboka.diecasttracker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
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

    private companion object {
        val LAYOUT = stringPreferencesKey("collection_layout")
        val SORT = stringPreferencesKey("collection_sort")
        val HUNT_PLACE = stringPreferencesKey("hunt_place")
        val DEX_CELLULAR = booleanPreferencesKey("dex_cellular")
    }
}
