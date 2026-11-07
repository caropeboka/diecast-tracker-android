package io.github.caropeboka.diecasttracker.ui.explore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.db.PurchaseEventRow
import io.github.caropeboka.diecasttracker.data.db.SightingEventRow
import io.github.caropeboka.diecasttracker.data.explore.Exploration
import io.github.caropeboka.diecasttracker.data.explore.ExploreRange
import io.github.caropeboka.diecasttracker.data.explore.ExploreType
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.places.Nominatim
import io.github.caropeboka.diecasttracker.data.places.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** Looking up the regions of the map's points: [done] of [total] this round; [offline] when OpenStreetMap was out of reach. */
data class RegionLookup(val done: Int = 0, val total: Int = 0, val offline: Boolean = false) {
    val running get() = done < total && !offline
}

class ExploreViewModel(
    repository: DiecastRepository,
    private val saved: SavedStateHandle,
    private val texts: Texts,
    private val osm: Nominatim,
) : ViewModel() {
    val type: StateFlow<ExploreType> = saved.getStateFlow(TYPE, ExploreType.All.key)
        .map(ExploreType::fromKey)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExploreType.All)

    val range: StateFlow<ExploreRange> = saved.getStateFlow(RANGE, ExploreRange.All.key)
        .map(ExploreRange::fromKey)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExploreRange.All)

    /** The region everything is narrowed to: names from the province down, empty for all. */
    val area: StateFlow<List<String>> = saved.getStateFlow<List<String>>(AREA, arrayListOf())

    /** The marker whose details show under the map. */
    val selected: StateFlow<String?> = saved.getStateFlow(SELECTED, null)

    private val events = repository.observeExploreEvents().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Per [Region.cell]: what's been found so far. */
    private val regions = MutableStateFlow<Map<String, Region>>(emptyMap())
    private val retries = MutableStateFlow(0)

    private val _lookup = MutableStateFlow(RegionLookup())
    val lookup: StateFlow<RegionLookup> = _lookup.asStateFlow()

    /** Null while loading. */
    val exploration: StateFlow<Exploration?> = combine(events, type, range, regions, area) { (sightings, purchases), type, range, regions, area ->
        Exploration.build(sightings, purchases, type, range, LocalDate.now(), texts, regions = regions, area = area)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setType(type: ExploreType) {
        saved[TYPE] = type.key
    }

    fun setRange(range: ExploreRange) {
        saved[RANGE] = range.key
    }

    fun setArea(area: List<String>) {
        saved[AREA] = ArrayList(area)
    }

    fun select(key: String?) {
        saved[SELECTED] = key
    }

    /**
     * Finds the region of every point on the map while the screen is shown: the cached ones at once, the rest one by
     * one at OpenStreetMap's pace. Starts over when points are added or [retryRegions] is called.
     */
    suspend fun lookUpRegions() {
        combine(events.map(::points).distinctUntilChanged(), retries) { points, _ -> points }.collectLatest(::fill)
    }

    fun retryRegions() {
        retries.update { it + 1 }
    }

    private suspend fun fill(points: Map<String, Pair<Double, Double>>) {
        val missing = points.filterKeys { it !in regions.value }
        val cached = missing.mapNotNull { (cell, point) -> osm.cachedRegion(point.first, point.second)?.let { cell to it } }.toMap()
        if (cached.isNotEmpty()) regions.update { it + cached }

        val left = missing.filterKeys { it !in cached }
        _lookup.value = RegionLookup(total = left.size)
        for ((cell, point) in left) {
            val region = osm.region(point.first, point.second)
            if (region == null) {
                _lookup.update { it.copy(offline = true) }
                return
            }
            regions.update { it + (cell to region) }
            _lookup.update { it.copy(done = it.done + 1) }
        }
    }

    /** One point per cell. */
    private fun points(events: Pair<List<SightingEventRow>, List<PurchaseEventRow>>): Map<String, Pair<Double, Double>> = buildMap {
        val (sightings, purchases) = events
        sightings.forEach { if (it.latitude != null && it.longitude != null) putIfAbsent(Region.cell(it.latitude, it.longitude), it.latitude to it.longitude) }
        purchases.forEach { if (it.latitude != null && it.longitude != null) putIfAbsent(Region.cell(it.latitude, it.longitude), it.latitude to it.longitude) }
    }

    companion object {
        private const val TYPE = "type"
        private const val RANGE = "range"
        private const val AREA = "area"
        private const val SELECTED = "selected"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                ExploreViewModel(app.container.repository, createSavedStateHandle(), app.container.texts, app.container.nominatim)
            }
        }
    }
}
