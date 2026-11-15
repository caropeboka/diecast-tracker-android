package io.github.caropeboka.diecasttracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatMoney
import io.github.caropeboka.diecasttracker.util.formatMoneyCompact
import io.github.caropeboka.diecasttracker.util.formatPercent
import kotlin.math.abs

/** The market value against what was paid, as a trading app shows a holding: [percent] is null when it was free. */
data class Gain(val amount: Long, val percent: Double?) {
    val up: Boolean get() = amount > 0
    val down: Boolean get() = amount < 0

    companion object {
        /** Null unless both prices are known. */
        fun of(purchase: Long?, market: Long?): Gain? {
            if (purchase == null || market == null || purchase < 0) return null
            return Gain(market - purchase, if (purchase > 0) (market - purchase) * 100.0 / purchase else null)
        }
    }
}

private val Gain.color: Color get() = if (up) Garage.Gain else if (down) Garage.Loss else Garage.MutedForeground

/** ▲ or ▼, so it isn't the colour alone that tells. */
private val Gain.arrow: String get() = if (up) "▲ " else if (down) "▼ " else ""

/** What a screen reader says for it: "Up 25% on the purchase price". */
@Composable
private fun Gain.description(shown: String): String = when {
    up -> stringResource(R.string.gain_up, shown)
    down -> stringResource(R.string.gain_down, shown)
    else -> stringResource(R.string.gain_even)
}

/**
 * "▲ 25%" beside a card's market value: short, and it reads the same for a cheap card and a dear box (Rp 5 rb more is a
 * lot on one and nothing on the other). The amount when it was free.
 */
@Composable
fun GainPercent(gain: Gain, style: TextStyle, modifier: Modifier = Modifier) {
    val shown = gain.percent?.let { formatPercent(abs(it)) } ?: formatMoneyCompact(abs(gain.amount))
    val description = gain.description(shown)
    Text(
        gain.arrow + shown,
        style = style,
        color = gain.color,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/** "▲ +Rp 9.500 (+25%) against the purchase price" under the form's market value: both, as a holding's page has. */
@Composable
fun GainLine(gain: Gain, modifier: Modifier = Modifier) {
    val sign = if (gain.down) "−" else if (gain.up) "+" else ""
    val amount = sign + formatMoney(abs(gain.amount))
    val shown = amount + gain.percent?.let { " ($sign${formatPercent(abs(it))})" }.orEmpty()
    val description = gain.description(shown)
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(gain.arrow + shown, style = MaterialTheme.typography.bodySmall, color = gain.color, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.dashboard_vs_purchase), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
    }
}
