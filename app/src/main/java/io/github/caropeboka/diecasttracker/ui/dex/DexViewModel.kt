package io.github.caropeboka.diecasttracker.ui.dex

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.dex.CatalogSummary
import io.github.caropeboka.diecasttracker.data.dex.DexCounts
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.DexSync
import io.github.caropeboka.diecasttracker.data.dex.DexWork
import io.github.caropeboka.diecasttracker.data.dex.ImageWork
import io.github.caropeboka.diecasttracker.data.dex.SyncProgress
import io.github.caropeboka.diecasttracker.data.dex.WikiSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A brand's dexes with their combined progress. */
data class BrandGroup(val brand: String, val counts: DexCounts, val catalogs: List<CatalogSummary>)

/** The Dex tab (web: `DexController::index`). */
class DexViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val dex: DexRepository,
    private val dexSync: DexSync,
    private val prefs: UserPrefs,
    private val messages: Messages,
) : ViewModel() {
    val currentYear = LocalDate.now().year

    /** Null while loading. */
    val brands: StateFlow<List<BrandGroup>?> = dex.observeCatalogs()
        .map { catalogs ->
            catalogs.groupBy { it.catalog.brand }.map { (brand, group) ->
                BrandGroup(brand, group.fold(DexCounts()) { sum, it -> sum + it.counts }, group)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val reviewCount = dex.observeReviewCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val missingImages = dex.observeMissingImages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val images = DexWork.observeImages(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageWork.Idle)
    val cellular = prefs.dexCellular.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val sync: StateFlow<SyncProgress> = DexWork.observeSync(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncProgress.Idle)

    val source: StateFlow<WikiSource> = saved.getStateFlow(SOURCE, WikiSource.HotWheels.key)
        .map { WikiSource.fromKey(it) ?: WikiSource.HotWheels }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WikiSource.HotWheels)
    val fromYear: StateFlow<Int> = saved.getStateFlow(FROM, currentYear)
    val toYear: StateFlow<Int> = saved.getStateFlow(TO, currentYear)

    fun years(source: WikiSource) = (currentYear downTo source.firstYear).toList()

    fun setSource(source: WikiSource) {
        saved[SOURCE] = source.key
    }

    fun setFrom(year: Int) {
        saved[FROM] = year
        if (year > toYear.value) saved[TO] = year
    }

    fun setTo(year: Int) {
        saved[TO] = year
        if (year < fromYear.value) saved[FROM] = year
    }

    /** Syncs the chosen years, newest first, in the background. */
    fun startSync() {
        DexWork.sync(app, source.value, (toYear.value downTo fromYear.value).toList())
    }

    fun resync(catalog: CatalogEntity) {
        val source = WikiSource.fromKey(catalog.source) ?: return
        DexWork.sync(app, source, listOfNotNull(catalog.year))
    }

    fun delete(catalog: CatalogEntity) {
        viewModelScope.launch {
            dexSync.delete(catalog.id)
            messages.show("${catalog.name} dihapus dari Dex.")
        }
    }

    fun setCellular(allowed: Boolean) {
        viewModelScope.launch {
            prefs.setDexCellular(allowed)
            // The waiting download job is rescheduled with the new network rule.
            if (missingImages.value > 0) DexWork.downloadImages(app, allowed, replace = true)
        }
    }

    fun downloadNow() {
        viewModelScope.launch { DexWork.downloadImages(app, prefs.dexCellular.first(), replace = true) }
    }

    companion object {
        private const val SOURCE = "source"
        private const val FROM = "from"
        private const val TO = "to"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { DexViewModel(app, createSavedStateHandle(), dex, dexSync, prefs, messages) }
            }
        }
    }
}
