package io.github.caropeboka.diecasttracker.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.dex.DexLinkState
import io.github.caropeboka.diecasttracker.data.dex.DexRef
import io.github.caropeboka.diecasttracker.data.dex.LinkAction
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.dex.DexEntryCard
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/**
 * A saved diecast's dex entry: confirm an automatic match, pick a suggestion, or drop it. Changes are saved
 * right away; they're not part of the form (web: `DexEditPanel`).
 */
@Composable
fun DexEditPanel(
    state: DexLinkState,
    year: String,
    busy: Boolean,
    onLink: (LinkAction, Long?) -> Unit,
    onYear: (Int) -> Unit,
    onOpenCatalog: (Long) -> Unit,
) {
    DexBox {
        DexTitle(stringResource(R.string.tab_dex))
        val linked = state.linked
        when {
            linked != null -> {
                DexEntryCard(linked) { onOpenCatalog(linked.row.item.catalogId) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                    if (state.match == CatalogMatch.Auto) {
                        Note(R.drawable.ic_auto_fix_high, stringResource(R.string.dex_matched_auto), Modifier.align(Alignment.CenterVertically))
                        Button(onClick = { onLink(LinkAction.Confirm, null) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                            IconLabel(R.drawable.ic_check, stringResource(R.string.dex_correct))
                        }
                    } else {
                        Note(R.drawable.ic_check, stringResource(R.string.dex_confirmed), Modifier.align(Alignment.CenterVertically))
                    }
                    TextButton(onClick = { onLink(LinkAction.Ignore, null) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_block, stringResource(R.string.dex_unlink))
                    }
                }
                YearFix(linked, year, onYear)
            }
            state.suggestions.isNotEmpty() -> {
                Text(stringResource(R.string.dex_maybe), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                state.suggestions.forEach { suggestion ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DexEntryCard(suggestion, Modifier.weight(1f)) { onOpenCatalog(suggestion.row.item.catalogId) }
                        Button(
                            onClick = { onLink(LinkAction.Link, suggestion.row.item.id) },
                            enabled = !busy,
                            shape = MaterialTheme.shapes.medium,
                        ) { IconLabel(R.drawable.ic_check, stringResource(R.string.dex_this_one)) }
                    }
                }
            }
            else -> Text(
                stringResource(if (state.match == CatalogMatch.Ignored) R.string.dex_marked_absent else R.string.dex_not_in_dex),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
    }
}

/**
 * A new diecast: the entry it will be linked to (picked in the Dex, or the scan's suggestion once accepted),
 * or the scan's suggestion (web: `DexCreatePanel`).
 */
@Composable
fun DexCreatePanel(
    chosen: DexRef?,
    suggestion: DexRef?,
    year: String,
    onChoose: (DexRef?) -> Unit,
    onYear: (Int) -> Unit,
    onOpenCatalog: (Long) -> Unit,
) {
    if (chosen != null) {
        DexBox(highlight = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DexTitle(stringResource(R.string.dex_linked), Modifier.weight(1f))
                TextButton(onClick = { onChoose(null) }, shape = MaterialTheme.shapes.medium) {
                    IconLabel(R.drawable.ic_close, stringResource(R.string.dex_unlink))
                }
            }
            DexEntryCard(chosen) { onOpenCatalog(chosen.row.item.catalogId) }
            YearFix(chosen, year, onYear)
        }
        return
    }

    suggestion ?: return
    val release = suggestion.row.catalogYear
    val yearDiffers = release != null && release.toString() != year.trim()

    DexBox {
        DexTitle(stringResource(R.string.dex_looks_like))
        DexEntryCard(suggestion) { onOpenCatalog(suggestion.row.item.catalogId) }
        Button(
            onClick = {
                onChoose(suggestion)
                if (yearDiffers) onYear(release!!)
            },
            shape = MaterialTheme.shapes.medium,
        ) { IconLabel(R.drawable.ic_check, if (yearDiffers) stringResource(R.string.dex_link_with_year, release) else stringResource(R.string.dex_link)) }
        if (yearDiffers) {
            Text(
                stringResource(R.string.dex_ai_year, year.trim().ifEmpty { "—" }, release),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
    }
}

/** "Pakai 2026" when the form's year differs from the entry's release year. */
@Composable
private fun YearFix(entry: DexRef, year: String, onYear: (Int) -> Unit) {
    val release = entry.row.catalogYear ?: return
    if (release.toString() == year.trim()) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, MaterialTheme.shapes.medium)
            .padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
    ) {
        val note = stringResource(R.string.dex_year_note, year.trim().ifEmpty { stringResource(R.string.dex_year_empty) }, MARK)
        Text(
            buildAnnotatedString {
                val (before, after) = note.split(MARK)
                append(before)
                withStyle(SpanStyle(color = Garage.Foreground, fontWeight = FontWeight.SemiBold)) { append(release.toString()) }
                append(after)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
        TextButton(onClick = { onYear(release) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.dex_use_year, release))
        }
    }
}

@Composable
private fun DexBox(highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (highlight) Garage.Brand.copy(alpha = 0.05f) else Garage.Card, shape)
            .border(1.dp, if (highlight) Garage.Brand.copy(alpha = 0.4f) else Garage.Border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun DexTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(R.drawable.ic_collections_bookmark), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Note(icon: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
    }
}

/** Stands in for the release year in [R.string.dex_year_note], which is drawn bold. */
private const val MARK = "\u0000"
