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
import io.github.caropeboka.diecasttracker.ui.edit.DiecastEditScreen
import io.github.caropeboka.diecasttracker.ui.settings.AiSettingsScreen
import kotlinx.serialization.Serializable

@Serializable
data object CollectionRoute

/**
 * [id] 0 = a new diecast, which starts in [status] (a status key; owned by default). [scan] opens the camera
 * straight away so the AI can fill the form (the collection's Scan button).
 */
@Serializable
data class DiecastEditRoute(val id: Long = 0, val status: String? = null, val scan: Boolean = false)

@Serializable
data object DataRoute

/** [fromScan]: opened from the Scan button before a key was set. */
@Serializable
data class AiSettingsRoute(val fromScan: Boolean = false)

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

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        NavHost(nav, startDestination = CollectionRoute) {
            composable<CollectionRoute> {
                CollectionScreen(
                    onAdd = dropUnlessResumed { nav.navigate(DiecastEditRoute()) },
                    onScan = dropUnlessResumed { nav.navigate(DiecastEditRoute(scan = true)) },
                    onOpen = { id -> nav.navigate(DiecastEditRoute(id = id)) },
                    onData = dropUnlessResumed { nav.navigate(DataRoute) },
                    onAiSettings = { fromScan -> nav.navigate(AiSettingsRoute(fromScan)) },
                )
            }
            composable<DiecastEditRoute> {
                DiecastEditScreen(
                    onClose = { nav.popBackStack<DiecastEditRoute>(inclusive = true) },
                    onOpen = { id -> nav.navigate(DiecastEditRoute(id = id)) },
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
