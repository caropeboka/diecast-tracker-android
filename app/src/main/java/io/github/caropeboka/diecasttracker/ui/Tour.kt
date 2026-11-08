package io.github.caropeboka.diecasttracker.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.AppLanguage
import io.github.caropeboka.diecasttracker.data.locale.AppLocales
import io.github.caropeboka.diecasttracker.ui.settings.phoneLanguage
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** What the first-run tour points at; each piece says where it is with [tourTarget]. */
enum class TourTarget { Collection, Create, Garage, Map, Dex, Settings }

/** The tour in order: what each step points at (nothing: a card in the middle) and what it says. */
enum class TourStep(val target: TourTarget?, @StringRes val title: Int, @StringRes val text: Int) {
    Welcome(null, R.string.tour_welcome_title, R.string.tour_welcome_text),
    Collection(TourTarget.Collection, R.string.tour_collection_title, R.string.tour_collection_text),
    Create(TourTarget.Create, R.string.tour_create_title, R.string.tour_create_text),
    Garage(TourTarget.Garage, R.string.tour_garage_title, R.string.tour_garage_text),
    Map(TourTarget.Map, R.string.tour_map_title, R.string.tour_map_text),
    Dex(TourTarget.Dex, R.string.tour_dex_title, R.string.tour_dex_text),
    Settings(TourTarget.Settings, R.string.tour_settings_title, R.string.tour_settings_text),
    Ready(null, R.string.tour_ready_title, R.string.tour_ready_text),
}

/** The first-run tour: the step on screen (null when it isn't running) and where its targets are. */
@Stable
class Tour(step: TourStep? = null) {
    var step by mutableStateOf(step)
    internal val targets = mutableStateMapOf<TourTarget, Rect>()

    companion object {
        /** Kept through a language change on the first step, which recreates the activity. */
        val Saver: Saver<Tour, Int> = Saver(save = { it.step?.ordinal ?: -1 }, restore = { Tour(TourStep.entries.getOrNull(it)) })
    }
}

val LocalTour = staticCompositionLocalOf<Tour?> { null }

/** Where [target] is, from the root's top left, for the tour's spotlight. */
fun Modifier.tourTarget(tour: Tour?, target: TourTarget): Modifier = if (tour == null) {
    this
} else {
    onGloballyPositioned {
        val bounds = it.boundsInRoot()
        if (tour.targets[target] != bounds) tour.targets[target] = bounds
    }
}

/**
 * The tour over every screen, like a game's: the screen dims but for a spotlight on what the step is about, and a
 * card says what it does. Taps don't reach the screen; one on the spotlight goes on like "Next". Back goes a step back.
 */
