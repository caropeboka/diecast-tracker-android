package io.github.caropeboka.diecasttracker.ui.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.data.dashboard.YearColumn
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlin.math.ceil

/** The web's `--viz-muted`: regular diecasts among the rarities. */
internal val RegularBar = Color(0xFF4A4A46)

/** A thin bar filling [fraction] of the width, on an optional [track] (web: `Meter`, `BarList`). */
@Composable
internal fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, track: Color = Color.Transparent, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).background(track, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color, RoundedCornerShape(3.dp)))
    }
}

internal class BarRow(
    val key: String,
    val label: String,
    /** The number at the row's end, e.g. "12 pcs". */
    val display: String,
    /** Of the longest bar. */
    val fraction: Float,
    val color: Color = Garage.MeterBar,
    val detail: String? = null,
    val onClick: (() -> Unit)? = null,
)

/** Labelled horizontal bars, longest first (web: `BarList`). */
@Composable
internal fun BarList(rows: List<BarRow>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .then(if (row.onClick != null) Modifier.clickable(onClick = row.onClick) else Modifier)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(row.display, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Bar(row.fraction, row.color)
                row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
            }
        }
    }
}

/** Pieces per release year as columns; empty years show as a stub on the baseline (web: `ColumnChart`). */
@Composable
internal fun ColumnChart(columns: List<YearColumn>) {
    val max = columns.maxOf { it.total }.coerceAtLeast(1)
    val showValues = columns.size <= 14
    val labelEvery = ceil(columns.size / 10.0).toInt().coerceAtLeast(1)

    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(if (columns.size > 16) 2.dp else 4.dp),
    ) {
        columns.forEachIndexed { i, column ->
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) { contentDescription = "${column.longLabel}: ${column.total} diecast" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (showValues && column.total > 0) column.total.toString() else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Garage.MutedForeground,
                    maxLines = 1,
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    if (column.total > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(column.total.toFloat() / max)
                                .background(Garage.MeterBar, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(2.dp).background(Garage.MeterTrack))
                    }
                }
                Text(
                    if (i % labelEvery == 0) column.label else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = Garage.MutedForeground,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** One headline number with its label and a hint (web: `StatTile`). */
@Composable
internal fun StatTile(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 2)
        }
    }
}

@Composable
internal fun Thumb(item: DiecastListItem, size: Dp = 44.dp) {
    DiecastPhoto(item.coverFile, null, Modifier.size(size).clip(RoundedCornerShape(6.dp)), placeholderSize = size / 2)
}

/** Diecasts with a photo, name, brand · series and something at the end (web: `MiniList`). */
@Composable
internal fun MiniList(items: List<DiecastListItem>, onOpen: (Long) -> Unit, detail: @Composable (DiecastListItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpen(item.id) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Thumb(item, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle(item)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                detail(item)
            }
        }
    }
}

internal fun subtitle(item: DiecastListItem): String? = listOfNotNull(item.brand, item.series).joinToString(" · ").ifEmpty { null }

/** "Semua →" at a panel's heading. */
@Composable
internal fun SeeAll(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp)) {
        Text("Semua →", style = MaterialTheme.typography.bodySmall)
    }
}
