package io.github.caropeboka.diecasttracker

import android.app.Application
import android.content.Context
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.ai.AiSettings
import io.github.caropeboka.diecasttracker.data.ai.AiVision
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.OpenAiClient
import io.github.caropeboka.diecasttracker.data.backup.BackupExporter
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.decor.DecorMaker
import io.github.caropeboka.diecasttracker.data.decor.DecorRepository
import io.github.caropeboka.diecasttracker.data.decor.DecorStore
import io.github.caropeboka.diecasttracker.data.dex.ChecklistReader
import io.github.caropeboka.diecasttracker.data.dex.DexCustom
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.DexRepository
import io.github.caropeboka.diecasttracker.data.dex.DexSync
import io.github.caropeboka.diecasttracker.data.dex.DexWork
import io.github.caropeboka.diecasttracker.data.dex.WikiCatalog
import io.github.caropeboka.diecasttracker.data.locale.AppLocales
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.money.RateSource
import io.github.caropeboka.diecasttracker.data.places.DeviceLocation
import io.github.caropeboka.diecasttracker.data.places.Nominatim
import io.github.caropeboka.diecasttracker.data.places.PlaceLookup
import io.github.caropeboka.diecasttracker.data.price.PriceChecker
import io.github.caropeboka.diecasttracker.data.scan.ScanQueue
import io.github.caropeboka.diecasttracker.data.scan.ScanWork
import io.github.caropeboka.diecasttracker.data.scan.StoreCheck
import io.github.caropeboka.diecasttracker.data.shelf.ShelfRepository
import io.github.caropeboka.diecasttracker.data.studio.MlKitCutter
import io.github.caropeboka.diecasttracker.data.studio.StudioPhoto
import io.github.caropeboka.diecasttracker.util.Currency
import io.github.caropeboka.diecasttracker.util.Formats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Everything the screens share, created once per process. */
class AppContainer(context: Context) {
    /** For work that must outlive a screen (imports, clean-up). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val texts = Texts(context)
    val database = AppDatabase.build(context)
    val photos = PhotoStore(context)
    val dexMatcher = DexMatcher(database.dex())
    val repository = DiecastRepository(database, photos, texts, dexMatcher = dexMatcher)
    val shelves = ShelfRepository(database, texts)
    val prefs = UserPrefs(context)
    val backupImporter = BackupImporter(context, database, repository, photos, scope) {
        if (dex.countMissingImages() > 0) DexWork.downloadImages(context, prefs.dexCellular.first())
    }
    val backupExporter = BackupExporter(context, database, photos, scope, onBackup = prefs::setLastBackup)
    val messages = Messages()

    val http = OkHttpClient()
    val rateSource = RateSource(http)
    val aiSettings = AiSettings(context)
    val gemini = GeminiClient(http, aiSettings, texts)
    val openAi = OpenAiClient(http, texts)
    val vision = AiVision(aiSettings.setup, gemini, openAi, aiSettings, texts)
    val scanner = DiecastScanner(vision, photos, texts)
    val matcher = CollectionMatcher(repository)

    private val userAgent = "DiecastTracker-Android/${versionName(context)} (personal diecast collection tracker)"

    val location = DeviceLocation(context)
    val nominatim = Nominatim(http, database.places(), userAgent)
    val places = PlaceLookup(database.places(), nominatim)

    val wiki = WikiCatalog(http, userAgent, texts)
    val dexSync = DexSync(database, wiki, dexMatcher, texts, context.filesDir)
    val dex = DexRepository(database, dexMatcher)
    val dexCustom = DexCustom(database, dexMatcher, photos, texts, context.filesDir)
    val checklist = ChecklistReader(vision)
    val priceChecker = PriceChecker(vision, database, texts)
    val scanQueue = ScanQueue(database, photos, scanner, texts)
    val storeCheck = StoreCheck(database, photos, scanner, matcher, repository, texts)
    private val cutter = MlKitCutter(context)
    val studio = StudioPhoto(photos, texts, cutter)
    val decorStore = DecorStore(context.filesDir)
    val decorations = DecorRepository(database, decorStore)
    val decorMaker = DecorMaker(texts, cutter)

    /** Fetches the exchange rates when prices are shown in another currency and the last ones are over 12 hours old. */
    suspend fun refreshRates(force: Boolean = false): Boolean {
        val last = prefs.rates.first()
        if (!force && (prefs.currency.first() == Currency.IDR || last != null && System.currentTimeMillis() - last.fetchedAt < RATES_TTL_MS)) return true
        val fresh = rateSource.fetch() ?: return false
        prefs.setRates(fresh)
        return true
    }

    private fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"

    private companion object {
        const val RATES_TTL_MS = 12 * 60 * 60 * 1000L
    }
}

class DiecastTrackerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocales.wrap(base))

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Formats.locale = resources.configuration.locales[0]
        // Every price and date on the screens follows these; the rates are refreshed when another currency is used.
        container.scope.launch {
            combine(container.prefs.currency, container.prefs.dateStyle, container.prefs.rates) { currency, style, rates ->
                Formats.currency = currency
                Formats.dateStyle = style
                Formats.rates = rates
                currency
            }.collect { container.refreshRates() }
        }
        container.scope.launch(Dispatchers.IO) {
            container.storeCheck.prune(before = System.currentTimeMillis() - StoreCheck.KEEP_MS)
            container.repository.housekeeping()
            container.decorations.housekeeping()
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
