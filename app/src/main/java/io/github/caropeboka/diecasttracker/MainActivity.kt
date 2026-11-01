package io.github.caropeboka.diecasttracker

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.caropeboka.diecasttracker.data.locale.AppLocales
import io.github.caropeboka.diecasttracker.ui.DiecastTrackerRoot
import io.github.caropeboka.diecasttracker.ui.theme.GarageTheme
import io.github.caropeboka.diecasttracker.util.Formats
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    /** The launcher shortcut "Cek di toko" was used. */
    private val checkRequests = Channel<Unit>(Channel.CONFLATED)
    private val openCheck = checkRequests.receiveAsFlow()

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocales.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light status and navigation bar icons on the Garage background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A new language recreates the activity, so this is where dates and numbers learn it.
        Formats.locale = resources.configuration.locales[0]

        // Opened from the launcher while the app already runs in a task started another way (e.g. "Open" in the
        // package installer after sideloading): bring that task back instead of stacking a fresh copy on it.
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }

        // Restored after the process was killed: the back stack already holds the check screen.
        if (savedInstanceState == null) handleShortcut(intent)

        setContent {
            GarageTheme {
                DiecastTrackerRoot(openCheck)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShortcut(intent: Intent) {
        if (intent.action == ACTION_CHECK) checkRequests.trySend(Unit)
    }

    companion object {
        /** See `res/xml/shortcuts.xml`. */
        const val ACTION_CHECK = "io.github.caropeboka.diecasttracker.CHECK"
    }
}
