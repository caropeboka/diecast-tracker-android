package io.github.caropeboka.diecasttracker.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
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
import io.github.caropeboka.diecasttracker.ui.garage.GarageScreen
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
 * straight away (the bottom bar's "Scan dengan AI"); it reopens for every side until Back, then the AI reads them
 * all on "Pindai dengan AI". [dexItem] fills a new diecast from
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

/** The 3D shelves (G0 trial). */
@Serializable
data object GarageRoute

/** [openCheck]: the launcher shortcut "Cek di toko" was used. */
@Composable
fun DiecastTrackerRoot(openCheck: Flow<Unit> = emptyFlow()) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val container = LocalContext.current.container
    val messages = container.messages
    val scanEnabled by container.scanner.enabled.collectAsStateWithLifecycle(initialValue = false)

    LaunchedEffect(messages) {
        messages.flow.collect {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(it)
        }
    }
    // Screens grow out of the tap that opened them (ScreenReveal).
    val blob = with(LocalDensity.current) { 56.dp.toPx() }
    DisposableEffect(nav) { onDispose(nav.trackReveals(blob, ScreenTransitions::reveals)) }
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

    // The bottom bar's centre button, on every tab. Camera first, the AI fills the form; without a key, the AI
    // settings explain how to turn scanning on.
    val menu = remember { CreateMenu() }
    val create = CreateActions(
        scanEnabled = scanEnabled,
        onScan = { nav.navigate(if (scanEnabled) DiecastEditRoute(scan = true) else AiSettingsRoute(fromScan = true)) },
        onAdd = { nav.navigate(DiecastEditRoute()) },
        onCheck = { nav.navigate(CheckRoute) },
        menu = menu,
    )

    // Lets a diecast card grow into its diecast's page, the dashboard's button into the 3D garage, and every other
    // screen out of what opened it (see ScreenTransitions).
    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSnackbar provides snackbar,
            LocalCreateActions provides create,
            LocalSharedTransition provides this,
        ) {
            Box(Modifier.fillMaxSize().noteTaps()) {
                NavHost(
                    nav,
                    startDestination = CollectionRoute,
                    enterTransition = ScreenTransitions.enter,
                    exitTransition = ScreenTransitions.exit,
                    popEnterTransition = ScreenTransitions.popEnter,
                    popExitTransition = ScreenTransitions.popExit,
                    predictivePopEnterTransition = ScreenTransitions.predictivePopEnter,
                    predictivePopExitTransition = ScreenTransitions.predictivePopExit,
                ) {
                    screen<CollectionRoute> {
                        CollectionScreen(
                            onTab = openTab,
                            onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                            onOpen = openDiecast,
                            onData = dropUnlessResumed { nav.navigate(DataRoute) },
                            onBulkScan = dropUnlessResumed { nav.navigate(BulkScanRoute) },
                            onCheck = dropUnlessResumed { nav.navigate(CheckRoute) },
                            onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                        )
                    }
                    screen<CheckRoute> {
                        CheckScreen(
                            onBack = dropUnlessResumed { nav.popBackStack() },
                            onOpen = openDiecast,
                            onAdd = { scanItem, name, status ->
                                nav.navigate(DiecastEditRoute(status = status?.key, scanItem = scanItem, name = name))
                            },
                            onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                        )
                    }
                    screen<DashboardRoute> {
                        DashboardScreen(
                            onTab = openTab,
                            onOpen = openDiecast,
                            onOpenCatalog = openCatalog,
                            onCollection = openCollection,
                            onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                            onBulkScan = dropUnlessResumed { nav.navigate(BulkScanRoute) },
                            onShelves = dropUnlessResumed { nav.navigate(GarageRoute) },
                        )
                    }
                    screen<GarageRoute> {
                        GarageScreen(onBack = dropUnlessResumed { nav.popBackStack() }, onOpen = openDiecast)
                    }
                    screen<BulkScanRoute> {
                        BulkScanScreen(
                            onBack = dropUnlessResumed { nav.popBackStack() },
                            onOpen = openDiecast,
                            onComplete = { item -> nav.navigate(DiecastEditRoute(scanItem = item)) },
                            onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                        )
                    }
                    screen<ExploreRoute> {
                        ExploreScreen(onTab = openTab, onOpen = openDiecast)
                    }
                    screen<DexRoute> {
                        DexScreen(onTab = openTab, onOpen = openCatalog, onMatches = dropUnlessResumed { nav.navigate(DexMatchesRoute) })
                    }
                    screen<DexCatalogRoute> {
                        DexCatalogScreen(
                            onBack = dropUnlessResumed { nav.popBackStack() },
                            onAdd = { item, status -> nav.navigate(DiecastEditRoute(status = status.key, dexItem = item)) },
                            onOpenDiecast = openDiecast,
                            onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                        )
                    }
                    screen<DexMatchesRoute> {
                        DexMatchesScreen(
                            onBack = dropUnlessResumed { nav.popBackStack() },
                            onOpenDiecast = openDiecast,
                            onOpenCatalog = openCatalog,
                        )
                    }
                    screen<DiecastEditRoute> {
                        DiecastEditScreen(
                            onClose = { nav.popBackStack<DiecastEditRoute>(inclusive = true) },
                            onOpen = { id -> nav.navigate(DiecastEditRoute(id = id)) },
                            onOpenCatalog = openCatalog,
                            onAiSettings = dropUnlessResumed { nav.navigate(AiSettingsRoute()) },
                        )
                    }
                    screen<DataRoute> {
                        DataScreen(onBack = dropUnlessResumed { nav.popBackStack() })
                    }
                    screen<AiSettingsRoute> {
                        AiSettingsScreen(onClose = { nav.popBackStack<AiSettingsRoute>(inclusive = true) })
                    }
                }
                // Over the screen and its bottom bar.
                CreateMenuOverlay(create)
            }
        }
    }
}

/** A destination whose screen can grow out of what opened it, with its enter/exit for shared bounds. */
private inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalScreenVisibility provides this) {
            RevealedScreen(entry) { content(entry) }
        }
    }
}
