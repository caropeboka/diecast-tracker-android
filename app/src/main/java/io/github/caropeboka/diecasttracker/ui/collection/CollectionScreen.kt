package io.github.caropeboka.diecasttracker.ui.collection

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CollectionLayout
import io.github.caropeboka.diecasttracker.data.CollectionSort
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MissingField
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.ui.LocalTour
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.TourTarget
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.scan.QueueBanner
import io.github.caropeboka.diecasttracker.ui.revealSource
import io.github.caropeboka.diecasttracker.ui.sharedDiecastCard
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.ui.tourTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onTab: (MainTab) -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onData: () -> Unit,
    onBulkScan: () -> Unit,
    onCheck: () -> Unit,
    onAiSettings: () -> Unit,
    onSettings: () -> Unit,
    viewModel: CollectionViewModel = viewModel(factory = CollectionViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val backupDue by viewModel.backupDue.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Heading(stringResource(R.string.collection_title))
                        state.items?.let {
                            Text(
                                pluralStringResource(R.plurals.collection_count, it.size, it.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = Garage.MutedForeground,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleLayout) {
                        if (state.layout == CollectionLayout.Grid) {
                            Icon(painterResource(R.drawable.ic_view_list), contentDescription = stringResource(R.string.collection_show_list))
                        } else {
                            Icon(painterResource(R.drawable.ic_grid_view), contentDescription = stringResource(R.string.collection_show_grid))
                        }
                    }
                    Box {
                        // What the menu opens grows out of this button.
                        IconButton(onClick = { menuOpen = true }, modifier = revealSource().tourTarget(LocalTour.current, TourTarget.Menu)) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.collection_more_menu))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_store_check)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onCheck()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_bulk_scan)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onBulkScan()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_ai)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAiSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_data)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_folder_zip), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onData()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_menu)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSettings()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainNavigationBar(MainTab.Collection, onTab) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (queued > 0) QueueBanner(queued, onBulkScan, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            backupDue?.let { BackupReminderCard(it, onBackup = onData, onLater = viewModel::snoozeBackupReminder) }
            if (state.total > 0) {
                SearchField(search, viewModel::setSearch)
                FilterBar(state, viewModel)
            }

            val items = state.items
            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                items.isEmpty() && state.total == 0 -> EmptyCollection(onAdd, onData)
                items.isEmpty() -> NoMatches(viewModel::resetFilters)
                state.layout == CollectionLayout.Grid -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        DiecastCard(item, onClick = { onOpen(item.id) }, modifier = sharedDiecastCard(item.id))
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        DiecastRow(item, onClick = { onOpen(item.id) }, modifier = sharedDiecastCard(item.id))
                        HorizontalDivider(Modifier.padding(start = 92.dp), color = Garage.Border)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(R.string.collection_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.collection_clear_search))
                }
            }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun FilterBar(state: CollectionUiState, viewModel: CollectionViewModel) {
    val filter = state.filter
    val missingChip = @Composable {
        MenuChip(
            label = filter.missing?.let { stringResource(it.missing) } ?: stringResource(R.string.collection_all_completeness),
            active = filter.missing != null,
            options = listOf<MissingField?>(null) + MissingField.entries,
            optionLabel = { it?.let { field -> stringResource(field.missing) } ?: stringResource(R.string.collection_all_completeness) },
            current = filter.missing,
            onSelect = viewModel::setMissing,
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuChip(
            label = filter.status?.let { stringResource(it.label) } ?: stringResource(R.string.collection_all_but_spotted),
            active = filter.status != null,
            options = listOf<DiecastStatus?>(null) + DiecastStatus.entries,
            optionLabel = { it?.let { status -> stringResource(status.label) } ?: stringResource(R.string.collection_all_but_spotted) },
            current = filter.status,
            onSelect = viewModel::setStatus,
        )
        // Set from the dashboard's "Lengkapi": in view, or the shorter list would look unexplained.
        if (filter.missing != null) missingChip()
        if (state.brands.isNotEmpty()) {
            MenuChip(
                label = filter.brand ?: stringResource(R.string.collection_all_brands),
                active = filter.brand != null,
                options = listOf<String?>(null) + state.brands,
                optionLabel = { it ?: stringResource(R.string.collection_all_brands) },
                current = filter.brand,
                onSelect = viewModel::setBrand,
            )
        }
        MenuChip(
            label = filter.rarity?.let { stringResource(it.label) } ?: stringResource(R.string.collection_all_rarities),
            active = filter.rarity != null,
            options = listOf<Rarity?>(null) + Rarity.entries,
            optionLabel = { it?.let { rarity -> stringResource(rarity.label) } ?: stringResource(R.string.collection_all_rarities) },
            current = filter.rarity,
            onSelect = viewModel::setRarity,
        )
        if (state.tags.isNotEmpty()) {
            MenuChip(
                label = filter.tag?.let { stringResource(R.string.collection_tag, it) } ?: stringResource(R.string.collection_all_tags),
                active = filter.tag != null,
                options = listOf<String?>(null) + state.tags,
                optionLabel = { it ?: stringResource(R.string.collection_all_tags) },
                current = filter.tag,
                onSelect = viewModel::setTag,
            )
        }
        if (filter.missing == null) missingChip()
        MenuChip(
            label = stringResource(filter.sort.label),
            active = false,
            icon = R.drawable.ic_sort,
            options = CollectionSort.entries,
            optionLabel = { stringResource(it.label) },
            current = filter.sort,
            onSelect = viewModel::setSort,
        )
    }
}

/** A filter chip that opens a menu of choices. */
@Composable
private fun <T> MenuChip(
    label: String,
    active: Boolean,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    current: T,
    onSelect: (T) -> Unit,
    @DrawableRes icon: Int? = null,
) {
    var open by remember { mutableStateOf(false) }

    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, maxLines = 1) },
            leadingIcon = icon?.let {
                { Icon(painterResource(it), contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
            },
            trailingIcon = {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, Modifier.size(FilterChipDefaults.IconSize))
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Garage.Card,
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.widthIn(max = 240.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    trailingIcon = if (option == current) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.collection_selected), Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The collection lives only on this phone: now and then it asks for a backup (see `BackupReminder`). */
@Composable
private fun BackupReminderCard(due: BackupDue, onBackup: () -> Unit, onLater: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = Garage.Brand2.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Garage.Brand2.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(R.drawable.ic_history),
                    contentDescription = null,
                    tint = Garage.Brand2,
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
                    Text(
                        due.daysSinceLast?.let { pluralStringResource(R.plurals.backup_reminder_days, it.toInt(), it) } ?: stringResource(R.string.backup_reminder_never),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.backup_reminder_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
            }
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = onLater) { Text(stringResource(R.string.later)) }
                TextButton(onClick = onBackup) { Text(stringResource(R.string.backup_reminder_now)) }
            }
        }
    }
}

@Composable
private fun EmptyCollection(onAdd: () -> Unit, onData: () -> Unit) {
    EmptyState(
        icon = R.drawable.ic_photo_camera,
        title = stringResource(R.string.collection_empty_title),
        text = stringResource(R.string.collection_empty_text),
    ) {
        Button(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_photo_camera, stringResource(R.string.collection_add_first))
        }
        OutlinedButton(onClick = onData, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_folder_zip, stringResource(R.string.restore_from_backup))
        }
    }
}

@Composable
private fun NoMatches(onReset: () -> Unit) {
    EmptyState(icon = R.drawable.ic_search, title = stringResource(R.string.collection_no_matches), text = null) {
        TextButton(onClick = onReset) { Text(stringResource(R.string.collection_reset_filter)) }
    }
}

@Composable
private fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    text: String?,
    actions: @Composable () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Garage.MutedForeground.copy(alpha = 0.6f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions()
        }
    }
}
