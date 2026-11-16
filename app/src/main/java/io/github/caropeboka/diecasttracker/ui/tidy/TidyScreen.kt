package io.github.caropeboka.diecasttracker.ui.tidy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.tidy.DuplicateGroup
import io.github.caropeboka.diecasttracker.data.tidy.Spelling
import io.github.caropeboka.diecasttracker.data.tidy.SpellingGroup
import io.github.caropeboka.diecasttracker.data.tidy.TidyField
import io.github.caropeboka.diecasttracker.ui.components.BrandLogo
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.MakeLogo
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.settings.SettingsScaffold
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** How many diecasts' names a spelling lists before "+n more". */
private const val NAMES_SHOWN = 3

/**
 * "Rapikan data": the brands, car makers, series and scales written in more than one way, each to be written one way
 * with a tap (and undone from the snackbar), and the diecasts that look entered twice, to open one by one.
 */
@Composable
fun TidyScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: TidyViewModel = viewModel(factory = TidyViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val undo = stringResource(R.string.undo)

    LaunchedEffect(viewModel) {
        viewModel.renamed.collect { renamed ->
            val result = snackbar.showSnackbar(renamed.message, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(renamed)
        }
    }

    SettingsScaffold(stringResource(R.string.tidy_title), onBack) {
        val current = state
        if (current == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val report = current.report
            Note(stringResource(R.string.tidy_intro))

            if (report.count == 0) {
                SectionCard(null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.Gain, modifier = Modifier.size(22.dp))
                        Text(stringResource(R.string.tidy_all_clean), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (report.spellings.isNotEmpty()) {
                SectionCard(stringResource(R.string.tidy_spellings)) {
                    Note(stringResource(R.string.tidy_spellings_note))
                    report.spellings.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider(color = Garage.Border)
                        SpellingGroupView(
                            group,
                            enabled = !viewModel.busy,
                            onApply = { to -> viewModel.apply(group, to) },
                            onLeave = { viewModel.leave(group) },
                        )
                    }
                }
            }

            if (report.duplicates.isNotEmpty()) {
                SectionCard(stringResource(R.string.tidy_duplicates)) {
                    Note(stringResource(R.string.tidy_duplicates_note))
                    report.duplicates.forEachIndexed { index, group ->
                        if (index > 0) HorizontalDivider(color = Garage.Border)
                        DuplicateGroupView(group, current.items, onOpen, onNotDuplicate = { viewModel.notDuplicate(group) })
                    }
                }
            }

            if (current.skipped) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = viewModel::showSkipped) { Text(stringResource(R.string.tidy_show_skipped)) }
                }
            }
        }
    }
}

@Composable
private fun SpellingGroupView(group: SpellingGroup, enabled: Boolean, onApply: (String) -> Unit, onLeave: () -> Unit) {
    var choice by rememberSaveable(group.key) { mutableStateOf(group.suggested) }
    // The options change when a diecast is edited meanwhile.
    val picked = choice.takeIf { it in group.options } ?: group.suggested

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(group.field.label), style = MaterialTheme.typography.labelLarge, color = Garage.MutedForeground)
            if (!group.sure) {
                Surface(shape = RoundedCornerShape(50), color = Garage.Accent, border = BorderStroke(1.dp, Garage.Brand2.copy(alpha = 0.5f))) {
                    Text(
                        stringResource(R.string.tidy_maybe),
                        style = MaterialTheme.typography.labelSmall,
                        color = Garage.Brand2,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.selectableGroup()) {
            group.options.forEach { option ->
                OptionRow(group.field, option, group.spellings.find { it.text == option }, selected = picked == option) { choice = option }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onLeave, enabled = enabled) {
                Text(stringResource(if (group.sure) R.string.tidy_leave else R.string.tidy_not_same))
            }
            Button(
                onClick = { onApply(picked) },
                enabled = enabled && group.spellings.any { it.text != picked },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.tidy_apply))
            }
        }
    }
}

/** One way of writing it: how many diecasts write it so and which, or that it is the usual spelling. */
@Composable
private fun OptionRow(field: TidyField, option: String, spelling: Spelling?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        when (field) {
            TidyField.Brand -> BrandLogo(option, 20.dp, fallback = false)
            TidyField.Make -> MakeLogo(option, 22.dp, fallback = false)
            else -> Unit
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option, style = MaterialTheme.typography.bodyLarge)
            val detail = if (spelling == null) {
                stringResource(R.string.tidy_usual)
            } else {
                val names = spelling.names.take(NAMES_SHOWN).joinToString(", ")
                val more = spelling.names.size - NAMES_SHOWN
                listOfNotNull(
                    pluralStringResource(R.plurals.tidy_spelling_count, spelling.count, spelling.count),
                    names + if (more > 0) " " + pluralStringResource(R.plurals.tidy_more, more, more) else "",
                ).joinToString(" · ")
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DuplicateGroupView(group: DuplicateGroup, items: Map<Long, DiecastListItem>, onOpen: (Long) -> Unit, onNotDuplicate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        group.ids.mapNotNull(items::get).forEach { item -> DuplicateRow(item) { onOpen(item.id) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onNotDuplicate) { Text(stringResource(R.string.tidy_not_duplicate)) }
        }
    }
}

@Composable
private fun DuplicateRow(item: DiecastListItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiecastPhoto(item.coverFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 24.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(item.brand, item.collectionNumber, item.color, item.year?.toString()).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val status = stringResource(item.status.label) + if (item.quantity > 1) " · ×${item.quantity}" else ""
            Text(status, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
        }
        Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            tint = Garage.MutedForeground,
            modifier = Modifier.size(20.dp).rotate(-90f),
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
}
