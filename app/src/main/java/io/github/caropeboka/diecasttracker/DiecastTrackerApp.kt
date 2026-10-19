package io.github.caropeboka.diecasttracker

import android.app.Application
import android.content.Context
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.AiSettings
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Everything the screens share, created once per process. */
class AppContainer(context: Context) {
    /** For work that must outlive a screen (imports, clean-up). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = AppDatabase.build(context)
    val photos = PhotoStore(context)
    val repository = DiecastRepository(database, photos)
    val prefs = UserPrefs(context)
    val backupImporter = BackupImporter(context, database, repository, photos, scope)
    val messages = Messages()

    val http = OkHttpClient()
    val aiSettings = AiSettings(context)
    val gemini = GeminiClient(http, aiSettings)
    val scanner = DiecastScanner(aiSettings.gemini, gemini, photos)
    val matcher = CollectionMatcher(repository)
}

class DiecastTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch(Dispatchers.IO) { container.repository.housekeeping() }
    }
}

val Context.container: AppContainer get() = (applicationContext as DiecastTrackerApp).container
