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
import kotlinx.serialization.Serializable

@Serializable
data object CollectionRoute

/** [id] 0 = a new diecast, which starts in [status] (a status key; owned by default). */
@Serializable
data class DiecastEditRoute(val id: Long = 0, val status: String? = null)

@Serializable
data object DataRoute

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
                    onOpen = { id -> nav.navigate(DiecastEditRoute(id = id)) },
                    onData = dropUnlessResumed { nav.navigate(DataRoute) },
                )
            }
            composable<DiecastEditRoute> {
                DiecastEditScreen(onClose = { nav.popBackStack<DiecastEditRoute>(inclusive = true) })
            }
            composable<DataRoute> {
                DataScreen(onBack = dropUnlessResumed { nav.popBackStack() })
            }
        }
    }
}
