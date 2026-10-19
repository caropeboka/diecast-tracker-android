package io.github.caropeboka.diecasttracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Rarity

/** The web app's "Garage" theme tokens (dark only). */
object Garage {
    val Background = Color(0xFF0B0B0D)
    val Card = Color(0xFF131316)
    val Popover = Color(0xFF16161A)
    val Muted = Color(0xFF1C1C21)
    val Accent = Color(0xFF221F1C)
    val Border = Color(0xFF26262B)
    val Input = Color(0xFF2C2C32)
    val Foreground = Color(0xFFF4F1EC)
    val MutedForeground = Color(0xFF9A968F)
    val Brand = Color(0xFFFF6A1A)
    val OnBrand = Color(0xFF160A03)
    val Brand2 = Color(0xFFFFB020)

    val Gain = Color(0xFF0CA30C)
    val Loss = Color(0xFFE66767)

    /** Badge fill per rarity; text on them is dark ([OnBrand]) for contrast. */
    fun rarityColor(rarity: Rarity): Color? = when (rarity) {
        Rarity.Regular -> null
        Rarity.TreasureHunt -> Color(0xFF199E70)
        Rarity.SuperTreasureHunt -> Color(0xFFD95926)
        Rarity.Chase -> Color(0xFF9085E9)
        Rarity.Limited -> Color(0xFF5B9BD5)
        Rarity.Exclusive -> Color(0xFFD55181)
    }
}

private val colors = darkColorScheme(
    primary = Garage.Brand,
    onPrimary = Garage.OnBrand,
    primaryContainer = Color(0xFF3D2012),
    onPrimaryContainer = Color(0xFFFFD2B8),
    inversePrimary = Color(0xFFA43E00),
    secondary = Garage.Brand2,
    onSecondary = Garage.OnBrand,
    secondaryContainer = Color(0xFF2E1C12),
    onSecondaryContainer = Color(0xFFFFCFAE),
    tertiary = Garage.Brand2,
    onTertiary = Garage.OnBrand,
    background = Garage.Background,
    onBackground = Garage.Foreground,
    surface = Garage.Background,
    onSurface = Garage.Foreground,
    surfaceVariant = Garage.Muted,
    onSurfaceVariant = Garage.MutedForeground,
    surfaceTint = Garage.Card,
    surfaceDim = Garage.Background,
    surfaceBright = Garage.Accent,
    surfaceContainerLowest = Garage.Background,
    surfaceContainerLow = Garage.Card,
    surfaceContainer = Garage.Popover,
    surfaceContainerHigh = Garage.Muted,
    surfaceContainerHighest = Garage.Accent,
    inverseSurface = Garage.Foreground,
    inverseOnSurface = Garage.Background,
    outline = Garage.Input,
    outlineVariant = Garage.Border,
    error = Garage.Loss,
    onError = Color(0xFF1A0606),
    errorContainer = Color(0xFF3A1515),
    onErrorContainer = Color(0xFFFFD6D6),
    scrim = Color.Black,
)

val SairaCondensed = FontFamily(
    Font(R.font.saira_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.saira_condensed_bold, FontWeight.Bold),
)

/** Variable font: the weight axis follows each [Font]'s weight. */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans, FontWeight.Normal),
    Font(R.font.instrument_sans, FontWeight.Medium),
    Font(R.font.instrument_sans, FontWeight.SemiBold),
    Font(R.font.instrument_sans, FontWeight.Bold),
)

private val typography = Typography().run {
    fun TextStyle.heading(weight: FontWeight = FontWeight.SemiBold) =
        copy(fontFamily = SairaCondensed, fontWeight = weight, letterSpacing = 0.02.em)

    fun TextStyle.body() = copy(fontFamily = InstrumentSans)

    Typography(
        displayLarge = displayLarge.heading(FontWeight.Bold),
        displayMedium = displayMedium.heading(FontWeight.Bold),
        displaySmall = displaySmall.heading(FontWeight.Bold),
        headlineLarge = headlineLarge.heading(FontWeight.Bold),
        headlineMedium = headlineMedium.heading(),
        headlineSmall = headlineSmall.heading(),
        titleLarge = titleLarge.heading(),
        titleMedium = titleMedium.body().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.body().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.body(),
        bodyMedium = bodyMedium.body(),
        bodySmall = bodySmall.body(),
        labelLarge = labelLarge.body().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.body(),
        labelSmall = labelSmall.body(),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun GarageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
