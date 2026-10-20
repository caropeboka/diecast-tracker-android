package io.github.caropeboka.diecasttracker.ui.dex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.dex.DexCounts
import io.github.caropeboka.diecasttracker.data.dex.DexRef
import io.github.caropeboka.diecasttracker.data.dex.EntryState
import io.github.caropeboka.diecasttracker.ui.theme.Garage

private fun percent(part: Int, total: Int) = if (total == 0) 0 else Math.round(part * 100f / total)

/**
 * Seen/caught progress on one bar: owned (strong step) · seen only (middle step) · the rest (track), split by small
 * gaps. Same ramp, so it reads as one measure; [DexLegend] carries the numbers (web: `DexMeter`).
 */
@Composable
fun DexMeter(counts: DexCounts, modifier: Modifier = Modifier) {
    val segments = listOf(
        counts.caught to Garage.MeterBar,
        (counts.seen - counts.caught) to Garage.MeterSoft,
        (counts.total - counts.seen) to Garage.MeterTrack,
    ).filter { it.first > 0 }

    Row(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .semantics { contentDescription = "Dilihat ${counts.seen}, dimiliki ${counts.caught}, dari ${counts.total}" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (segments.isEmpty()) Box(Modifier.fillMaxSize().background(Garage.MeterTrack, RoundedCornerShape(4.dp)))
        segments.forEach { (value, color) ->
            Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
    }
}

/** "■ Dimiliki 12 (5%)  ■ Dilihat 34 (14%)  dari 250". */
@Composable
fun DexLegend(counts: DexCounts, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodySmall
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(Garage.MeterBar, "Dimiliki ${counts.caught} (${percent(counts.caught, counts.total)}%)")
        LegendItem(Garage.MeterSoft, "Dilihat ${counts.seen} (${percent(counts.seen, counts.total)}%)")
        Text("dari ${counts.total}", style = style, color = Garage.MutedForeground)
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.Foreground)
    }
}

/** A downloaded wiki thumbnail (or a car silhouette); entries not seen yet are dimmed and grey. */
@Composable
fun DexImage(path: String?, modifier: Modifier = Modifier, dim: Boolean = false, placeholderSize: Dp = 24.dp) {
    val dexSync = LocalContext.current.container.dexSync
    Box(modifier.background(Garage.Muted), contentAlignment = Alignment.Center) {
        if (path == null) {
            Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = Garage.MutedForeground.copy(alpha = 0.4f), modifier = Modifier.size(placeholderSize))
        } else {
            AsyncImage(
                model = dexSync.file(path),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = if (dim) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                modifier = Modifier.fillMaxSize().padding(2.dp).alpha(if (dim) 0.35f else 1f),
            )
        }
    }
}

/** Icon + label (as its description), so the state never rides on colour alone. */
@Composable
fun StateBadge(state: EntryState, modifier: Modifier = Modifier) {
    val (icon, label, background) = when {
        state.caught -> Triple(R.drawable.ic_check, "Dimiliki", Garage.MeterBar)
        state.seen -> Triple(R.drawable.ic_visibility, "Dilihat", Garage.MeterSoft)
        state.wanted -> Triple(R.drawable.ic_favorite, "Diincar", Garage.Background.copy(alpha = 0.85f))
        else -> return
    }
    Box(modifier.size(20.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(12.dp))
    }
}

fun stateLabel(state: EntryState) = when {
    state.caught -> "Dimiliki"
    state.seen -> "Dilihat"
    state.wanted -> "Diincar"
    else -> "Belum ditelusuri"
}

@Composable
fun HuntChip(isTh: Boolean, isSth: Boolean, modifier: Modifier = Modifier) {
    val (label, rarity) = when {
        isSth -> "STH" to Rarity.SuperTreasureHunt
        isTh -> "TH" to Rarity.TreasureHunt
        else -> return
    }
    Text(
        label,
        color = Garage.OnBrand,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Garage.rarityColor(rarity)!!, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp),
    )
}

/** A dex entry in one line: thumbnail, "Hot Wheels Mainline 2026 · #061", name, series (web: `DexEntryCard`). */
@Composable
fun DexEntryCard(ref: DexRef, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val item = ref.row.item
    val shape = MaterialTheme.shapes.medium

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Garage.Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DexImage(item.imagePath, Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f)) {
            Text("${ref.row.catalogName} · #${item.number}", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1)
            Text(
                item.name + (item.variantLabel?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.series?.let { series ->
                Text(
                    series + (item.seriesNumber?.let { " $it" } ?: "") + when {
                        item.isSth -> " · Super TH"
                        item.isTh -> " · TH"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
