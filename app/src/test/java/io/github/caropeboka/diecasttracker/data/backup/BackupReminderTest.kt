package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.backup.BackupReminder.DAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupReminderTest {
    private val now = 1_000 * DAY_MILLIS

    @Test
    fun `due when never backed up or over thirty days ago`() {
        assertTrue(BackupReminder.isDue(5, BackupTimes(), now))
        assertTrue(BackupReminder.isDue(5, BackupTimes(last = now - 30 * DAY_MILLIS), now))
        assertFalse(BackupReminder.isDue(5, BackupTimes(last = now - 29 * DAY_MILLIS), now))
    }

    @Test
    fun `not due for an empty collection or while put off`() {
        assertFalse(BackupReminder.isDue(0, BackupTimes(), now))
        assertFalse(BackupReminder.isDue(5, BackupTimes(snoozedUntil = now + 1), now))
        assertTrue(BackupReminder.isDue(5, BackupTimes(snoozedUntil = now), now))
    }

    @Test
    fun `days are whole days`() {
        assertEquals(31, BackupReminder.daysSince(now - 31 * DAY_MILLIS - 5_000, now))
    }
}