@Composable
fun TourOverlay(tour: Tour, aiReady: Boolean, onAiSettings: () -> Unit, onFinish: () -> Unit) {
    val step = tour.step ?: return
    val density = LocalDensity.current
    val spot = spotFor(tour, step)
    val go = { to: TourStep? -> if (to == null) onFinish() else tour.step = to }
    val next = { go(TourStep.entries.getOrNull(step.ordinal + 1)) }
    val currentSpot by rememberUpdatedState(spot)
    val currentNext by rememberUpdatedState(next)

    BackHandler { go(TourStep.entries.getOrNull(step.ordinal - 1)) }

    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(300)) }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
        val height = constraints.maxHeight
        val middle = Offset(constraints.maxWidth / 2f, height / 2f)
        val hole by animateRectAsState(spot ?: Rect(middle, 0f), tween(380, easing = FastOutSlowInEasing), label = "spotlight")
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
            label = "pulse",
        )
        val round = step.target == TourTarget.Create
        val corner = with(density) { 14.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .pointerInput(Unit) { detectTapGestures { tap -> if (currentSpot?.contains(tap) == true) currentNext() } },
        ) {
            drawRect(Color.Black.copy(alpha = 0.74f))
            if (hole.width > 1f) {
                val radius = if (round) hole.minDimension / 2 else corner
                drawRoundRect(Color.Black, hole.topLeft, hole.size, CornerRadius(radius), blendMode = BlendMode.Clear)
                // A ring spreading out from it, so the eye finds it.
                val grow = 12.dp.toPx() * pulse
                val ring = hole.inflate(grow)
                drawRoundRect(
                    Garage.Brand.copy(alpha = 1f - pulse),
                    ring.topLeft,
                    ring.size,
                    CornerRadius(if (round) ring.minDimension / 2 else corner + grow),
                    style = Stroke(2.dp.toPx()),
                )
            }
        }

        AnimatedContent(
            step,
            Modifier.fillMaxSize(),
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 140)) + scaleIn(tween(240, delayMillis = 140), initialScale = 0.94f)) togetherWith
                    fadeOut(tween(120))
            },
            label = "tour card",
        ) { shownStep ->
            Box(Modifier.fillMaxSize()) {
                val target = spotFor(tour, shownStep)
                val gap = 16.dp
                val placement = with(density) {
                    when {
                        target == null -> Modifier.align(Alignment.Center)
                        // Below what's in the top half, above what's in the bottom half.
                        target.center.y < height / 2f -> Modifier.align(Alignment.TopCenter).padding(top = target.bottom.toDp() + gap)
                        else -> Modifier.align(Alignment.BottomCenter).padding(bottom = (height - target.top).toDp() + gap)
                    }
                }
                TourCard(
                    shownStep,
                    aiReady,
                    placement,
                    onSkip = onFinish,
                    onNext = { if (tour.step == shownStep) go(TourStep.entries.getOrNull(shownStep.ordinal + 1)) },
                    onAiSettings = onAiSettings,
                )
            }
        }
    }
}

/** The step's target with a little room around it; null for a step without one, or one not on screen. */
@Composable
private fun spotFor(tour: Tour, step: TourStep): Rect? {
    val room = with(LocalDensity.current) { 6.dp.toPx() }
    return step.target?.let { tour.targets[it] }?.inflate(room)
}

@Composable
private fun TourCard(
    step: TourStep,
    aiReady: Boolean,
    modifier: Modifier,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onAiSettings: () -> Unit,
) {
    Surface(
        modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp),
        shape = MaterialTheme.shapes.large,
        color = Garage.Popover,
        border = BorderStroke(1.dp, Garage.Brand.copy(alpha = 0.5f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.tour_step, step.ordinal + 1, TourStep.entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = Garage.Brand,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(step.title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(step.text), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
            if (step == TourStep.Welcome) LanguagePicker()
            if (step == TourStep.Ready && !aiReady) {
                Text(stringResource(R.string.tour_ai_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onAiSettings, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.tour_ai_settings)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Dots(step)
                Spacer(Modifier.weight(1f))
                if (step != TourStep.Ready) TextButton(onClick = onSkip) { Text(stringResource(R.string.tour_skip)) }
                Button(onClick = onNext, shape = MaterialTheme.shapes.medium) {
                    Text(
                        stringResource(
                            when (step) {
                                TourStep.Welcome -> R.string.tour_start
                                TourStep.Ready -> R.string.tour_done
                                else -> R.string.tour_next
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** Where the tour is: the current step a longer pill. */
@Composable
private fun Dots(step: TourStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TourStep.entries.forEach {
            Box(
                Modifier
                    .size(width = if (it == step) 14.dp else 6.dp, height = 6.dp)
                    .background(if (it.ordinal <= step.ordinal) Garage.Brand else Garage.Border, CircleShape),
            )
        }
    }
}

/** The first thing to choose; the screens switch language at once (Android 12 and older restart the screen). */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLocales.current(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.tour_language), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLanguage.entries.forEach { option ->
                FilterChip(
                    selected = language == option,
                    onClick = {
                        if (language == option) return@FilterChip
                        language = option
                        AppLocales.set(context, option)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (context as? Activity)?.recreate()
                    },
                    label = {
                        Text(if (option == AppLanguage.System) stringResource(R.string.settings_language_system_now, phoneLanguage(context)) else option.native)
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Garage.Brand.copy(alpha = 0.18f)),
                )
            }
        }
    }
}
