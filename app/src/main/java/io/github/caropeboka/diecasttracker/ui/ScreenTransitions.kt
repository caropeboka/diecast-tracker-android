package io.github.caropeboka.diecasttracker.ui

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute

/** The [androidx.compose.animation.SharedTransitionLayout] around the screens, for [sharedDiecastCard]. */
val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** The screen's own enter/exit animation, for [sharedDiecastCard]. */
val LocalScreenVisibility = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * How one screen gives way to another, the way Android itself moves between screens:
 * - a deeper screen opened without a tap (the launcher shortcut) comes in from a tenth of the width to the right, fading in at once, and settles with a long,
 *   soft finish (the system's own "open" animation); the one below drifts a tenth to the left. Back mirrors it:
 *   the screen fades away quickly while drifting right.
 * - a diecast opened from the collection, the dashboard or the one in hand in the 3D garage grows out of its card,
 *   and shrinks back into it (Material's container transform, see [sharedDiecastCard]); the 3D garage grows out of
 *   the dashboard's button the same way ([sharedGarage]).
 * - every other screen opened with a tap grows out of what was tapped and shrinks back into it ([RevealedScreen]);
 *   the centre button's choices go back into the button.
 * - between the bottom bar's tabs a quick cross-fade in place, so the bar itself stays put.
 * - the back gesture scrubs a shrink: the screen gets smaller and moves with the finger, the one below shows around
 *   it. Lifting the finger finishes it, sliding back cancels it.
 */
object ScreenTransitions {
    private val TABS = listOf(CollectionRoute::class, DashboardRoute::class, ExploreRoute::class, DexRoute::class)

    /** Screens with diecast cards that grow into the diecast's page. */
    private val CARDS = listOf(CollectionRoute::class, DashboardRoute::class, GarageRoute::class)

    private const val TAB_FADE = 220

    /** Android's activity_open_enter / activity_close_exit timing. */
    private const val SLIDE = 450
    private const val FADE = 83
    private const val FADE_IN_DELAY = 50
    private const val FADE_OUT_DELAY = 35

    /** How far screens drift: a tenth of the width. */
    private const val SHIFT = 10

    /** The card growing into the diecast's page. */
    internal const val CONTAINER = 380

    /** Android's fast_out_extra_slow_in: off at once, then a long soft landing. */
    private val EMPHASIZED = PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )

    /** For the back gesture: the screen stays solid while it shrinks and only fades in the last stretch. */
    private val LATE = Easing { ((it - 0.6f) / 0.4f).coerceIn(0f, 1f) }

    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            // Fades in on top of the old tab, which stays until it is covered: no dip in the bar halfway.
            betweenTabs() -> fadeIn(tween(TAB_FADE))
            // The page grows out of the card in the overlay.
            openingCard() || revealing() -> EnterTransition.None
            else -> slideIntoContainer(SlideDirection.Start, tween(SLIDE, easing = EMPHASIZED)) { it / SHIFT } +
                fadeIn(tween(FADE, FADE_IN_DELAY, LinearEasing))
        }
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            betweenTabs() -> hold(TAB_FADE)
            openingCard() || revealing() -> hold(CONTAINER)
            // Solid underneath; the new screen covers it within a few frames.
            else -> slideOutOfContainer(SlideDirection.Start, tween(SLIDE, easing = EMPHASIZED)) { it / SHIFT }
        }
    }

    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        when {
            // Already there underneath; the screen above fades away or shrinks into its card.
            betweenTabs() || closingCard() || unrevealing() -> EnterTransition.None
            else -> slideIntoContainer(SlideDirection.End, tween(SLIDE, easing = EMPHASIZED)) { it / SHIFT }
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        when {
            betweenTabs() -> fadeOut(tween(TAB_FADE))
            closingCard() || unrevealing() -> hold(CONTAINER)
            else -> slideOutOfContainer(SlideDirection.End, tween(SLIDE, easing = EMPHASIZED)) { it / SHIFT } +
                fadeOut(tween(FADE, FADE_OUT_DELAY, LinearEasing))
        }
    }

    /** The gesture scrubs these, so they move evenly with the finger. */
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        if (betweenTabs() || closingCard() || unrevealing()) EnterTransition.None else scaleIn(tween(SLIDE, easing = LinearEasing), initialScale = 0.95f)
    }

    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { edge ->
        when {
            betweenTabs() -> fadeOut(tween(TAB_FADE, easing = LinearEasing))
            closingCard() || unrevealing() -> hold(CONTAINER)
            else -> scaleOut(tween(SLIDE, easing = LinearEasing), targetScale = 0.9f) +
                // Towards the side the finger moves to.
                slideOutHorizontally(tween(SLIDE, easing = LinearEasing)) { if (edge == BackEventCompat.EDGE_RIGHT) -it / SHIFT else it / SHIFT } +
                fadeOut(tween(SLIDE, easing = LATE))
        }
    }

    /** Stays fully visible for [millis], then goes: for a screen that something else covers or grows out of. */
    internal fun hold(millis: Int) = fadeOut(tween(durationMillis = 1, delayMillis = millis))

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
        initialState.isTab() && targetState.isTab()

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.openingCard() = growsInto(initialState, targetState)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.closingCard() = growsInto(targetState, initialState)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.revealing() = Reveals.link(targetState)?.source == initialState.id

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.unrevealing() = Reveals.link(initialState)?.source == targetState.id

    /** Whether [to], opened from [from] with a tap, grows out of what was tapped: not a tab, nor a card's page. */
    fun reveals(from: NavBackStackEntry, to: NavBackStackEntry) = !to.isTab() && !growsInto(from, to)

    /** Whether [to] grows out of something tapped on [from]. */
    private fun growsInto(from: NavBackStackEntry, to: NavBackStackEntry) =
        to.isDiecastPage() && CARDS.any { from.destination.hasRoute(it) } ||
            to.destination.hasRoute(GarageRoute::class) && from.destination.hasRoute(DashboardRoute::class)

    private fun NavBackStackEntry.isTab() = TABS.any { destination.hasRoute(it) }

    private fun NavBackStackEntry.isDiecastPage() =
        destination.hasRoute(DiecastEditRoute::class) && toRoute<DiecastEditRoute>().id != 0L
}

