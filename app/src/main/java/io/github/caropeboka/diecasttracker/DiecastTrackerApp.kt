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
import io.github.caropeboka.diecasttracker.data.backup.BackupExporter
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.dex.ChecklistReader
import io.github.caropeboka.diecasttracker.data.dex.DexCustom
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.DexSync
import io.github.caropeboka.diecasttracker.data.dex.DexWork
import io.github.caropeboka.diecasttracker.data.dex.WikiCatalog
import io.github.caropeboka.diecasttracker.data.places.DeviceLocation
import io.github.caropeboka.diecasttracker.data.places.Nominatim
import io.github.caropeboka.diecasttracker.data.places.PlaceLookup
import io.github.caropeboka.diecasttracker.data.price.PriceChecker
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
import io.github.caropeboka.diecasttracker.data.scan.ScanWork
import io.github.caropeboka.diecasttracker.data.scan.StoreCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Everything the screens share, created once per process. */
class AppContainer(context: Context) {
    /** For work that must outlive a screen (imports, clean-up). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = AppDatabase.build(context)
    val photos = PhotoStore(context)
    val dexMatcher = DexMatcher(database.dex())
    val repository = DiecastRepository(database, photos, dexMatcher = dexMatcher)
    val prefs = UserPrefs(context)
    val backupImporter = BackupImporter(context, database, repository, photos, scope) {
        if (dex.countMissingImages() > 0) DexWork.downloadImages(context, prefs.dexCellular.first())
    }
    val backupExporter = BackupExporter(context, database, photos, scope, onBackup = prefs::setLastBackup)
    val messages = Messages()

    val http = OkHttpClient()
    val aiSettings = AiSettings(context)
    val gemini = GeminiClient(http, aiSettings)
    val scanner = DiecastScanner(aiSettings.gemini, gemini, photos)
    val matcher = CollectionMatcher(repository)

    private val userAgent = "DiecastTracker-Android/${versionName(context)} (personal diecast collection tracker)"

    val location = DeviceLocation(context)
    val nominatim = Nominatim(http, database.places(), userAgent)
    val places = PlaceLookup(database.places(), nominatim)

    val wiki = WikiCatalog(http, userAgent)
    val dexSync = DexSync(database, wiki, dexMatcher, context.filesDir)
    val dex = DexRepository(database, dexMatcher)
    val dexCustom = DexCustom(database, dexMatcher, photos, context.filesDir)
    val checklist = ChecklistReader(aiSettings.gemini, gemini)
    val priceChecker = PriceChecker(aiSettings.gemini, gemini, database)
    val scanQueue = ScanQueue(database, photos, scanner)
    val storeCheck = StoreCheck(database, photos, scanner, matcher, repository)

    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"
}

class DiecastTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch(Dispatchers.IO) {
            container.storeCheck.prune(before = System.currentTimeMillis() - StoreCheck.KEEP_MS)
            container.repository.housekeeping()
            container.backupExporter.housekeeping()
            container.database.places().pruneCache(before = System.currentTimeMillis() - Nominatim.REVERSE_TTL_MS)
            // Thumbnails left from an earlier sync: carry on when the network allows.
            if (container.dex.countMissingImages() > 0) {
                DexWork.downloadImages(this@DiecastTrackerApp, container.prefs.dexCellular.first())
            }
        }
        // Photos waiting in the bulk scan queue: read them now, or as soon as an API key is set.
        container.scope.launch {
            container.scanner.enabled.collect { enabled ->
                if (enabled && container.scanQueue.hasPending() && !container.prefs.bulkScanPaused.first()) {
                    ScanWork.start(this@DiecastTrackerApp)
                }
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as DiecastTrackerApp).container
