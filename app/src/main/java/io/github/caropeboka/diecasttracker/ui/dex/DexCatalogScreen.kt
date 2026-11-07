package io.github.caropeboka.diecasttracker.ui.dex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.dex.DexEntry
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.DexProgress
import io.github.caropeboka.diecasttracker.data.dex.WikiSource
import io.github.caropeboka.diecasttracker.ui.components.Badge
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** One dex (web: `dex/show.tsx`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexCatalogScreen(
    onBack: () -> Unit,
    onAdd: (itemId: Long, status: DiecastStatus) -> Unit,
    onOpenDiecast: (Long) -> Unit,
    onAiSettings: () -> Unit,
    viewModel: DexCatalogViewModel = viewModel(factory = DexCatalogViewModel.Factory),
    custom: CustomDexViewModel = viewModel(factory = CustomDexViewModel.Factory),
) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filterKey by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val bySeries by viewModel.bySeries.collectAsStateWithLifecycle()
    val openNumber by viewModel.open.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val filter = EntryFilter.valueOf(filterKey)
    val isCustom = catalog?.source == DexMatcher.CUSTOM
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(custom) { custom.closed.collect { onBack() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Heading(catalog?.name ?: stringResource(R.string.tab_dex))
                        entries?.let { Text(pluralStringResource(R.plurals.dex_numbers, it.size, it.size), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    val wiki = catalog?.let { WikiSource.fromKey(it.source) }
                    val page = catalog?.sourcePage
                    if (wiki != null && page != null) {
                        IconButton(onClick = { uriHandler.openUri(wiki.wikiUrl(page)) }) {
                            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = stringResource(R.string.dex_open_wiki))
                        }
                    }
                    if (isCustom) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.dex_menu))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dex_edit)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        custom.editCatalog()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.dex_delete), color = Garage.Loss) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = Garage.Loss) },
                                    onClick = {
                                        menu = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val all = entries
        if (all == null || catalog == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val counts = DexProgress.counts(all)
        val visible = DexCatalogViewModel.visible(all, filter, query)

        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            full {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DexMeter(counts)
                    DexLegend(counts)
                    if (counts.wanted > 0) {
                        Text(stringResource(R.string.dex_wanted_wishlist, counts.wanted), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                    }
                }
            }
            if (isCustom) full { CustomDexTools(custom) }
            // Nothing to filter or search in an empty dex.
            if (all.isNotEmpty()) full {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EntryFilter.entries.forEach { option ->
                        FilterChip(
                            selected = option == filter,
                            onClick = { viewModel.setFilter(option) },
                            label = { Text("${stringResource(option.label)} · ${option.count(counts)}") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Garage.Brand.copy(alpha = 0.18f)),
                        )
                    }
                }
            }
            if (all.isNotEmpty()) full {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.dex_search)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = fieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.setBySeries(!bySeries) }) {
                        Icon(
                            painterResource(if (bySeries) R.drawable.ic_grid_view else R.drawable.ic_view_list),
                            contentDescription = stringResource(if (bySeries) R.string.collection_show_grid else R.string.dex_group_series),
                        )
                    }
                }
            }

            when {
                all.isEmpty() && isCustom -> full {
                    EmptyNote(stringResource(R.string.dex_custom_empty))
                }
                all.isEmpty() -> full { EmptyNote(stringResource(R.string.dex_catalog_empty)) }
                visible.isEmpty() -> full { EmptyNote(stringResource(R.string.dex_no_match)) }
                bySeries -> visible.groupBy { it.series }.forEach { (series, group) ->
                    val progress = DexProgress.series(all).find { it.series == series }
                    full {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            SubHeading(series ?: stringResource(R.string.dex_other_series), Modifier.weight(1f))
                            progress?.let { Text(stringResource(R.string.dex_owned_of, it.caught, it.total), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
                        }
                    }
                    items(group, key = { "s-$series-${it.number}" }) { EntryTile(it) { viewModel.open(it.number) } }
                }
                else -> items(visible, key = { it.number }) { EntryTile(it) { viewModel.open(it.number) } }
            }
        }

        all.find { it.number == openNumber }?.let { entry ->
            EntrySheet(
                entry = entry,
                catalog = catalog!!,
                onDismiss = {
                    viewModel.open(null)
                    if (viewModel.forEntry) onBack()
                },
                onAdd = { itemId, status ->
                    viewModel.open(null)
                    onAdd(itemId, status)
                },
                onOpenDiecast = onOpenDiecast,
                tools = if (isCustom) {
                    EntryTools(
                        onEdit = { item ->
                            viewModel.open(null)
                            custom.editEntry(item)
                        },
                        onAddVariant = {
                            viewModel.open(null)
                            custom.addVariant(entry)
                        },
                        onDelete = custom::deleteEntry,
                    )
                } else {
                    null
                },
            )
        }

        if (isCustom) {
            EntryEditorSheet(custom, seriesSuggestions = all.mapNotNull { it.series }.distinct())
            ImportSheet(custom, brand = catalog!!.brand, onAiSettings = onAiSettings)
            ChecklistReviewSheet(custom)
            custom.catalogDraft?.let { draft ->
                CatalogSheet(draft, custom.catalogErrors, brands = emptyList(), onChange = custom::updateCatalogDraft, onSave = custom::saveCatalog, onDismiss = custom::closeCatalog)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named, catalog?.name.orEmpty())) },
            text = { Text(stringResource(R.string.dex_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    custom.deleteCatalog()
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** What an own dex's entry sheet adds: correcting, another colour, removing. */
private class EntryTools(
    val onEdit: (CatalogItemEntity) -> Unit,
    val onAddVariant: () -> Unit,
    val onDelete: (CatalogItemEntity) -> Unit,
)

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground, modifier = Modifier.padding(vertical = 24.dp))
}

@Composable
private fun EntryTile(entry: DexEntry, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .clip(shape)
            .background(Garage.Card)
            .border(1.dp, Garage.Border, shape)
            .clickable(onClickLabel = stringResource(R.string.open_named, "#${entry.number}"), onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
            DexImage(entry.imagePath, Modifier.fillMaxSize(), dim = !entry.state.seen)
            Text(
                "#${entry.number}",
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Garage.Background.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp),
            )
            StateBadge(entry.state, Modifier.align(Alignment.TopEnd).padding(4.dp))
            HuntChip(entry.isTh, entry.isSth, Modifier.align(Alignment.BottomStart).padding(4.dp))
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelSmall,
            // Always two lines, so the cards in a row line up.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** An entry's details: its variants, what's linked, and adding it (web: `EntrySheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    entry: DexEntry,
    catalog: CatalogEntity,
    onDismiss: () -> Unit,
    onAdd: (Long, DiecastStatus) -> Unit,
    onOpenDiecast: (Long) -> Unit,
    tools: EntryTools?,
) {
    var variantId by rememberSaveable(entry.number) { mutableLongStateOf(entry.variants.first().item.id) }
    val variant = entry.variants.find { it.item.id == variantId } ?: entry.variants.first()
    val item = variant.item
    var confirmDelete by rememberSaveable(entry.number) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                SubHeading("#${entry.number} · ${entry.name}", maxLines = 2)
                Text(
                    catalog.name + (entry.series?.let { " · $it" + (entry.seriesNumber?.let { n -> " $n" } ?: "") } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }

            DexImage(item.imagePath, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(MaterialTheme.shapes.large), placeholderSize = 40.dp)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(stateLabel(entry.state), Garage.Muted, Garage.Foreground)
                if (item.isNew) Badge(stringResource(R.string.dex_new_model), Garage.Muted, Garage.Foreground)
                if (item.isTh) Badge(stringResource(R.string.rarity_treasure_hunt), Garage.rarityColor(Rarity.TreasureHunt)!!, Garage.OnBrand)
                if (item.isSth) Badge(stringResource(R.string.rarity_super_treasure_hunt), Garage.rarityColor(Rarity.SuperTreasureHunt)!!, Garage.OnBrand)
                item.exclusive?.let { Badge(stringResource(R.string.dex_exclusive, it), Garage.Muted, Garage.Foreground) }
                item.toyCode?.let { Badge(it, Garage.Muted, Garage.MutedForeground) }
            }

            if (entry.variants.size > 1) {
                Text(stringResource(R.string.dex_variants, entry.variants.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.variants.forEach { v ->
                        val selected = v.item.id == item.id
                        val shape = RoundedCornerShape(6.dp)
                        Column(
                            Modifier
                                .width(96.dp)
                                .clip(shape)
                                .border(if (selected) 2.dp else 1.dp, if (selected) Garage.Brand else Garage.Border, shape)
                                .clickable { variantId = v.item.id },
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                                DexImage(v.item.imagePath, Modifier.fillMaxSize(), dim = !v.state.seen)
                                StateBadge(v.state, Modifier.align(Alignment.TopEnd).padding(2.dp))
                            }
                            Text(
                                v.item.variantLabel ?: stringResource(R.string.dex_main_colour),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Text(
                if (entry.variants.size > 1) stringResource(R.string.dex_log_variant, item.variantLabel ?: stringResource(R.string.dex_main_colour)) else stringResource(R.string.dex_log),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = { onAdd(item.id, DiecastStatus.Owned) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                IconLabel(R.drawable.ic_add, stringResource(R.string.add_to_collection))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAdd(item.id, DiecastStatus.Spotted) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                    IconLabel(R.drawable.ic_visibility, stringResource(R.string.status_spotted))
                }
                OutlinedButton(onClick = { onAdd(item.id, DiecastStatus.Wishlist) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                    IconLabel(R.drawable.ic_favorite, stringResource(R.string.status_wishlist))
                }
            }

            if (tools != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { tools.onEdit(item) }, shape = MaterialTheme.shapes.medium) { IconLabel(R.drawable.ic_edit, stringResource(R.string.edit)) }
                    TextButton(onClick = tools.onAddVariant, shape = MaterialTheme.shapes.medium) { IconLabel(R.drawable.ic_add, stringResource(R.string.dex_add_variant)) }
                    TextButton(
                        onClick = { confirmDelete = true },
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                    ) { IconLabel(R.drawable.ic_delete, stringResource(R.string.delete)) }
                }
            }

            if (entry.diecasts.isNotEmpty()) {
                Text(stringResource(R.string.check_in_collection), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                entry.diecasts.forEach { diecast ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onOpenDiecast(diecast.id) }
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DiecastPhoto(diecast.coverFile, null, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 20.dp)
                        Column(Modifier.weight(1f)) {
                            Text(diecast.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(diecast.status.label) + if (diecast.catalogMatch == CatalogMatch.Auto) " · " + stringResource(R.string.dex_matched_auto_lower) else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = Garage.MutedForeground,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && tools != null) {
        val linked = entry.diecasts.count { it.catalogItemId == item.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named, "#${item.number} ${item.name}" + (item.variantLabel?.let { " ($it)" } ?: ""))) },
            text = {
                Text(
                    if (linked > 0) pluralStringResource(R.plurals.dex_entry_delete_linked, linked, linked) else stringResource(R.string.dex_entry_delete),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (entry.variants.size == 1) onDismiss()
                    tools.onDelete(item)
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
