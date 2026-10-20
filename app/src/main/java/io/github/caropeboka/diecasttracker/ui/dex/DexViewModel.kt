package io.github.caropeboka.diecasttracker.ui.dex

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.COMMON_BRANDS
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.dex.CatalogField
import io.github.caropeboka.diecasttracker.data.dex.CatalogSummary
import io.github.caropeboka.diecasttracker.data.dex.DexCounts
import io.github.caropeboka.diecasttracker.data.dex.DexCustom
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.DexSync
import io.github.caropeboka.diecasttracker.data.dex.DexWork
import io.github.caropeboka.diecasttracker.data.dex.ImageWork
import io.github.caropeboka.diecasttracker.data.dex.SyncProgress
import io.github.caropeboka.diecasttracker.data.dex.WikiSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** A brand's dexes with their combined progress. */
data class BrandGroup(val brand: String, val counts: DexCounts, val catalogs: List<CatalogSummary>)

/** The Dex tab (web: `DexController::index`). */
class DexViewModel(
    private val app: Application,
    private val saved: SavedStateHandle,
    private val dex: DexRepository,
    private val dexSync: DexSync,
    private val custom: DexCustom,
    repository: DiecastRepository,
    private val prefs: UserPrefs,
    private val messages: Messages,
) : ViewModel() {
    val currentYear = LocalDate.now().year

    /** The collection's brands without a wiki list, first one prefilled (web: `collectionBrands`). */
    private val collectionBrands: StateFlow<List<String>> = repository.observeBrands()
        .map { brands -> brands.filterNot(::hasWikiList) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Brands for an own dex: those, then the usual ones. */
    val brandSuggestions: StateFlow<List<String>> = collectionBrands
        .map { (it + COMMON_BRANDS.filterNot(::hasWikiList)).distinctBy(String::lowercase) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var catalogDraft by mutableStateOf(saved.get<String>(CATALOG)?.let { Json.decodeFromString<CatalogDraft>(it) })
        private set
    var catalogErrors by mutableStateOf(emptyMap<CatalogField, String>())
        private set

    private val _opened = Channel<Long>(Channel.BUFFERED)

    /** A new own dex to open. */
    val opened: Flow<Long> = _opened.receiveAsFlow()

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

    // Own dexes (web: `CustomDexController::store` / `update`)

    fun newCatalog() = storeCatalogDraft(CatalogDraft(brand = collectionBrands.value.firstOrNull().orEmpty()))

    private fun hasWikiList(brand: String) = WikiSource.entries.any { it.brand.equals(brand.trim(), ignoreCase = true) }

    fun editCatalog(catalog: CatalogEntity) =
        storeCatalogDraft(CatalogDraft(catalog.id, catalog.brand, catalog.name, catalog.year?.toString().orEmpty()))

    fun updateCatalogDraft(field: CatalogField, change: (CatalogDraft) -> CatalogDraft) {
        val current = catalogDraft ?: return
        storeCatalogDraft(change(current))
        if (field in catalogErrors) catalogErrors = catalogErrors - field
    }

    fun closeCatalog() {
        storeCatalogDraft(null)
        catalogErrors = emptyMap()
    }

    fun saveCatalog() {
        val draft = catalogDraft ?: return
        viewModelScope.launch {
            catalogErrors = custom.checkCatalog(draft.brand, draft.name, draft.year, draft.id)
            if (catalogErrors.isNotEmpty()) return@launch

            if (draft.id == 0L) {
                val id = custom.create(draft.brand, draft.name, draft.year)
                closeCatalog()
                messages.show("Dex ${draft.name.trim()} dibuat. Sekarang isi daftarnya.")
                _opened.send(id)
            } else {
                custom.update(draft.id, draft.brand, draft.name, draft.year)
                closeCatalog()
                messages.show("Dex disimpan.")
            }
        }
    }

    private fun storeCatalogDraft(next: CatalogDraft?) {
        catalogDraft = next
        saved[CATALOG] = next?.let { Json.encodeToString(it) }
    }

    companion object {
        private const val SOURCE = "source"
        private const val FROM = "from"
        private const val TO = "to"
        private const val CATALOG = "catalog_draft"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { DexViewModel(app, createSavedStateHandle(), dex, dexSync, dexCustom, repository, prefs, messages) }
            }
        }
    }
}
