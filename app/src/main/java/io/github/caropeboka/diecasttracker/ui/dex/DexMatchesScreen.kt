package io.github.caropeboka.diecasttracker.ui.dex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.LinkAction
import io.github.caropeboka.diecasttracker.data.dex.ReviewItem
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Diecasts to check against the Dex (web: `DexController::matches`). */
class DexMatchesViewModel(private val dex: DexRepository, private val messages: Messages, private val texts: Texts) : ViewModel() {
    /** Null while loading; with how many there are in all (the list shows the first [DexRepository.REVIEW_LIMIT]). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val items: StateFlow<Pair<List<ReviewItem>, Int>?> = combine(dex.observeReview(), dex.observeReviewCount(), ::Pair)
        .mapLatest { (rows, total) -> rows.map { dex.review(it) } to total }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun act(diecastId: Long, action: LinkAction, itemId: Long? = null, fixYear: Boolean = false, message: (Texts) -> String) {
        viewModelScope.launch {
            dex.link(diecastId, action, itemId, fixYear)
            messages.show(message(texts))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                DexMatchesViewModel(app.container.dex, app.container.messages, app.container.texts)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexMatchesScreen(
    onBack: () -> Unit,
    onOpenDiecast: (Long) -> Unit,
    onOpenEntry: (CatalogItemEntity) -> Unit,
    viewModel: DexMatchesViewModel = viewModel(factory = DexMatchesViewModel.Factory),
) {
    val state by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.dex_matches_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val (items, total) = state ?: run {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    stringResource(R.string.dex_matches_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )
            }
            if (items.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(40.dp))
                        SubHeading(stringResource(R.string.dex_matches_done))
                    }
                }
            }
            items(items, key = { it.row.diecast.id }) { item ->
                ReviewCard(item, viewModel, onOpenDiecast, onOpenEntry)
            }
            if (total > items.size) {
                item {
                    Text(
                        pluralStringResource(R.plurals.dex_matches_more, total - items.size, total - items.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(item: ReviewItem, viewModel: DexMatchesViewModel, onOpenDiecast: (Long) -> Unit, onOpenEntry: (CatalogItemEntity) -> Unit) {
    val diecast = item.row.diecast
    val linked = item.link.linked
    val id = diecast.id
    var searching by rememberSaveable(id) { mutableStateOf(false) }
    if (searching) {
        DexPickerSheet(
            start = diecast.name,
            brand = diecast.brand,
            year = diecast.year,
            onPick = { ref ->
                searching = false
                viewModel.act(id, LinkAction.Link, ref.row.item.id, message = { it(R.string.dex_msg_linked) })
            },
            onDismiss = { searching = false },
        )
    }

    SectionCard(null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpenDiecast(id) },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiecastPhoto(item.row.coverFile, null, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 24.dp)
            Column(Modifier.weight(1f)) {
                Text(diecast.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(diecast.brand, diecast.series).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                Text(
                    (listOfNotNull(diecast.collectionNumber, diecast.year?.toString()).joinToString(" · ").ifEmpty { stringResource(R.string.dex_no_number) }) +
                        " · " + stringResource(diecast.status.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
        }

        when {
            linked != null && item.link.match == CatalogMatch.Auto -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(R.drawable.ic_auto_fix_high), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.dex_matched_auto), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                }
                DexEntryCard(linked) { onOpenEntry(linked.row.item) }
                val fix = linked.yearFix
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fix != null) {
                        Button(
                            onClick = { viewModel.act(id, LinkAction.Confirm, fixYear = true, message = { it(R.string.dex_msg_year_fixed, fix) }) },
                            shape = MaterialTheme.shapes.medium,
                        ) { IconLabel(R.drawable.ic_check, stringResource(R.string.dex_correct_fix_year, fix)) }
                        OutlinedButton(
                            onClick = { viewModel.act(id, LinkAction.Confirm, message = { it(R.string.dex_msg_confirmed) }) },
                            shape = MaterialTheme.shapes.medium,
                        ) { Text(stringResource(R.string.dex_correct_keep_year)) }
                    } else {
                        Button(
                            onClick = { viewModel.act(id, LinkAction.Confirm, message = { it(R.string.dex_msg_confirmed) }) },
                            shape = MaterialTheme.shapes.medium,
                        ) { IconLabel(R.drawable.ic_check, stringResource(R.string.dex_correct)) }
                    }
                    TextButton(onClick = { searching = true }) {
                        IconLabel(R.drawable.ic_search, stringResource(R.string.dex_change))
                    }
                    TextButton(onClick = { viewModel.act(id, LinkAction.Ignore, message = { it(R.string.dex_msg_unlinked) }) }) {
                        IconLabel(R.drawable.ic_block, stringResource(R.string.dex_not_this))
                    }
                }
                if (fix != null) {
                    Text(
                        stringResource(R.string.dex_year_mismatch, diecast.year?.toString() ?: stringResource(R.string.dex_no_year), fix),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
            }
            item.link.suggestions.isNotEmpty() -> {
                Text(stringResource(R.string.dex_maybe), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                item.link.suggestions.forEach { suggestion ->
                    DexEntryCard(suggestion) { onOpenEntry(suggestion.row.item) }
                    Button(
                        onClick = {
                            viewModel.act(
                                id, LinkAction.Link, suggestion.row.item.id, fixYear = suggestion.yearFix != null,
                                message = { texts -> suggestion.yearFix?.let { texts(R.string.dex_msg_linked_year, it) } ?: texts(R.string.dex_msg_linked) },
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_check, suggestion.yearFix?.let { stringResource(R.string.dex_this_is_it_year, it) } ?: stringResource(R.string.dex_this_is_it)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { searching = true }) {
                        IconLabel(R.drawable.ic_search, stringResource(R.string.dex_search_self))
                    }
                    TextButton(onClick = { viewModel.act(id, LinkAction.Ignore, message = { it(R.string.dex_marked_absent) }) }) {
                        IconLabel(R.drawable.ic_block, stringResource(R.string.dex_not_here))
                    }
                }
            }
            else -> {
                Text(stringResource(R.string.dex_not_found_synced), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { searching = true }, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_search, stringResource(R.string.dex_search_dex))
                    }
                    TextButton(onClick = { viewModel.act(id, LinkAction.Ignore, message = { it(R.string.dex_marked_absent) }) }) {
                        IconLabel(R.drawable.ic_block, stringResource(R.string.skip))
                    }
                }
            }
        }
    }
}
