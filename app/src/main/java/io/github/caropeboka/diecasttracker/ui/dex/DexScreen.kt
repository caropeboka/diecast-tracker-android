package io.github.caropeboka.diecasttracker.ui.dex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.dex.CatalogSummary
import io.github.caropeboka.diecasttracker.data.dex.ImageWork
import io.github.caropeboka.diecasttracker.data.dex.SyncProgress
import io.github.caropeboka.diecasttracker.data.dex.WikiSource
import io.github.caropeboka.diecasttracker.data.dex.YearResult
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.components.ChoiceInput
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** "Dex" (web: `dex/index.tsx`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexScreen(
    onTab: (MainTab) -> Unit,
    onOpen: (Long) -> Unit,
    onMatches: () -> Unit,
    viewModel: DexViewModel = viewModel(factory = DexViewModel.Factory),
) {
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val reviewCount by viewModel.reviewCount.collectAsStateWithLifecycle()
    val missing by viewModel.missingImages.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<CatalogEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Dex") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainNavigationBar(MainTab.Dex, onTab) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val groups = brands
        if (groups == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    "Seberapa banyak rilisan yang sudah kamu temui dan miliki, per tahun dan per seri.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )
            }
            if (reviewCount > 0) item { ReviewBanner(reviewCount, onMatches) }
            // During a sync the photos are still being looked up, and the download waits for the sync, not the network.
            if ((missing > 0 || images == ImageWork.Running) && sync !is SyncProgress.Running) {
                item { ImagesBanner(missing, images, cellular, viewModel::setCellular, viewModel::downloadNow) }
            }

            if (groups.isEmpty()) {
                item { EmptyDex() }
            } else {
                items(groups, key = { it.brand }) { group ->
                    SectionCard(null) {
                        SubHeading(group.brand)
                        DexMeter(group.counts)
                        DexLegend(group.counts)
                        group.catalogs.forEach { summary ->
                            HorizontalDivider(color = Garage.Border)
                            CatalogRow(summary, onOpen = { onOpen(summary.catalog.id) }, onResync = { viewModel.resync(summary.catalog) }, onDelete = { deleting = summary.catalog })
                        }
                    }
                }
            }

            item { SyncCard(viewModel) }
        }
    }

    deleting?.let { catalog ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus ${catalog.name}?") },
            text = { Text("Daftarnya dan foto-fotonya dihapus dari HP. Diecast di koleksimu tetap ada, hanya tautannya ke Dex ini yang lepas.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.delete(catalog)
                }) { Text("Hapus", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun ReviewBanner(count: Int, onMatches: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Garage.Brand.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, Garage.Brand.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onMatches),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_auto_fix_high), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("$count diecast perlu dicek", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Konfirmasi yang dicocokkan otomatis, pilih saran, dan perbaiki tahun yang keliru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            Text("Cocokkan", style = MaterialTheme.typography.labelLarge, color = Garage.Brand)
        }
    }
}

