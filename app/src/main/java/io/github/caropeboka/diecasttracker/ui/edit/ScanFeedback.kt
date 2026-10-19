package io.github.caropeboka.diecasttracker.ui.edit

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.ui.components.Badge
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.icon
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatRupiah

/** What the last scan did: why it failed, or the ✦ note with the AI's confidence and look-alikes in the collection. */
@Composable
fun ScanFeedback(scan: ScanState, onOpen: (Long) -> Unit) {
    when (scan) {
        is ScanState.Failed -> Notice(R.drawable.ic_warning, if (scan.retryLater) Garage.Brand2 else Garage.Loss) {
            Text(scan.message, style = MaterialTheme.typography.bodyMedium)
        }
        is ScanState.Done -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Notice(R.drawable.ic_sparkles, Garage.Brand) {
                Text("Field bertanda ✦ diisi oleh AI.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Keyakinan: ${scan.confidence.label}. Periksa dulu sebelum menyimpan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            if (scan.matches.isNotEmpty()) Matches(scan.matches, onOpen)
        }
        ScanState.Idle, ScanState.Scanning -> Unit
    }
}

@Composable
private fun Notice(@DrawableRes icon: Int, color: Color, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.1f), shape)
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/** Diecasts in the collection that look like the scanned one (web: `match-list.tsx`). */
@Composable
private fun Matches(matches: List<DiecastListItem>, onOpen: (Long) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Card, shape)
            .border(1.dp, Garage.Border, shape)
            .padding(vertical = 6.dp),
    ) {
        Text(
            "Mirip dengan yang sudah ada di koleksimu:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        matches.forEach { MatchRow(it, onOpen) }
    }
}

@Composable
private fun MatchRow(match: DiecastListItem, onOpen: (Long) -> Unit) {
    val small = MaterialTheme.typography.bodySmall

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Buka ${match.name}") { onOpen(match.id) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DiecastPhoto(match.coverFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 24.dp)
        Column(Modifier.weight(1f)) {
            Text(match.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            listOfNotNull(match.brand, match.series, match.color).joinToString(" · ").takeIf(String::isNotEmpty)?.let {
                Text(it, style = small, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (match.status == DiecastStatus.Wishlist && match.targetPrice != null) {
                Text("Target maks ${formatRupiah(match.targetPrice)}", style = small, color = Garage.MutedForeground)
            }
            if (match.sightingsCount > 0 && match.sightingsMinPrice != null) {
                Text("Pernah ditemui mulai ${formatRupiah(match.sightingsMinPrice)}", style = small, color = Garage.MutedForeground)
            }
        }
        Badge(
            text = when (match.status) {
                DiecastStatus.Owned -> "Punya ${match.quantity}×"
                DiecastStatus.Spotted -> "Ditemui ${match.sightingsCount}×"
                else -> match.status.label
            },
            background = Garage.Accent,
            content = Garage.Foreground,
            icon = match.status.icon(),
        )
    }
}
