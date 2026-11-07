package io.github.caropeboka.diecasttracker.ui.dex

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.dex.DexCounts
import io.github.caropeboka.diecasttracker.data.dex.DexEntry
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.ui.DexCatalogRoute
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

enum class EntryFilter(@StringRes val label: Int) {
    All(R.string.dex_filter_all),
    Missing(R.string.dex_filter_missing),
    Seen(R.string.dex_state_seen),
    Caught(R.string.dex_state_owned),
    Wanted(R.string.dex_state_wanted);

    fun matches(entry: DexEntry) = when (this) {
        All -> true
        Missing -> !entry.state.seen
        Seen -> entry.state.seen
        Caught -> entry.state.caught
        Wanted -> entry.state.wanted
    }

    fun count(counts: DexCounts) = when (this) {
        All -> counts.total
        Missing -> counts.total - counts.seen
        Seen -> counts.seen
        Caught -> counts.caught
        Wanted -> counts.wanted
    }
}

/** One dex: its entries, by filter and search, as a grid or per series (web: `DexController::show`). */
class DexCatalogViewModel(private val saved: SavedStateHandle, dex: DexRepository) : ViewModel() {
    private val route = saved.toRoute<DexCatalogRoute>()
    val catalogId = route.id

    /** Opened for one entry (a diecast's link): its sheet shows at once, and closing it leaves the dex. */
    val forEntry = route.number != null

    val catalog: StateFlow<CatalogEntity?> = dex.observeCatalog(catalogId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null while loading. */
    val entries: StateFlow<List<DexEntry>?> = dex.observeEntries(catalogId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val filter: StateFlow<String> = saved.getStateFlow(FILTER, EntryFilter.All.name)
    val query: StateFlow<String> = saved.getStateFlow(QUERY, "")
    val bySeries: StateFlow<Boolean> = saved.getStateFlow(BY_SERIES, false)

    /** The entry whose sheet is open. */
    val open: StateFlow<String?> = saved.getStateFlow(OPEN, null)

    fun setFilter(filter: EntryFilter) {
        saved[FILTER] = filter.name
    }

    fun setQuery(text: String) {
        saved[QUERY] = text
    }

    fun setBySeries(on: Boolean) {
        saved[BY_SERIES] = on
    }

    fun open(number: String?) {
        saved[OPEN] = number
    }

    init {
        // Once: after that the open sheet is kept like any other.
        if (forEntry && saved.get<Boolean>(ENTRY_SHOWN) != true) {
            saved[ENTRY_SHOWN] = true
            saved[OPEN] = route.number
        }
    }

    companion object {
        private const val FILTER = "filter"
        private const val QUERY = "query"
        private const val BY_SERIES = "by_series"
        private const val OPEN = "open"
        private const val ENTRY_SHOWN = "entry_shown"

        fun visible(entries: List<DexEntry>, filter: EntryFilter, query: String): List<DexEntry> {
            val needle = query.trim().lowercase()
            return entries.filter { entry ->
                filter.matches(entry) && (
                    needle.isEmpty() ||
                        needle in entry.name.lowercase() ||
                        needle in entry.number.lowercase() ||
                        needle in entry.series.orEmpty().lowercase()
                    )
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                DexCatalogViewModel(createSavedStateHandle(), app.container.dex)
            }
        }
    }
}
