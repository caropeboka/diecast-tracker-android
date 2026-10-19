package io.github.caropeboka.diecasttracker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

    private companion object {
        val LAYOUT = stringPreferencesKey("collection_layout")
        val SORT = stringPreferencesKey("collection_sort")
    }
}
