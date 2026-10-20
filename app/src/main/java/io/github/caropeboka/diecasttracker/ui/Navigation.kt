package io.github.caropeboka.diecasttracker.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.ui.collection.CollectionScreen
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.data.DataScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexCatalogScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexMatchesScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexScreen
import io.github.caropeboka.diecasttracker.ui.edit.DiecastEditScreen
import io.github.caropeboka.diecasttracker.ui.explore.ExploreScreen
import io.github.caropeboka.diecasttracker.ui.settings.AiSettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object CollectionRoute

/**
 * [id] 0 = a new diecast, which starts in [status] (a status key; owned by default). [scan] opens the camera
 * straight away so the AI can fill the form (the collection's Scan button). [dexItem] fills a new diecast from
 * that dex entry and links it.
 */
@Serializable
data class DiecastEditRoute(val id: Long = 0, val status: String? = null, val scan: Boolean = false, val dexItem: Long = 0)

@Serializable
data object DataRoute

/** [fromScan]: opened from the Scan button before a key was set. */
@Serializable
data class AiSettingsRoute(val fromScan: Boolean = false)

/** "Peta jelajah". */
@Serializable
data object ExploreRoute

@Serializable
data object DexRoute

@Serializable
data class DexCatalogRoute(val id: Long)

/** "Cocokkan Dex". */
@Serializable
data object DexMatchesRoute

@Composable
fun DiecastTrackerRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val messages = LocalContext.current.container.messages

    LaunchedEffect(messages) {
        messages.flow.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }

    // Tabs keep their state (scroll, filters) and Back from any tab returns to the collection.
    val openTab = { tab: MainTab ->
        val route: Any = when (tab) {
            MainTab.Collection -> CollectionRoute
            MainTab.Explore -> ExploreRoute
            MainTab.Dex -> DexRoute
        }
        nav.navigate(route) {
            popUpTo<CollectionRoute> { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val openDiecast = { id: Long -> nav.navigate(DiecastEditRoute(id = id)) }
    val openCatalog = { id: Long -> nav.navigate(DexCatalogRoute(id)) }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        NavHost(nav, startDestination = CollectionRoute) {
            composable<CollectionRoute> {
                CollectionScreen(
                    onTab = openTab,
                    onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                    onScan = dropUnlessResumed { nav.navigate(DiecastEditRoute(scan = true)) },
                    onOpen = openDiecast,
                    onData = dropUnlessResumed { nav.navigate(DataRoute) },
                    onAiSettings = { fromScan -> nav.navigate(AiSettingsRoute(fromScan)) },
                )
            }
            composable<ExploreRoute> {
                ExploreScreen(onTab = openTab, onOpen = openDiecast)
            }
            composable<DexRoute> {
                DexScreen(onTab = openTab, onOpen = openCatalog, onMatches = dropUnlessResumed { nav.navigate(DexMatchesRoute) })
            }
            composable<DexCatalogRoute> {
                DexCatalogScreen(
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onAdd = { item, status -> nav.navigate(DiecastEditRoute(status = status.key, dexItem = item)) },
                    onOpenDiecast = openDiecast,
                    onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                )
            }
            composable<DexMatchesRoute> {
                DexMatchesScreen(
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onOpenDiecast = openDiecast,
                    onOpenCatalog = openCatalog,
                )
            }
            composable<DiecastEditRoute> {
                DiecastEditScreen(
                    onClose = { nav.popBackStack<DiecastEditRoute>(inclusive = true) },
                    onOpen = { id -> nav.navigate(DiecastEditRoute(id = id)) },
                    onOpenCatalog = openCatalog,
                )
            }
            composable<DataRoute> {
                DataScreen(onBack = dropUnlessResumed { nav.popBackStack() })
            }
            composable<AiSettingsRoute> {
                AiSettingsScreen(onClose = { nav.popBackStack<AiSettingsRoute>(inclusive = true) })
            }
        }
    }
}
