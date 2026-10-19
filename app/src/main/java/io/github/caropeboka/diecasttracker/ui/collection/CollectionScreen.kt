package io.github.caropeboka.diecasttracker.ui.collection

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CollectionLayout
import io.github.caropeboka.diecasttracker.data.CollectionSort
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onTab: (MainTab) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onOpen: (Long) -> Unit,
    onData: () -> Unit,
    onAiSettings: (fromScan: Boolean) -> Unit,
    viewModel: CollectionViewModel = viewModel(factory = CollectionViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val scanEnabled by viewModel.scanEnabled.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Heading("Koleksi")
                        state.items?.let {
                            Text(
                                "${it.size} item tercatat",
                                style = MaterialTheme.typography.bodySmall,
                                color = Garage.MutedForeground,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleLayout) {
                        if (state.layout == CollectionLayout.Grid) {
                            Icon(painterResource(R.drawable.ic_view_list), contentDescription = "Tampilkan sebagai daftar")
                        } else {
                            Icon(painterResource(R.drawable.ic_grid_view), contentDescription = "Tampilkan sebagai grid")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu lain")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Pengaturan AI") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onAiSettings(false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Data & backup") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_folder_zip), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onData()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text("Tambah") },
                    containerColor = Garage.Muted,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
                // Camera first, the AI fills the form; without a key, it explains how to turn scanning on.
                ExtendedFloatingActionButton(
                    onClick = { if (scanEnabled) onScan() else onAiSettings(true) },
                    icon = { Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null) },
                    text = { Text("Scan") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        bottomBar = { MainNavigationBar(MainTab.Collection, onTab) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        DiecastCard(item, onClick = { onOpen(item.id) })
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { item ->
                        DiecastRow(item, onClick = { onOpen(item.id) })
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
        placeholder = { Text("Cari nama, seri, warna, lokasi…") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Hapus pencarian")
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

    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MenuChip(
            label = filter.status?.label ?: "Semua kecuali ditemui",
            active = filter.status != null,
            options = listOf<DiecastStatus?>(null) + DiecastStatus.entries,
            optionLabel = { it?.label ?: "Semua kecuali ditemui" },
            current = filter.status,
            onSelect = viewModel::setStatus,
        )
        if (state.brands.isNotEmpty()) {
            MenuChip(
                label = filter.brand ?: "Semua merek",
                active = filter.brand != null,
                options = listOf<String?>(null) + state.brands,
                optionLabel = { it ?: "Semua merek" },
                current = filter.brand,
                onSelect = viewModel::setBrand,
            )
        }
        MenuChip(
            label = filter.rarity?.label ?: "Semua kelangkaan",
            active = filter.rarity != null,
            options = listOf<Rarity?>(null) + Rarity.entries,
            optionLabel = { it?.label ?: "Semua kelangkaan" },
            current = filter.rarity,
            onSelect = viewModel::setRarity,
        )
        if (state.tags.isNotEmpty()) {
            MenuChip(
                label = filter.tag?.let { "Tag: $it" } ?: "Semua tag",
                active = filter.tag != null,
                options = listOf<String?>(null) + state.tags,
                optionLabel = { it ?: "Semua tag" },
                current = filter.tag,
                onSelect = viewModel::setTag,
            )
        }
        MenuChip(
            label = filter.sort.label,
            active = false,
            icon = R.drawable.ic_sort,
            options = CollectionSort.entries,
            optionLabel = { it.label },
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
    optionLabel: (T) -> String,
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
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = "Dipilih", Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyCollection(onAdd: () -> Unit, onData: () -> Unit) {
    EmptyState(
        icon = R.drawable.ic_photo_camera,
        title = "Koleksimu masih kosong",
        text = "Tambah diecast pertamamu, atau pulihkan koleksi dari backup web app.",
    ) {
        Button(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_photo_camera, "Tambah diecast pertama")
        }
        OutlinedButton(onClick = onData, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_folder_zip, "Pulihkan dari backup")
        }
    }
}

@Composable
private fun NoMatches(onReset: () -> Unit) {
    EmptyState(icon = R.drawable.ic_search, title = "Tidak ada yang cocok", text = null) {
        TextButton(onClick = onReset) { Text("Reset filter") }
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
