package io.github.caropeboka.diecasttracker.data.scan

import android.content.Context
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
import io.github.caropeboka.diecasttracker.data.ai.AiOfflineException
import io.github.caropeboka.diecasttracker.data.ai.AiRetryLaterException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Where the AI's reading of the queue stands. */
sealed interface ScanWorkState {
    data object Idle : ScanWorkState

    /** Waiting to start, e.g. for a connection. */
    data object Queued : ScanWorkState

    data class Reading(val itemId: Long) : ScanWorkState

    /** The AI was busy, out of quota or unreachable: trying again at [at] (epoch millis). */
    data class Waiting(val at: Long) : ScanWorkState
}

/**
 * Reads the bulk scan queue in the background, one photo after another, even with the app closed (the web reads it
 * only while its page is open). A busy AI or used-up quota is waited out with growing pauses; without a connection it
 * waits for one.
 */
object ScanWork {
    private const val NAME = "bulk-scan"
    private const val ITEM = "item"

    fun start(context: Context) {
        val request = OneTimeWorkRequestBuilder<BulkScanWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Paused: a photo being read goes back in line. */
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    fun observe(context: Context): Flow<ScanWorkState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
            val info = infos.firstOrNull { !it.state.isFinished } ?: return@map ScanWorkState.Idle
            when {
                info.state == WorkInfo.State.RUNNING -> ScanWorkState.Reading(info.progress.getLong(ITEM, 0))
                info.runAttemptCount > 0 -> ScanWorkState.Waiting(info.nextScheduleTimeMillis)
                else -> ScanWorkState.Queued
            }
        }

    class BulkScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val container = applicationContext.container
            // Paused, or no key: the photos wait; the screen starts again when that changes.
            if (container.prefs.bulkScanPaused.first() || !container.scanner.isEnabled()) return Result.success()

            val queue = container.scanQueue
            while (!isStopped) {
                val item = queue.next() ?: return Result.success()
                setProgress(workDataOf(ITEM to item.id))
                try {
                    queue.process(item)
                } catch (_: AiRetryLaterException) {
                    return Result.retry()
                } catch (_: AiOfflineException) {
                    return Result.retry()
                }
            }
            return Result.success()
        }
    }
}
