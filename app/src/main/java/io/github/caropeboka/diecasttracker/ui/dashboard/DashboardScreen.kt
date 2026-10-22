package io.github.caropeboka.diecasttracker.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CollectionSort
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MissingField
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.dashboard.BrandCount
import io.github.caropeboka.diecasttracker.data.dashboard.Completeness
import io.github.caropeboka.diecasttracker.data.dashboard.Count
import io.github.caropeboka.diecasttracker.data.dashboard.Dashboard
import io.github.caropeboka.diecasttracker.data.dashboard.DashboardStats
import io.github.caropeboka.diecasttracker.data.dashboard.RarityCount
import io.github.caropeboka.diecasttracker.data.dashboard.YearColumn
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.dex.CatalogSummary
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.collection.CollectionLink
import io.github.caropeboka.diecasttracker.ui.collection.DiecastCard
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.dex.DexLegend
import io.github.caropeboka.diecasttracker.ui.dex.DexMeter
import io.github.caropeboka.diecasttracker.ui.scan.QueueBanner
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatRupiah
import io.github.caropeboka.diecasttracker.util.formatRupiahCompact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

class DashboardViewModel(repository: DiecastRepository, dex: DexRepository, queue: ScanQueue) : ViewModel() {
    /** Null while loading; follows every change to the collection and the Dex. */
    val dashboard: StateFlow<Dashboard?> = combine(repository.observeDashboard(), dex.observeCatalogs(), Dashboard::build)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Photos in the bulk scan queue not saved yet (web: `pending_scans`). */
    val queued: StateFlow<Int> = queue.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { DashboardViewModel(repository, dex, scanQueue) }
            }
        }
    }
}

private val OWNED = CollectionLink(status = DiecastStatus.Owned)

/** "Garasi": the collection in numbers (web: `dashboard.tsx`). Links open the collection filtered to match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onTab: (MainTab) -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCatalog: (Long) -> Unit,
    onCollection: (CollectionLink) -> Unit,
    onAdd: () -> Unit,
    onBulkScan: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = DashboardViewModel.Factory),
) {
    val dashboard by viewModel.dashboard.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting(), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                        Heading("Garasi kamu")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainNavigationBar(MainTab.Garage, onTab) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val data = dashboard
        when {
            data == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            data.isEmpty && queued == 0 -> EmptyGarage(onAdd, Modifier.padding(padding))
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (queued > 0) item(key = "queue") { QueueBanner(queued, onBulkScan) }
                if (data.isEmpty) {
                    item(key = "empty") { EmptyGarage(onAdd) }
                    return@LazyColumn
                }
                item(key = "value") { ValueHero(data, onOpen, onCollection) }
                item(key = "stats") { StatTiles(data.stats, onCollection) }
                if (data.dex.isNotEmpty()) {
                    item(key = "dex") { DexPanel(data.dex, onOpenCatalog, onAll = { onTab(MainTab.Dex) }) }
                }
                item(key = "valuable") {
                    MostValuable(data.mostValuable, onOpen, onAll = { onCollection(OWNED.copy(sort = CollectionSort.Value)) })
                }
                item(key = "completeness") { CompletenessPanel(data.completeness, data.stats.uniqueItems, onCollection) }
                item(key = "brands") { BrandPanel(data.brands, data.stats.totalItems, onCollection) }
                item(key = "rarity") { RarityPanel(data.rarities, onCollection) }
                if (data.makes.isNotEmpty()) {
                    item(key = "makes") { MakePanel(data.makes, onCollection) }
                }
                if (data.years.isNotEmpty()) {
                    item(key = "years") { YearPanel(data.years, data.unknownYears) }
                }
                if (data.scales.size > 1) {
                    item(key = "scales") { ScalePanel(data.scales) }
                }
                if (data.topGainers.isNotEmpty()) {
                    item(key = "gainers") { GainersPanel(data.topGainers, onOpen) }
                }
                if (data.wishlist.isNotEmpty()) {
                    item(key = "wishlist") {
                        WishlistPanel(data.wishlist, data.stats.wishlist, onOpen, onAll = {
                            onCollection(CollectionLink(status = DiecastStatus.Wishlist))
                        })
                    }
                }
                item(key = "recent-heading") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubHeading("Baru ditambahkan", Modifier.weight(1f))
                        SeeAll { onCollection(CollectionLink()) }
                    }
                }
                items(data.recent.chunked(2), key = { row -> "recent-${row.first().id}" }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { DiecastCard(it, onClick = { onOpen(it.id) }, modifier = Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour < 11 -> "Selamat pagi"
        hour < 15 -> "Selamat siang"
        hour < 19 -> "Selamat sore"
        else -> "Selamat malam"
    }
}

@Composable
private fun EmptyGarage(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = Garage.MutedForeground.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
        Text("Garasimu masih kosong", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Angka dan grafik koleksimu muncul di sini begitu ada diecast yang dimiliki.",
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_add, "Tambah diecast")
        }
    }
}

/** The collection's market value, the gain against what it cost, and a collage of the best pieces. */
@Composable
private fun ValueHero(data: Dashboard, onOpen: (Long) -> Unit, onCollection: (CollectionLink) -> Unit) {
    val stats = data.stats

    Surface(shape = MaterialTheme.shapes.large, color = Garage.Card, border = BorderStroke(1.dp, Garage.Border)) {
        Column {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(width = 4.dp, height = 16.dp)
                            .background(Brush.verticalGradient(listOf(Garage.Brand, Garage.Brand2)), RoundedCornerShape(2.dp)),
                    )
                    Text("Nilai koleksi", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground, fontWeight = FontWeight.Medium)
                }
                Text(
                    if (stats.valuedItems > 0) formatRupiah(stats.totalValue) else "—",
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                )
                GainLine(stats) { onCollection(OWNED.copy(missing = MissingField.PurchasePrice)) }

                HorizontalDivider(color = Garage.Border, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
                    MiniStat("Rata-rata / diecast", stats.averageValue?.let(::formatRupiahCompact) ?: "—", Modifier.weight(1f))
                    VerticalDivider(color = Garage.Border)
                    MiniStat(
                        "Termahal",
                        data.mostValuable.firstOrNull()?.marketValue?.let(::formatRupiahCompact) ?: "—",
                        Modifier.weight(1f).padding(start = 12.dp),
                    )
                    VerticalDivider(color = Garage.Border)
                    MiniStat("Merek", stats.brands.toString(), Modifier.weight(1f).padding(start = 12.dp))
                }
                Text(
                    "Dihitung dari ${stats.valuedItems} dari ${stats.uniqueItems} diecast yang sudah punya harga pasaran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            if (data.showcase.isNotEmpty()) Collage(data.showcase, onOpen)
        }
    }
}

