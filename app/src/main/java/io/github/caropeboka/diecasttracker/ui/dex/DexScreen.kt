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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
    onBrand: (String) -> Unit,
    onMatches: () -> Unit,
    viewModel: DexViewModel = viewModel(factory = DexViewModel.Factory),
) {
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    val reviewCount by viewModel.reviewCount.collectAsStateWithLifecycle()
    val missing by viewModel.missingImages.collectAsStateWithLifecycle()
    val images by viewModel.images.collectAsStateWithLifecycle()
    val cellular by viewModel.cellular.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.opened.collect(onOpen) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.tab_dex)) },
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
                    stringResource(R.string.dex_intro),
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
                // A card per brand; its lists are on the brand's page.
                items(groups, key = { it.brand }) { group -> BrandCard(group, onOpen = { onBrand(group.brand) }) }
            }

            item { OwnDexCard(viewModel::newCatalog) }
            item { SyncCard(viewModel) }
        }
    }

    CatalogDialogs(viewModel, deleting = null, onDeleteDone = {})
}

/** One brand's dexes (its years and own lists) with their combined progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexBrandScreen(
    brand: String,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: DexViewModel = viewModel(factory = DexViewModel.Factory),
) {
    val brands by viewModel.brands.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<CatalogEntity?>(null) }
    val group = brands?.find { it.brand == brand }

    // Its last dex removed: nothing left to show.
    if (brands != null && group == null) LaunchedEffect(Unit) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(brand) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        if (group == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                SectionCard(null) {
                    DexMeter(group.counts)
                    DexLegend(group.counts)
                    Text(
                        pluralStringResource(R.plurals.dex_brand_lists, group.catalogs.size, group.catalogs.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
            }
            item {
                SectionCard(null) {
                    group.catalogs.forEachIndexed { index, summary ->
                        if (index > 0) HorizontalDivider(color = Garage.Border)
                        CatalogRow(
                            summary,
                            onOpen = { onOpen(summary.catalog.id) },
                            onResync = { viewModel.resync(summary.catalog) },
                            onEdit = { viewModel.editCatalog(summary.catalog) },
                            onDelete = { deleting = summary.catalog },
                        )
                    }
                }
            }
        }
    }

    CatalogDialogs(viewModel, deleting, onDeleteDone = { deleting = null })
}

/** A brand on the Dex tab: its progress over all its lists; tapping it opens them. */
@Composable
private fun BrandCard(group: BrandGroup, onOpen: () -> Unit) {
    SectionCard(
        group.brand,
        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable(onClickLabel = stringResource(R.string.dex_brand_open), onClick = onOpen),
        action = {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.dex_brand_open), style = MaterialTheme.typography.bodySmall)
            }
        },
    ) {
        DexMeter(group.counts)
        DexLegend(group.counts)
        Text(
            pluralStringResource(R.plurals.dex_brand_lists, group.catalogs.size, group.catalogs.size) +
                (group.catalogs.firstOrNull()?.let { " · " + it.catalog.name } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The own dex sheet (new or edited) and the confirmation before a dex is removed. */
@Composable
private fun CatalogDialogs(viewModel: DexViewModel, deleting: CatalogEntity?, onDeleteDone: () -> Unit) {
    val brandSuggestions by viewModel.brandSuggestions.collectAsStateWithLifecycle()

    viewModel.catalogDraft?.let { draft ->
        CatalogSheet(
            draft,
            viewModel.catalogErrors,
            brandSuggestions,
            onChange = viewModel::updateCatalogDraft,
            onSave = viewModel::saveCatalog,
            onDismiss = viewModel::closeCatalog,
        )
    }

    deleting?.let { catalog ->
        AlertDialog(
            onDismissRequest = onDeleteDone,
            title = { Text(stringResource(R.string.delete_named, catalog.name)) },
            text = { Text(stringResource(R.string.dex_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteDone()
                    viewModel.delete(catalog)
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = onDeleteDone) { Text(stringResource(R.string.cancel)) } },
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
                Text(pluralStringResource(R.plurals.dex_review_count, count, count), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.dex_review_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            Text(stringResource(R.string.dex_review_open), style = MaterialTheme.typography.labelLarge, color = Garage.Brand)
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
                    downloading -> pluralStringResource(R.plurals.dex_images_downloading, missing, missing)
                    work == ImageWork.Retrying -> pluralStringResource(R.plurals.dex_images_retrying, missing, missing)
                    cellular -> pluralStringResource(R.plurals.dex_images_wait_connection, missing, missing)
                    else -> pluralStringResource(R.plurals.dex_images_wait_wifi, missing, missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!downloading) {
                TextButton(onClick = onDownload, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.retry)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dex_cellular), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.dex_cellular_hint),
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
        SubHeading(stringResource(R.string.dex_empty_title))
        Text(
            stringResource(R.string.dex_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CatalogRow(summary: CatalogSummary, onOpen: () -> Unit, onResync: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val counts = summary.counts

    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.catalog.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DexMeter(counts)
            Text(
                stringResource(R.string.dex_counts, counts.caught, counts.seen, counts.total) + if (counts.wanted > 0) " · " + stringResource(R.string.dex_counts_wanted, counts.wanted) else "",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.dex_menu_for, summary.catalog.name), tint = Garage.MutedForeground)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (WikiSource.fromKey(summary.catalog.source) != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dex_resync)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_sync), contentDescription = null) },
                        onClick = {
                            menu = false
                            onResync()
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dex_edit)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.dex_remove), color = Garage.Loss) },
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

/** "Dex sendiri" (web: `CreateDexPanel`). */
@Composable
private fun OwnDexCard(onCreate: () -> Unit) {
    SectionCard(stringResource(R.string.dex_own)) {
        Text(
            stringResource(R.string.dex_own_text),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
        OutlinedButton(onClick = onCreate, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            IconLabel(R.drawable.ic_add, stringResource(R.string.dex_own_create))
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

    SectionCard(stringResource(R.string.dex_sync_title)) {
        Text(
            stringResource(R.string.dex_sync_text),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
        SourceToggle(source, enabled = !running, onSelect = viewModel::setSource)
        source.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
        if (!source.single) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceInput(from, years, { it.toString() }, viewModel::setFrom, stringResource(R.string.dex_from), Modifier.weight(1f))
                ChoiceInput(to, years, { it.toString() }, viewModel::setTo, stringResource(R.string.dex_to), Modifier.weight(1f))
            }
        }
        Button(onClick = viewModel::startSync, enabled = !running, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            IconLabel(
                R.drawable.ic_sync,
                when {
                    source.single -> stringResource(R.string.dex_sync_list)
                    from == to -> stringResource(R.string.dex_sync_one, from)
                    else -> pluralStringResource(R.plurals.dex_sync_range, to - from + 1, from, to, to - from + 1)
                },
            )
        }

        when (val state = sync) {
            is SyncProgress.Running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        !state.started -> stringResource(R.string.waiting_for_connection)
                        state.year != null -> stringResource(R.string.dex_syncing, state.year, state.done + 1, state.total)
                        else -> stringResource(R.string.dex_syncing_list)
                    },
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

/** The wikis, two to a row so the names fit a phone. */
@Composable
private fun SourceToggle(selected: WikiSource, enabled: Boolean, onSelect: (WikiSource) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        WikiSource.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { source ->
                    val active = source == selected
                    val shape = RoundedCornerShape(8.dp)
                    Text(
                        source.shortName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .then(if (active) Modifier.background(Garage.Card, shape).border(1.dp, Garage.Brand.copy(alpha = 0.5f), shape) else Modifier)
                            .selectable(selected = active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(source) })
                            .padding(vertical = 10.dp),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun YearLine(source: WikiSource?, result: YearResult) {
    val style = MaterialTheme.typography.bodySmall
    val label = listOfNotNull(source?.brand, result.year?.toString()).joinToString(" ")
    if (result.error != null) {
        Text(stringResource(R.string.named_problem, label, result.error), style = style, color = Garage.Loss)
    } else {
        Text(
            // "New" only on a re-sync: on the first one everything is.
            pluralStringResource(R.plurals.dex_sync_releases, result.items, label, result.items) +
                (if (result.added in 1..<result.items) stringResource(R.string.dex_sync_new, result.added) else "") +
                (if (result.removed > 0) stringResource(R.string.dex_sync_removed, result.removed) else "") +
                (if (result.linked > 0) " · " + pluralStringResource(R.plurals.dex_sync_linked, result.linked, result.linked) else ""),
            style = style,
            color = Color.Unspecified,
        )
    }
}
