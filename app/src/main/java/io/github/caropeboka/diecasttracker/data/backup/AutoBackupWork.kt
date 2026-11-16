package io.github.caropeboka.diecasttracker.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.caropeboka.diecasttracker.container
import java.util.concurrent.TimeUnit

/**
 * Runs [AutoBackup] every [AutoBackup.EVERY_DAYS] days, with the app closed too, while the battery and the storage
 * aren't low. A try that wrote nowhere is tried again once, half an hour later; after that the next week's run, the
 * screen's "Back up now" and the reminder on the collection take over.
 */
object AutoBackupWork {
    private const val NAME = "auto-backup"
    private const val ATTEMPTS = 2
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Keeps the weekly run scheduled while [on]; the first one comes [AutoBackup.EVERY_DAYS] days after [lastAt] (at once
     * when that has passed). Already scheduled: left as it is.
     */
    fun schedule(context: Context, on: Boolean, lastAt: Long?, now: Long = System.currentTimeMillis()) {
        val work = WorkManager.getInstance(context)
        if (!on) {
            work.cancelUniqueWork(NAME)
            return
        }
        val delay = lastAt?.let { (it + AutoBackup.EVERY_DAYS * DAY_MILLIS - now).coerceAtLeast(0) } ?: 0
        val request = PeriodicWorkRequestBuilder<Worker>(AutoBackup.EVERY_DAYS, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints(requiresBatteryNotLow = true, requiresStorageNotLow = true))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val written = applicationContext.container.autoBackup.run(whenBusy = true)
            return if (written || runAttemptCount + 1 >= ATTEMPTS) Result.success() else Result.retry()
        }
    }
}