@Composable
private fun GainLine(stats: DashboardStats, onFillPrices: () -> Unit) {
    if (stats.gainItems == 0) {
        Column {
            Text(
                "Isi harga beli untuk melihat untung / rugi koleksimu.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )
            TextButton(onClick = onFillPrices, contentPadding = PaddingValues(0.dp)) { Text("Lengkapi harga beli →") }
        }
        return
    }

    val up = stats.valueGain >= 0
    val color = if (up) Garage.Gain else Garage.Loss
    val amount = (if (up) "+" else "−") + formatRupiah(abs(stats.valueGain)) +
        stats.gainPercent?.let { " (${if (it >= 0) "+" else ""}$it%)" }.orEmpty()

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            painterResource(R.drawable.ic_trending_up),
            contentDescription = if (up) "Naik" else "Turun",
            tint = color,
            modifier = Modifier.size(18.dp).scale(scaleX = 1f, scaleY = if (up) 1f else -1f),
        )
        Text(amount, style = MaterialTheme.typography.bodyMedium, color = color, fontWeight = FontWeight.Medium)
    }
    Text("dibanding harga beli", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Always a full block, kept low for a phone (the web's 2×2 and "one tall and two" fill the whole screen here):
 * 6 → 3×2, 3–5 → one row of 3, 2 → side by side, 1 → wide.
 */
@Composable
private fun Collage(showcase: List<DiecastListItem>, onOpen: (Long) -> Unit) {
    val gap = 4.dp

    @Composable
    fun Tile(item: DiecastListItem, modifier: Modifier) {
        DiecastPhoto(
            item.coverFile,
            item.name,
            modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onOpen(item.id) },
        )
    }

    @Composable
    fun Grid(items: List<DiecastListItem>, columns: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { Tile(it, Modifier.weight(1f).aspectRatio(1f)) }
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().background(Garage.Muted.copy(alpha = 0.4f)).padding(gap)) {
        val n = showcase.size
        when {
            n >= 6 -> Grid(showcase.take(6), 3)
            n >= 3 -> Grid(showcase.take(3), 3)
            n == 2 -> Grid(showcase, 2)
            else -> Tile(showcase[0], Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
    }
}

@Composable
private fun StatTiles(stats: DashboardStats, onCollection: (CollectionLink) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                R.drawable.ic_car,
                "Total diecast",
                "${stats.totalItems} pcs",
                "${stats.uniqueItems} model berbeda",
                Modifier.weight(1f).fillMaxHeight(),
            ) { onCollection(OWNED) }
            StatTile(
                R.drawable.ic_shopping_bag,
                "Total modal",
                if (stats.pricedItems > 0) formatRupiah(stats.totalSpent) else "—",
                "${stats.pricedItems} dari ${stats.uniqueItems} ada harga beli",
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                R.drawable.ic_sparkles,
                "Langka",
                stats.specialItems.toString(),
                if (stats.specialItems > 0) "${stats.superTreasureHunts} Super Treasure Hunt" else "Belum ada TH / STH / Chase",
                Modifier.weight(1f).fillMaxHeight(),
            )
            StatTile(
                R.drawable.ic_favorite,
                "Wishlist",
                stats.wishlist.toString(),
                when {
                    stats.spotted > 0 -> "diburu · ${stats.spotted} pernah ditemui"
                    stats.wishlist > 0 -> "diecast sedang diburu"
                    else -> "Tambahkan diecast buruanmu"
                },
                Modifier.weight(1f).fillMaxHeight(),
            ) { onCollection(CollectionLink(status = DiecastStatus.Wishlist)) }
        }
    }
}