/**
 * On a diecast card or row: the container diecast [id]'s page grows out of. It stays as it is underneath while the
 * page fades in over it. Only one on a screen at a time.
 */
@Composable
fun sharedDiecastCard(id: Long): Modifier = sharedContainer("diecast-$id", page = false)

/** On the diecast's page: fades in over its card while growing out of it, and out again while shrinking back. */
@Composable
fun sharedDiecastPage(id: Long): Modifier = sharedContainer("diecast-$id", page = true)

/**
 * On the button that opens the 3D garage ([page] false), and on the garage itself. Unlike a card, the button's label
 * would show blown up under the garage, so it fades out at once and back in only as the garage has shrunk into it.
 */
@Composable
fun sharedGarage(page: Boolean): Modifier = sharedContainer("garage", page, label = !page)

@Composable
private fun sharedContainer(key: String, page: Boolean, label: Boolean = false): Modifier {
    val shared = LocalSharedTransition.current ?: return Modifier
    val visibility = LocalScreenVisibility.current ?: return Modifier
    val shape = MaterialTheme.shapes.large
    val duration = ScreenTransitions.CONTAINER

    return with(shared) {
        Modifier.sharedBounds(
            rememberSharedContentState(key),
            visibility,
            enter = when {
                page -> fadeIn(tween(duration / 2))
                label -> fadeIn(tween(duration / 4, delayMillis = duration * 3 / 4))
                else -> EnterTransition.None
            },
            exit = when {
                page -> fadeOut(tween(duration / 2, delayMillis = duration / 4))
                label -> fadeOut(tween(duration / 4))
                else -> ScreenTransitions.hold(duration)
            },
            boundsTransform = BoundsTransform { _, _ -> tween(duration, easing = FastOutSlowInEasing) },
            // Laid out at full size and scaled, top first: the page's photo shows while it is still card-sized.
            resizeMode = ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            zIndexInOverlay = if (page) 1f else 0f,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }
}