@Composable
private fun ImagesBanner(missing: Int, work: ImageWork, cellular: Boolean, onCellular: (Boolean) -> Unit, onDownload: () -> Unit) {
    val downloading = work == ImageWork.Running
    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (downloading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
            }
            Text(
                when {
                    downloading -> "Mengunduh foto katalog… $missing lagi"
                    work == ImageWork.Retrying -> "$missing foto katalog belum terunduh, dicoba lagi otomatis."
                    cellular -> "$missing foto katalog menunggu koneksi."
                    else -> "$missing foto katalog menunggu Wi-Fi."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!downloading) {
                TextButton(onClick = onDownload, shape = MaterialTheme.shapes.medium) { Text("Coba lagi") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pakai data seluler juga", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Sekitar 5–8 MB per tahun. Mati: foto hanya diunduh lewat Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            Switch(checked = cellular, onCheckedChange = onCellular)
        }
    }
}

@Composable
private fun EmptyDex() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_collections_bookmark), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(40.dp))
        SubHeading("Dex masih kosong")
        Text(
            "Sinkronkan daftar rilisan Hot Wheels atau Matchbox di bawah. Koleksimu langsung dicocokkan, dan kamu bisa melihat " +
                "berapa yang sudah ditemui dan dimiliki.",
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CatalogRow(summary: CatalogSummary, onOpen: () -> Unit, onResync: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val counts = summary.counts

    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.catalog.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DexMeter(counts)
            Text(
                "dimiliki ${counts.caught} · dilihat ${counts.seen} dari ${counts.total}" + if (counts.wanted > 0) " · ${counts.wanted} diincar" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu ${summary.catalog.name}", tint = Garage.MutedForeground)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (WikiSource.fromKey(summary.catalog.source) != null) {
                    DropdownMenuItem(
                        text = { Text("Sinkron ulang") },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
                        onClick = {
                            menu = false
                            onResync()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Hapus dari Dex", color = Garage.Loss) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = Garage.Loss) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun SyncCard(viewModel: DexViewModel) {
    val source by viewModel.source.collectAsStateWithLifecycle()
    val from by viewModel.fromYear.collectAsStateWithLifecycle()
    val to by viewModel.toYear.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val running = sync is SyncProgress.Running
    val years = viewModel.years(source)

    SectionCard("Sinkron katalog") {
        Text(
            "Ambil daftar rilisan resmi per tahun dari Hot Wheels Wiki atau Matchbox Wiki (CC BY-SA). Sinkron ulang tahun yang " +
                "sama memperbarui daftarnya tanpa melepas tautan koleksimu.",
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
        SourceToggle(source, enabled = !running, onSelect = viewModel::setSource)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceInput(from, years, Int::toString, viewModel::setFrom, "Dari", Modifier.weight(1f))
            ChoiceInput(to, years, Int::toString, viewModel::setTo, "Sampai", Modifier.weight(1f))
        }
        Button(onClick = viewModel::startSync, enabled = !running, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            IconLabel(R.drawable.ic_sync, if (from == to) "Sinkron $from" else "Sinkron $from–$to (${to - from + 1} tahun)")
        }

        when (val state = sync) {
            is SyncProgress.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    state.year?.let { "Menyinkron $it (${state.done + 1} dari ${state.total})…" } ?: "Menunggu koneksi internet…",
                    style = MaterialTheme.typography.bodySmall,
                )
                // One year at a time: a range moves the bar, a single year just shows activity.
                if (state.total > 1) {
                    LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is SyncProgress.Finished -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.results.forEach { YearLine(state.source, it) }
            }
            SyncProgress.Idle -> Unit
        }
    }
}

@Composable
private fun SourceToggle(selected: WikiSource, enabled: Boolean, onSelect: (WikiSource) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WikiSource.entries.forEach { source ->
            val active = source == selected
            val shape = RoundedCornerShape(8.dp)
            Text(
                source.brand,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .then(if (active) Modifier.background(Garage.Card, shape).border(1.dp, Garage.Brand.copy(alpha = 0.5f), shape) else Modifier)
                    .selectable(selected = active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(source) })
                    .padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun YearLine(source: WikiSource?, result: YearResult) {
    val style = MaterialTheme.typography.bodySmall
    val label = "${source?.brand ?: ""} ${result.year}".trim()
    if (result.error != null) {
        Text("$label: ${result.error}", style = style, color = Garage.Loss)
    } else {
        Text(
            // "New" only on a re-sync: on the first one everything is.
            "$label: ${result.items} rilisan" +
                (if (result.added in 1..<result.items) ", ${result.added} baru" else "") +
                (if (result.removed > 0) ", ${result.removed} dihapus" else "") +
                (if (result.linked > 0) " · ${result.linked} diecast tertaut" else ""),
            style = style,
            color = Color.Unspecified,
        )
    }
}
