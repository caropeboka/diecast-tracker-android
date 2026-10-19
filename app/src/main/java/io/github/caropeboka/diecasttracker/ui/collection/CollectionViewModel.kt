package io.github.caropeboka.diecasttracker.ui.collection

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.data.CollectionFilter
import io.github.caropeboka.diecasttracker.data.CollectionLayout
import io.github.caropeboka.diecasttracker.data.CollectionSort
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionUiState(
    /** Null while loading. */
    val items: List<DiecastListItem>? = null,
    val filter: CollectionFilter = CollectionFilter(),
    val brands: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val layout: CollectionLayout = CollectionLayout.Grid,
    /** Everything in the database, Ditemui included: tells "empty collection" from "nothing matches". */
    val total: Int = 0,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CollectionViewModel(
    private val repository: DiecastRepository,
    private val prefs: UserPrefs,
    private val saved: SavedStateHandle,
) : ViewModel() {
    /** What the search field shows right away; the query follows it after a short pause. */
    val search: StateFlow<String> = saved.getStateFlow(SEARCH, "")

    private val filter = combine(
        search.debounce { if (it.isEmpty()) 0 else 300 }.onStart { emit(search.value) }.distinctUntilChanged(),
        saved.getStateFlow<String?>(BRAND, null),
        saved.getStateFlow<String?>(STATUS, null),
        saved.getStateFlow<String?>(RARITY, null),
        combine(saved.getStateFlow<String?>(TAG, null), prefs.sort, ::Pair),
    ) { search, brand, status, rarity, (tag, sort) ->
        CollectionFilter(
            search = search,
            brand = brand,
            status = DiecastStatus.fromKey(status),
            rarity = Rarity.fromKey(rarity),
            tag = tag,
            sort = sort,
        )
    }.distinctUntilChanged()

    private val items = filter.flatMapLatest { filter ->
        repository.observeCollection(filter).map { it to filter }
    }

    val state: StateFlow<CollectionUiState> = combine(
        items,
        repository.observeBrands(),
        repository.observeTagNames(),
        prefs.layout,
        repository.observeCount(),
    ) { (items, filter), brands, tags, layout, total ->
        CollectionUiState(items, filter, brands, tags, layout, total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionUiState())

    fun setSearch(text: String) {
        saved[SEARCH] = text
    }

    fun setBrand(brand: String?) {
        saved[BRAND] = brand
    }

    fun setStatus(status: DiecastStatus?) {
        saved[STATUS] = status?.key
    }

    fun setRarity(rarity: Rarity?) {
        saved[RARITY] = rarity?.key
    }

    fun setTag(tag: String?) {
        saved[TAG] = tag
    }

    fun setSort(sort: CollectionSort) {
        viewModelScope.launch { prefs.setSort(sort) }
    }

    fun toggleLayout() {
        val next = if (state.value.layout == CollectionLayout.Grid) CollectionLayout.List else CollectionLayout.Grid
        viewModelScope.launch { prefs.setLayout(next) }
    }

    fun resetFilters() {
        // Set, not remove(): removing a key detaches the StateFlows above.
        saved[SEARCH] = ""
        listOf(BRAND, STATUS, RARITY, TAG).forEach { saved[it] = null }
    }

    companion object {
        private const val SEARCH = "search"
        private const val BRAND = "brand"
        private const val STATUS = "status"
        private const val RARITY = "rarity"
        private const val TAG = "tag"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                CollectionViewModel(app.container.repository, app.container.prefs, createSavedStateHandle())
            }
        }
    }
}
