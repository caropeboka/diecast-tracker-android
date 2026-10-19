package io.github.caropeboka.diecasttracker.ui.collection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.QuantityBadge
import io.github.caropeboka.diecasttracker.ui.components.RarityBadge
import io.github.caropeboka.diecasttracker.ui.components.StatusBadge
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatRupiahCompact

/** Price line of a card: lowest asking price when spotted, target when wished for, else market value. */
private data class PriceLabel(val text: String, val emphasized: Boolean)

private fun DiecastListItem.priceLabel(): PriceLabel? = when (status) {
    DiecastStatus.Spotted -> sightingsMinPrice?.let { PriceLabel("mulai ${formatRupiahCompact(it)}", true) }
    DiecastStatus.Wishlist -> targetPrice?.let { PriceLabel("≤ ${formatRupiahCompact(it)}", false) }
    else -> marketValue?.let { PriceLabel(formatRupiahCompact(it), true) }
}

private fun DiecastListItem.detailLine(): String =
    if (status == DiecastStatus.Spotted && sightingsCount > 0) "Ditemui $sightingsCount×" else series ?: color ?: "—"

private val DiecastListItem.subtitle get() = listOfNotNull(brand, year?.toString()).joinToString(" · ")

/** Photo-forward card: the car fills it, the name sits on a dark gradient over the photo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiecastCard(item: DiecastListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f)) {
                DiecastPhoto(item.coverFile, item.name, Modifier.matchParentSize())

                FlowRow(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).padding(end = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    RarityBadge(item.rarity)
                    StatusBadge(item.status)
                }
                QuantityBadge(item.quantity, Modifier.align(Alignment.TopEnd).padding(8.dp))

                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.9f),
                            ),
                        )
                        .padding(start = 12.dp, end = 12.dp, top = 40.dp, bottom = 10.dp),
                ) {
                    if (item.subtitle.isNotEmpty()) {
                        Text(
                            item.subtitle.uppercase(),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        item.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.detailLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                item.priceLabel()?.let { price ->
                    Text(
                        price.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (price.emphasized) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (price.emphasized) Garage.Brand else Garage.MutedForeground,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Compact row for the list layout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiecastRow(item: DiecastListItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                DiecastPhoto(
                    item.coverFile,
                    item.name,
                    Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                    placeholderSize = 28.dp,
                )
                QuantityBadge(item.quantity, Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(item.brand, item.year?.toString(), item.series).joinToString(" · ")
                if (details.isNotEmpty()) {
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RarityBadge(item.rarity)
                    StatusBadge(item.status)
                    if (item.status == DiecastStatus.Spotted && item.sightingsCount > 0) {
                        Text("Ditemui ${item.sightingsCount}×", style = MaterialTheme.typography.labelSmall, color = Garage.MutedForeground)
                    }
                }
            }
            item.priceLabel()?.let { price ->
                Spacer(Modifier.width(12.dp))
                Text(
                    price.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (price.emphasized) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (price.emphasized) Garage.Brand else Garage.MutedForeground,
                )
            }
        }
    }
}
