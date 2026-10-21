package io.github.caropeboka.diecasttracker.data.backup

/** Epoch millis; null: never backed up / not put off. */
data class BackupTimes(val last: Long? = null, val snoozedUntil: Long? = null)

/**
 * The collection lives only on the phone, so it asks for a backup now and then: when there is something to lose and
 * the last backup (if any) is over [INTERVAL_DAYS] days old. "Nanti" puts it off for [SNOOZE_DAYS] days.
 */
object BackupReminder {
    const val INTERVAL_DAYS = 30
    const val SNOOZE_DAYS = 7
    const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    fun isDue(diecasts: Int, times: BackupTimes, now: Long): Boolean =
        diecasts > 0 &&
            (times.snoozedUntil == null || now >= times.snoozedUntil) &&
            (times.last == null || now - times.last >= INTERVAL_DAYS * DAY_MILLIS)

    /** Whole days since [last]. */
    fun daysSince(last: Long, now: Long): Long = (now - last) / DAY_MILLIS
}
