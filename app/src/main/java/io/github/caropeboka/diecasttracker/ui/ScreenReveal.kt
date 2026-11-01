package io.github.caropeboka.diecasttracker.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlin.math.min
import kotlin.math.roundToInt

/** Part of the screen, in the root's coordinates, in the colour it has there. */
class Spot(val bounds: Rect, val color: Color)

/**
 * Screens grow out of what was tapped to open them and shrink back into it (Material's container transform, as a
 * card grows into its diecast's page): the element marked with [revealSource] under the finger, or else a
 * finger-sized spot where it went down. A screen opened without a recent tap (the launcher shortcut) slides in as
 * before. Kept in memory only: after the process was killed, Back slides.
 */
internal object Reveals {
    /**
     * How long a tap still counts as what opened the next screen: a menu or a sheet is a window of its own, so the tap
     * on its item isn't seen, only the one that opened it.
     */
    private const val TAP_MEMORY = 10_000L

    private class Press(val spot: Spot, val back: Spot?, val at: Long)

    /** Where [entry][link] came from, and where it goes back to (the ＋ button for its menu's choices). */
    class Link(val source: String, val from: Spot, val back: Spot)

    private var down = Offset.Unspecified
    private var downAt = 0L
    private var press: Press? = null

    /** Per entry id of a screen that grew out of something. */
    private val links = mutableStateMapOf<String, Link>()

    /** The screen growing right now; null while going back, so a screen shown again under one doesn't grow. */
    var growing by mutableStateOf<String?>(null)
        private set

    fun down(position: Offset, at: Long) {
        down = position
        downAt = at
    }

    fun press(spot: Spot, back: Spot?, at: Long) {
        press = Press(spot, back, at)
    }

    fun link(entry: NavBackStackEntry) = links[entry.id]

    /** The screen [entry] opened, with its link. */
    fun opened(entry: NavBackStackEntry) = links.entries.firstOrNull { it.value.source == entry.id }?.toPair()

    /** After every change of screen: [pushed] says [entry] is new on top of [source]. */
    fun changed(entry: NavBackStackEntry?, source: NavBackStackEntry?, pushed: Boolean, blob: Float) {
        growing = null
        if (!pushed || entry == null || source == null) return
        if (down == Offset.Unspecified || SystemClock.uptimeMillis() - downAt > TAP_MEMORY) return
        val pressed = press?.takeIf { it.at == downAt }
        val from = pressed?.spot ?: Spot(Rect(down, blob / 2), Garage.Card)
        links[entry.id] = Link(source.id, from, pressed?.back ?: from)
        // Used up: a screen opened later without a tap of its own slides.
        downAt = 0
        growing = entry.id
        entry.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                links.remove(entry.id)
            }
        })
    }
}

/** Watches [nav] for new screens and links them to the tap that opened them; [reveals] says which may grow. */
internal fun NavController.trackReveals(blob: Float, reveals: (from: NavBackStackEntry, to: NavBackStackEntry) -> Boolean): () -> Unit {
    // Every screen that has been on top: a pop (or a tab's stack restored) lands on one of them, a push never does.
    val known = HashSet<String>()
    val listener = NavController.OnDestinationChangedListener { controller, _, _ ->
        val entry = controller.currentBackStackEntry
        val source = controller.previousBackStackEntry
        val pushed = entry != null && entry.id !in known && source != null && reveals(source, entry)
        entry?.let { known += it.id }
        Reveals.changed(entry, source, pushed, blob)
    }
    addOnDestinationChangedListener(listener)
    return { removeOnDestinationChangedListener(listener) }
}

/** On the root, around every screen: notes where each finger goes down, without taking the touch. */
internal fun Modifier.noteTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { Reveals.down(it.position, it.uptimeMillis) }
        }
    }
}

/**
 * On something that opens a screen: the screen grows out of its bounds, painted [color] as it starts, and shrinks back
 * into [back] (by default these bounds again).
 */
@Composable
fun revealSource(color: Color = Garage.Card, back: Spot? = null): Modifier {
    val bounds = remember { arrayOf(Rect.Zero) }
    val currentBack by rememberUpdatedState(back)
    val currentColor by rememberUpdatedState(color)
    return Modifier
        .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                Reveals.press(Spot(bounds[0], currentColor), currentBack, down.uptimeMillis)
            }
        }
}

/** A rounded rectangle whose corners shrink to fit when it is small: a circle or pill at a button, rounded at full size. */
private object RevealShape : Shape {
    private val CORNER = 28.dp

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.minDimension / 2, with(density) { CORNER.toPx() })
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
    }
}

private const val DURATION = ScreenTransitions.CONTAINER

/** Material's scrim over the screen a container grows out of. */
private const val DIM = 0.32f

/**
 * Around every screen: grows it out of what opened it ([Reveals]) and shrinks it back on Back; on the screen
 * underneath, an invisible stand-in for the tapped element that the screen above grows out of and goes back into,
 * and a scrim while that happens.
 */
@Composable
fun RevealedScreen(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    val shared = LocalSharedTransition.current
    val visibility = LocalScreenVisibility.current
    if (shared == null || visibility == null) {
        content()
        return
    }
    val link = Reveals.link(entry)
    val opened = Reveals.opened(entry)
    val dim by visibility.transition.animateFloat({ tween(DURATION) }, label = "dim") {
        if (it != EnterExitState.Visible && opened != null) DIM else 0f
    }

    with(shared) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (link == null) {
                            Modifier
                        } else {
                            Modifier.sharedBounds(
                                rememberSharedContentState("reveal-${entry.id}"),
                                visibility,
                                // Shown again under a screen going back: there at once.
                                enter = if (Reveals.growing == entry.id) fadeIn(tween(DURATION / 3)) else EnterTransition.None,
                                // Solid while it shrinks: the stand-in fades in over it at the end.
                                exit = ScreenTransitions.hold(DURATION),
                                boundsTransform = RevealBounds,
                                resizeMode = ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
                                zIndexInOverlay = 1f,
                                clipInOverlayDuringTransition = OverlayClip(RevealShape),
                            )
                        },
                    ),
            ) {
                content()
            }
            opened?.let { (id, into) -> StandIn(id, into, visibility) }
        }
    }
}

private val RevealBounds = BoundsTransform { _, _ -> tween(DURATION, easing = FastOutSlowInEasing) }

/**
 * Where the tapped element was, in its colour while a screen grows out of it; on the way back it fades in over the
 * shrinking screen, so that ends as the element it came from.
 */
@Composable
private fun SharedTransitionScope.StandIn(id: String, link: Reveals.Link, visibility: AnimatedVisibilityScope) {
    val growing = Reveals.growing == id
    val spot = if (growing) link.from else link.back
    val density = LocalDensity.current
    val bounds = spot.bounds
    Box(
        Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .sharedBounds(
                rememberSharedContentState("reveal-$id"),
                visibility,
                enter = if (growing) EnterTransition.None else fadeIn(tween(DURATION * 3 / 10, delayMillis = DURATION * 6 / 10)),
                exit = ScreenTransitions.hold(DURATION),
                boundsTransform = RevealBounds,
                resizeMode = ResizeMode.RemeasureToBounds,
                zIndexInOverlay = if (growing) 0f else 2f,
                clipInOverlayDuringTransition = OverlayClip(RevealShape),
            )
            .background(if (isTransitionActive) spot.color else Color.Transparent, RevealShape),
    )
}
