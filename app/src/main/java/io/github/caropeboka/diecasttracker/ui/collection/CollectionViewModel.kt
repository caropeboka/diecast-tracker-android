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
import io.github.caropeboka.diecasttracker.data.MissingField
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.backup.BackupReminder
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
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

/** [daysSinceLast]: null when this phone never made a backup. */
data class BackupDue(val daysSinceLast: Long?)

/** The collection with these filters (others cleared), opened from elsewhere; [sort] null keeps the remembered one. */
data class CollectionLink(
    val search: String = "",
    val brand: String? = null,
    val status: DiecastStatus? = null,
    val rarity: Rarity? = null,
    val missing: MissingField? = null,
    val sort: CollectionSort? = null,
    val make: String? = null,
    val scale: String? = null,
    val years: IntRange? = null,
    val rare: Boolean = false,
)

/** The filters only a link sets (each shown as a chip that clears it), and the rest of the menus'. */
private data class LinkFilters(val make: String?, val scale: String?, val years: IntRange?, val rare: Boolean)

private data class MoreFilters(val tag: String?, val missing: String?, val sort: CollectionSort, val links: LinkFilters)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CollectionViewModel(
    private val repository: DiecastRepository,
    private val prefs: UserPrefs,
    private val saved: SavedStateHandle,
    queue: ScanQueue,
) : ViewModel() {
    /** Photos in the bulk scan queue not saved yet (the banner). */
    val queued: StateFlow<Int> = queue.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** What the search field shows right away; the query follows it after a short pause. */
    val search: StateFlow<String> = saved.getStateFlow(SEARCH, "")

    private val links = combine(
        saved.getStateFlow<String?>(MAKE, null),
        saved.getStateFlow<String?>(SCALE, null),
        saved.getStateFlow<Int?>(YEAR_FROM, null),
        saved.getStateFlow<Int?>(YEAR_TO, null),
        saved.getStateFlow(RARE, false),
    ) { make, scale, from, to, rare ->
        LinkFilters(make, scale, if (from != null && to != null) from..to else null, rare)
    }

    private val filter = combine(
        search.debounce { if (it.isEmpty()) 0 else 300 }.onStart { emit(search.value) }.distinctUntilChanged(),
        saved.getStateFlow<String?>(BRAND, null),
        saved.getStateFlow<String?>(STATUS, null),
        saved.getStateFlow<String?>(RARITY, null),
        combine(saved.getStateFlow<String?>(TAG, null), saved.getStateFlow<String?>(MISSING, null), prefs.sort, links, ::MoreFilters),
    ) { search, brand, status, rarity, more ->
        CollectionFilter(
            search = search,
            brand = brand,
            status = DiecastStatus.fromKey(status),
            rarity = Rarity.fromKey(rarity),
            tag = more.tag,
            missing = MissingField.fromKey(more.missing),
            sort = more.sort,
            make = more.links.make,
            scale = more.links.scale,
            years = more.links.years,
            rare = more.links.rare,
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

    /** Null while no backup reminder is due. */
    val backupDue: StateFlow<BackupDue?> = combine(prefs.backupTimes, repository.observeCount()) { times, count ->
        val now = System.currentTimeMillis()
        BackupDue(times.last?.let { BackupReminder.daysSince(it, now) }).takeIf { BackupReminder.isDue(count, times, now) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun snoozeBackupReminder() {
        viewModelScope.launch {
            prefs.snoozeBackupReminder(System.currentTimeMillis() + BackupReminder.SNOOZE_DAYS * BackupReminder.DAY_MILLIS)
        }
    }

    fun setSearch(text: String) {
        saved[SEARCH] = text
    }

    fun setBrand(brand: String?) {
        saved[BRAND] = brand
    }

    fun setStatus(status: DiecastStatus?) {
        saved[STATUS] = status?.key
    }

    /** One rarity, or with [rare] every one but regular. */
    fun setRarity(rarity: Rarity?, rare: Boolean = false) {
        saved[RARITY] = rarity?.key
        saved[RARE] = rare && rarity == null
    }

    fun clearMake() {
        saved[MAKE] = null
    }

    fun clearScale() {
        saved[SCALE] = null
    }

    fun clearYears() {
        saved[YEAR_FROM] = null
        saved[YEAR_TO] = null
    }

    fun setTag(tag: String?) {
        saved[TAG] = tag
    }

    fun setMissing(missing: MissingField?) {
        saved[MISSING] = missing?.key
    }

    fun setSort(sort: CollectionSort) {
        viewModelScope.launch { prefs.setSort(sort) }
    }

    fun toggleLayout() {
        val next = if (state.value.layout == CollectionLayout.Grid) CollectionLayout.List else CollectionLayout.Grid
        viewModelScope.launch { prefs.setLayout(next) }
    }

    fun resetFilters() = show(CollectionLink())

    /** Replaces the search and filters, e.g. from a link on the dashboard; a [CollectionLink.sort] is remembered. */
    fun show(link: CollectionLink) {
        // Set, not remove(): removing a key detaches the StateFlows above.
        saved[SEARCH] = link.search
        saved[BRAND] = link.brand
        saved[STATUS] = link.status?.key
        saved[RARITY] = link.rarity?.key
        saved[TAG] = null
        saved[MISSING] = link.missing?.key
        saved[MAKE] = link.make
        saved[SCALE] = link.scale
        saved[YEAR_FROM] = link.years?.first
        saved[YEAR_TO] = link.years?.last
        saved[RARE] = link.rare
        link.sort?.let(::setSort)
    }

    companion object {
        private const val SEARCH = "search"
        private const val BRAND = "brand"
        private const val STATUS = "status"
        private const val RARITY = "rarity"
        private const val TAG = "tag"
        private const val MISSING = "missing"
        private const val MAKE = "make"
        private const val SCALE = "scale"
        private const val YEAR_FROM = "year_from"
        private const val YEAR_TO = "year_to"
        private const val RARE = "rare"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { CollectionViewModel(repository, prefs, createSavedStateHandle(), scanQueue) }
            }
        }
    }
}