@Composable
private fun DexPanel(dex: List<CatalogSummary>, onOpenCatalog: (Long) -> Unit, onAll: () -> Unit) {
    SectionCard("Dex", action = { SeeAll(onAll) }) {
        dex.forEach { summary ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onOpenCatalog(summary.catalog.id) }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(summary.catalog.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DexMeter(summary.counts)
                DexLegend(summary.counts)
            }
        }
    }
}

@Composable
private fun MostValuable(items: List<DiecastListItem>, onOpen: (Long) -> Unit, onAll: () -> Unit) {
    SectionCard("Paling berharga", action = if (items.isNotEmpty()) ({ SeeAll(onAll) }) else null) {
        if (items.isEmpty()) {
            Text(
                "Belum ada harga pasaran. Buka diecast → Cek harga pasaran untuk menghitungnya dari screenshot Tokopedia/Shopee.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )
            return@SectionCard
        }

        val max = items.first().marketValue?.coerceAtLeast(1L) ?: 1L
        items.forEachIndexed { index, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpen(item.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Garage.MutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(16.dp),
                )
                Thumb(item)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Text(formatRupiah(item.marketValue), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    subtitle(item)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Bar((item.marketValue ?: 0L).toFloat() / max, Garage.MeterBar, Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@DrawableRes
private fun MissingField.icon() = when (this) {
    MissingField.Photo -> R.drawable.ic_photo_camera
    MissingField.PurchasePrice -> R.drawable.ic_shopping_bag
    MissingField.MarketValue -> R.drawable.ic_trending_up
    MissingField.StorageLocation -> R.drawable.ic_grid_view
    MissingField.Tags -> R.drawable.ic_sell
}

@Composable
private fun CompletenessPanel(completeness: List<Completeness>, total: Int, onCollection: (CollectionLink) -> Unit) {
    SectionCard("Kelengkapan data") {
        completeness.forEach { (field, done) ->
            val complete = done >= total
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !complete) { onCollection(OWNED.copy(missing = field)) }
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(field.icon()), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
                    Text(field.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$done/$total", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
                    if (complete) {
                        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = "Lengkap", tint = Garage.Gain, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Lengkapi →", style = MaterialTheme.typography.bodySmall, color = Garage.Brand, fontWeight = FontWeight.Medium)
                    }
                }
                Bar(done.toFloat() / total.coerceAtLeast(1), Garage.MeterBar, track = Garage.MeterTrack)
            }
        }
    }
}

@Composable
private fun BrandPanel(brands: List<BrandCount>, totalItems: Int, onCollection: (CollectionLink) -> Unit) {
    var byValue by rememberSaveable { mutableStateOf(false) }
    val shown = brands.sortedByDescending { if (byValue) it.value else it.total.toLong() }.take(8)
    val max = shown.maxOfOrNull { if (byValue) it.value else it.total.toLong() }?.coerceAtLeast(1L) ?: 1L

    SectionCard("Per merek", action = { MeasureToggle(byValue) { byValue = it } }) {
        BarList(
            shown.map { brand ->
                BarRow(
                    key = brand.label,
                    label = brand.label,
                    display = if (byValue) formatRupiahCompact(brand.value) else "${brand.total} pcs",
                    fraction = (if (byValue) brand.value else brand.total.toLong()).toFloat() / max,
                    detail = if (byValue) {
                        "${brand.total} pcs"
                    } else {
                        "${(brand.total * 100f / totalItems.coerceAtLeast(1)).roundToInt()}% dari koleksi · nilai ${formatRupiah(brand.value)}"
                    },
                    onClick = brand.brand?.let { name -> { onCollection(OWNED.copy(brand = name)) } },
                )
            },
        )
    }
}

/** "Jumlah | Nilai". */
@Composable
private fun MeasureToggle(byValue: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .border(1.dp, Garage.Border, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        listOf(false to "Jumlah", true to "Nilai").forEach { (value, label) ->
            val selected = byValue == value
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) Garage.Foreground else Garage.MutedForeground,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (selected) Garage.Muted else Garage.Card)
                    .selectable(selected, role = Role.RadioButton) { onChange(value) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RarityPanel(rarities: List<RarityCount>, onCollection: (CollectionLink) -> Unit) {
    SectionCard("Kelangkaan") {
        if (rarities.none { it.rarity != Rarity.Regular }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painterResource(R.drawable.ic_sparkles),
                    contentDescription = null,
                    tint = Garage.MutedForeground.copy(alpha = 0.6f),
                    modifier = Modifier.size(32.dp),
                )
                Text("Semua masih regular", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Belum ada Treasure Hunt, Super TH, atau Chase. Semangat berburu! 🔥",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
            return@SectionCard
        }

        val max = rarities.maxOf { it.total }.coerceAtLeast(1)
        BarList(
            rarities.map { (rarity, total) ->
                BarRow(
                    key = rarity.key,
                    label = rarity.label,
                    display = "$total pcs",
                    fraction = total.toFloat() / max,
                    color = Garage.rarityColor(rarity) ?: RegularBar,
                    onClick = { onCollection(OWNED.copy(rarity = rarity)) },
                )
            },
        )
    }
}

@Composable
private fun MakePanel(makes: List<Count>, onCollection: (CollectionLink) -> Unit) {
    SectionCard("Pabrikan mobil") {
        CountBars(makes) { make -> onCollection(OWNED.copy(search = make)) }
    }
}

@Composable
private fun ScalePanel(scales: List<Count>) {
    SectionCard("Skala") { CountBars(scales, onClick = null) }
}

@Composable
private fun CountBars(counts: List<Count>, onClick: ((String) -> Unit)?) {
    val max = counts.maxOf { it.total }.coerceAtLeast(1)
    BarList(
        counts.map { count ->
            BarRow(
                key = count.label,
                label = count.label,
                display = "${count.total} pcs",
                fraction = count.total.toFloat() / max,
                onClick = onClick?.let { open -> { open(count.label) } },
            )
        },
    )
}

@Composable
private fun YearPanel(years: List<YearColumn>, unknownYears: Int) {
    SectionCard("Tahun rilis") {
        ColumnChart(years)
        if (unknownYears > 0) {
            Text(
                "$unknownYears diecast belum ada tahun rilisnya.",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
    }
}

@Composable
private fun GainersPanel(items: List<DiecastListItem>, onOpen: (Long) -> Unit) {
    SectionCard("Harga paling naik") {
        MiniList(items, onOpen) { item ->
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Naik ${formatRupiah((item.marketValue ?: 0L) - (item.purchasePrice ?: 0L))}"
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(painterResource(R.drawable.ic_trending_up), contentDescription = null, tint = Garage.Gain, modifier = Modifier.size(14.dp))
                Text(
                    formatRupiah((item.marketValue ?: 0L) - (item.purchasePrice ?: 0L)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.Gain,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun WishlistPanel(items: List<DiecastListItem>, count: Int, onOpen: (Long) -> Unit, onAll: () -> Unit) {
    SectionCard("Wishlist ($count)", action = { SeeAll(onAll) }) {
        MiniList(items, onOpen) { item ->
            item.targetPrice?.let {
                Text("≤ ${formatRupiah(it)}", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
    }
}
