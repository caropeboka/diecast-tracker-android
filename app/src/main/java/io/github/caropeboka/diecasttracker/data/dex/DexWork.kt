package io.github.caropeboka.diecasttracker.data.dex

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.caropeboka.diecasttracker.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** How one year's sync went. */
@Serializable
data class YearResult(
    val year: Int,
    val error: String? = null,
    val items: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val linked: Int = 0,
)

/** Where a sync in the background stands. */
sealed interface SyncProgress {
    data object Idle : SyncProgress

    data class Running(val year: Int?, val done: Int, val total: Int) : SyncProgress

    data class Finished(val source: WikiSource?, val results: List<YearResult>) : SyncProgress
}

/** Where the thumbnail download stands. */
enum class ImageWork { Idle, Waiting, Retrying, Running }

/**
 * Dex work that outlives the screen: syncing years from a wiki, then downloading thumbnails over Wi-Fi (or mobile
 * data, when the collector allows it). The image CDN refuses foreign referers, so thumbnails are fetched once and kept.
 */
object DexWork {
    private const val TAG = "DexWork"
    private const val SYNC = "dex-sync"
    private const val IMAGES = "dex-images"

    private const val SOURCE = "source"
    private const val YEARS = "years"
    private const val YEAR = "year"
    private const val DONE = "done"
    private const val TOTAL = "total"
    private const val RESULTS = "results"

    fun sync(context: Context, source: WikiSource, years: List<Int>) {
        val request = OneTimeWorkRequestBuilder<DexSyncWorker>()
            .setInputData(workDataOf(SOURCE to source.key, YEARS to years.toIntArray()))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SYNC, ExistingWorkPolicy.KEEP, request)
    }

    /** [replace]: the network setting changed, so the waiting job is rescheduled with the new constraint. */
    fun downloadImages(context: Context, cellular: Boolean, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DexImageWorker>()
            .setConstraints(Constraints(requiredNetworkType = if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMAGES, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun observeSync(context: Context): Flow<SyncProgress> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SYNC).map { infos ->
            val info = infos.firstOrNull() ?: return@map SyncProgress.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> SyncProgress.Running(
                    year = info.progress.getInt(YEAR, 0).takeIf { it > 0 },
                    done = info.progress.getInt(DONE, 0),
                    total = info.progress.getInt(TOTAL, 0),
                )
                WorkInfo.State.SUCCEEDED -> SyncProgress.Finished(
                    WikiSource.fromKey(info.outputData.getString(SOURCE)),
                    info.outputData.getString(RESULTS)?.let { runCatching { Json.decodeFromString<List<YearResult>>(it) }.getOrNull() }.orEmpty(),
                )
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> SyncProgress.Idle
            }
        }

    fun observeImages(context: Context): Flow<ImageWork> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IMAGES).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: return@map ImageWork.Idle
            when {
                info.state == WorkInfo.State.RUNNING -> ImageWork.Running
                // Enqueued again after a failed attempt: waiting out the back-off, not the network.
                info.runAttemptCount > 0 -> ImageWork.Retrying
                else -> ImageWork.Waiting
            }
        }

    class DexSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val container = applicationContext.container
            val source = WikiSource.fromKey(inputData.getString(SOURCE)) ?: return Result.failure()
            val years = inputData.getIntArray(YEARS) ?: return Result.failure()
            val results = mutableListOf<YearResult>()

            years.forEachIndexed { index, year ->
                setProgress(workDataOf(YEAR to year, DONE to index, TOTAL to years.size))
                results += try {
                    val result = container.dexSync.sync(source, year)
                    YearResult(year, items = result.items, added = result.added, removed = result.removed, linked = result.linked)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DexSyncException) {
                    YearResult(year, error = e.message)
                } catch (e: Exception) {
                    // A bug, not the wiki: still reported on the screen rather than failing the whole run silently.
                    Log.e(TAG, "Sync of $year failed", e)
                    YearResult(year, error = "Sinkron gagal (${e.message ?: e::class.simpleName}).")
                }
            }

            downloadImages(applicationContext, container.prefs.dexCellular.first())
            return Result.success(workDataOf(SOURCE to source.key, RESULTS to Json.encodeToString(results)))
        }
    }

    class DexImageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val sync = applicationContext.container.dexSync
            while (!isStopped) {
                // Connection trouble: WorkManager tries again later, with back-off.
                val remaining = sync.downloadImages() ?: return Result.retry()
                if (remaining == 0) break
            }
            return Result.success()
        }
    }
}
