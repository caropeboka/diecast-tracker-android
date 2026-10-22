package io.github.caropeboka.diecasttracker.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.ui.check.CheckScreen
import io.github.caropeboka.diecasttracker.ui.collection.CollectionLink
import io.github.caropeboka.diecasttracker.ui.collection.CollectionScreen
import io.github.caropeboka.diecasttracker.ui.collection.CollectionViewModel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.dashboard.DashboardScreen
import io.github.caropeboka.diecasttracker.ui.data.DataScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexCatalogScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexMatchesScreen
import io.github.caropeboka.diecasttracker.ui.dex.DexScreen
import io.github.caropeboka.diecasttracker.ui.edit.DiecastEditScreen
import io.github.caropeboka.diecasttracker.ui.explore.ExploreScreen
import io.github.caropeboka.diecasttracker.ui.scan.BulkScanScreen
import io.github.caropeboka.diecasttracker.ui.settings.AiSettingsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

@Serializable
data object CollectionRoute

/** "Garasi": the dashboard. */
@Serializable
data object DashboardRoute

/**
 * [id] 0 = a new diecast, which starts in [status] (a status key; owned by default). [scan] opens the camera
 * straight away so the AI can fill the form (the collection's Scan button). [dexItem] fills a new diecast from
 * that dex entry and links it. [scanItem] starts from a bulk scan or store check photo and what the AI read from it.
 * [name] starts with that name (a store check typed by hand).
 */
@Serializable
data class DiecastEditRoute(
    val id: Long = 0,
    val status: String? = null,
    val scan: Boolean = false,
    val dexItem: Long = 0,
    val scanItem: Long = 0,
    val name: String? = null,
)

/** "Scan massal". */
@Serializable
data object BulkScanRoute

/** "Cek di toko": opens the camera straight away when the AI is on. */
@Serializable
data object CheckRoute

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

/** [openCheck]: the launcher shortcut "Cek di toko" was used. */
@Composable
fun DiecastTrackerRoot(openCheck: Flow<Unit> = emptyFlow()) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val messages = LocalContext.current.container.messages

    LaunchedEffect(messages) {
        messages.flow.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    LaunchedEffect(openCheck) {
        openCheck.collect { nav.navigate(CheckRoute) { launchSingleTop = true } }
    }

    // Tabs keep their state (scroll, filters) and Back from any tab returns to the collection.
    val openTab = { tab: MainTab ->
        val route: Any = when (tab) {
            MainTab.Collection -> CollectionRoute
            MainTab.Garage -> DashboardRoute
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
    // The collection is always at the bottom of the stack. Its filters live in its view model's own saved state
    // (not the entry's savedStateHandle, a different one), so the link goes through that view model.
    val openCollection = { link: CollectionLink ->
        val entry = nav.getBackStackEntry<CollectionRoute>()
        ViewModelProvider.create(entry, CollectionViewModel.Factory, entry.defaultViewModelCreationExtras)[CollectionViewModel::class]
            .show(link)
        openTab(MainTab.Collection)
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        NavHost(nav, startDestination = CollectionRoute) {
            composable<CollectionRoute> {
                CollectionScreen(
                    onTab = openTab,
                    onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                    onScan = dropUnlessResumed { nav.navigate(DiecastEditRoute(scan = true)) },
                    onOpen = openDiecast,
                    onData = dropUnlessResumed { nav.navigate(DataRoute) },
                    onBulkScan = dropUnlessResumed { nav.navigate(BulkScanRoute) },
                    onCheck = dropUnlessResumed { nav.navigate(CheckRoute) },
                    onAiSettings = { fromScan -> nav.navigate(AiSettingsRoute(fromScan)) },
                )
            }
            composable<CheckRoute> {
                CheckScreen(
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onOpen = openDiecast,
                    onAdd = { scanItem, name, status ->
                        nav.navigate(DiecastEditRoute(status = status?.key, scanItem = scanItem, name = name))
                    },
                    onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                )
            }
            composable<DashboardRoute> {
                DashboardScreen(
                    onTab = openTab,
                    onOpen = openDiecast,
                    onOpenCatalog = openCatalog,
                    onCollection = openCollection,
                    onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                    onBulkScan = dropUnlessResumed { nav.navigate(BulkScanRoute) },
                )
            }
            composable<BulkScanRoute> {
                BulkScanScreen(
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onOpen = openDiecast,
                    onComplete = { item -> nav.navigate(DiecastEditRoute(scanItem = item)) },
                    onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
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
                    onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
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
