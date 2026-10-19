package io.github.caropeboka.diecasttracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.caropeboka.diecasttracker.ui.DiecastTrackerRoot
import io.github.caropeboka.diecasttracker.ui.theme.GarageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark only: light status and navigation bar icons on the Garage background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Opened from the launcher while the app already runs in a task started another way (e.g. "Open" in the
        // package installer after sideloading): bring that task back instead of stacking a fresh copy on it.
        if (!isTaskRoot && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }

        setContent {
            GarageTheme {
                DiecastTrackerRoot()
            }
        }
    }
}
