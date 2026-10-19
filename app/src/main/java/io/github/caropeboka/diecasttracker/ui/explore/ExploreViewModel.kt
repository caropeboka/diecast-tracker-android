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
import io.github.caropeboka.diecasttracker.data.explore.Exploration
import io.github.caropeboka.diecasttracker.data.explore.ExploreRange
import io.github.caropeboka.diecasttracker.data.explore.ExploreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class ExploreViewModel(repository: DiecastRepository, private val saved: SavedStateHandle) : ViewModel() {
    val type: StateFlow<ExploreType> = saved.getStateFlow(TYPE, ExploreType.All.key)
        .map(ExploreType::fromKey)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExploreType.All)

    val range: StateFlow<ExploreRange> = saved.getStateFlow(RANGE, ExploreRange.All.key)
        .map(ExploreRange::fromKey)
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExploreRange.All)

    /** The marker whose details show under the map. */
    val selected: StateFlow<String?> = saved.getStateFlow(SELECTED, null)

    /** Null while loading. */
    val exploration: StateFlow<Exploration?> = combine(repository.observeExploreEvents(), type, range) { (sightings, purchases), type, range ->
        Exploration.build(sightings, purchases, type, range, LocalDate.now())
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setType(type: ExploreType) {
        saved[TYPE] = type.key
    }

    fun setRange(range: ExploreRange) {
        saved[RANGE] = range.key
    }

    fun select(key: String?) {
        saved[SELECTED] = key
    }

    companion object {
        private const val TYPE = "type"
        private const val RANGE = "range"
        private const val SELECTED = "selected"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                ExploreViewModel(app.container.repository, createSavedStateHandle())
            }
        }
    }
}
