package io.github.caropeboka.diecasttracker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.ui.theme.Garage

@Composable
fun Badge(
    text: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier = modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
        }
        Text(text, color = content, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, maxLines = 1)
    }
}

/** Nothing for regular cars, so special pieces stand out. */
@Composable
fun RarityBadge(rarity: Rarity, modifier: Modifier = Modifier) {
    val color = Garage.rarityColor(rarity) ?: return
    Badge(
        text = rarity.label,
        background = color,
        content = Garage.OnBrand,
        icon = if (rarity == Rarity.SuperTreasureHunt) R.drawable.ic_sparkles else null,
        modifier = modifier,
    )
}

@DrawableRes
fun DiecastStatus.icon(): Int = when (this) {
    DiecastStatus.Owned -> R.drawable.ic_car
    DiecastStatus.Wishlist -> R.drawable.ic_favorite
    DiecastStatus.Spotted -> R.drawable.ic_visibility
    DiecastStatus.Sold -> R.drawable.ic_sell
}

/** Status is never colour-only: always the icon with its label. Nothing for owned cars. */
@Composable
fun StatusBadge(status: DiecastStatus, modifier: Modifier = Modifier) {
    if (status == DiecastStatus.Owned) return
    Badge(
        text = status.label,
        background = Color.Black.copy(alpha = 0.72f),
        content = Color.White,
        icon = status.icon(),
        modifier = modifier,
    )
}

@Composable
fun QuantityBadge(quantity: Int, modifier: Modifier = Modifier) {
    if (quantity <= 1) return
    Badge(text = "$quantity×", background = MaterialTheme.colorScheme.primary, content = Garage.OnBrand, modifier = modifier)
}
