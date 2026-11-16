package io.github.caropeboka.diecasttracker.data.backup

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoBackupTest {
    private val env = TestEnvironment()
    private var now = LocalDate.of(2026, 11, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 10 * HOUR
    private val exporter = BackupExporter(env.context, env.db, env.photos, CoroutineScope(Dispatchers.Unconfined), root = env.root, clock = { now })

    private var settings = AutoBackupSettings(on = true, folder = "folder", copy = "copy")
    private val records = mutableListOf<Pair<Long?, String?>>()
    private val folder = FakeFolder()
    private val copy = FakeCopy()

    private val auto = AutoBackup(
        exporter,
        env.texts,
        env.root,
        settings = { settings },
        record = { at, error -> records += at to error },
        folderAt = { folder },
        copyAt = { copy },
        clock = { now },
        zone = ZoneOffset.UTC,
    )

    @After
    fun tearDown() = env.close()

    @Test
    fun `only automatic backups beyond the newest three are outdated`() {
        val names = listOf(
            "backup-diecast-auto-2026-10-25.zip",
            "backup-diecast-auto-2026-11-08.zip",
            "backup-diecast-auto-2026-11-15.zip",
            "backup-diecast-auto-2026-11-15 (1).zip",
            "backup-diecast-auto-2026-11-01.zip",
            // Saved by hand, or something else entirely: never touched.
            "backup-diecast-2026-09-01.zip",
            "foto-mobil.jpg",
        )

        assertEquals(
            listOf("backup-diecast-auto-2026-11-01.zip", "backup-diecast-auto-2026-10-25.zip"),
            AutoBackup.outdated(names),
        )
        assertEquals(emptyList<String>(), AutoBackup.outdated(names.take(2)))
    }

    @Test
    fun `writes the backup into the folder and the copy, and keeps the newest three`() = runBlocking {
        env.repository.save(DiecastEntity(name = "1995 Mazda RX-7"))
        folder.files["backup-diecast-auto-2026-10-25.zip"] = byteArrayOf(1)
        folder.files["backup-diecast-auto-2026-11-01.zip"] = byteArrayOf(1)
        folder.files["backup-diecast-auto-2026-11-08.zip"] = byteArrayOf(1)
        folder.files["backup-diecast-2026-09-01.zip"] = byteArrayOf(1)

        assertTrue(auto.run())

        assertEquals(
            setOf(
                "backup-diecast-auto-2026-11-01.zip",
                "backup-diecast-auto-2026-11-08.zip",
                "backup-diecast-auto-2026-11-15.zip",
                "backup-diecast-2026-09-01.zip",
            ),
            folder.files.keys,
        )
        val written = folder.files.getValue("backup-diecast-auto-2026-11-15.zip")
        assertTrue("1995 Mazda RX-7" in manifest(written))
        assertTrue(written.contentEquals(copy.bytes))
        assertEquals(listOf(now to null), records)
        // The zip made in the cache is gone.
        assertFalse(env.root.resolve("auto-backup.zip").exists())
    }

    @Test
    fun `a second run on the same day writes over that day's backup`() = runBlocking {
        assertTrue(auto.run())
        env.repository.save(DiecastEntity(name = "Porsche 911 Rally"))
        assertTrue(auto.run())

        assertEquals(setOf("backup-diecast-auto-2026-11-15.zip"), folder.files.keys)
        assertTrue("Porsche 911 Rally" in manifest(folder.files.getValue("backup-diecast-auto-2026-11-15.zip")))
    }

    @Test
    fun `a place that fails doesn't stop the other, and says why`() = runBlocking {
        copy.failure = SecurityException("Permission Denial")

        assertTrue(auto.run())

        assertEquals(1, folder.files.size)
        val (at, error) = records.single()
        assertEquals(now, at)
        assertEquals("Salinan: file tidak bisa ditulis lagi. Pilih ulang.", error)
    }

    @Test
    fun `nothing written leaves no half file and no new time`() = runBlocking {
        folder.failAfterBytes = 10
        copy.failure = IOException("Tidak ada koneksi")

        assertFalse(auto.run())

        assertTrue(folder.files.isEmpty())
        val (at, error) = records.single()
        assertNull(at)
        assertNotNull(error)
        assertEquals(2, error!!.lines().size)
        assertTrue(error.lines()[1].endsWith("Tidak ada koneksi"))
    }

    @Test
    fun `off or without a place it doesn't run, unless asked to now`() = runBlocking {
        settings = settings.copy(on = false)
        assertFalse(auto.run())
        assertTrue(records.isEmpty())

        assertTrue(auto.run(now = true))
        assertEquals(1, records.size)

        settings = AutoBackupSettings(on = true)
        assertFalse(auto.run(now = true))
        assertEquals(1, records.size)
    }

    @Test
    fun `a place is on the phone, on Drive or elsewhere by its provider, not its app's name`() {
        // Found on the phone: a copy saved in the folder the save screen opened first, shown as "Drive".
        assertEquals(
            PlaceKind.Phone,
            BackupPlaces.kind("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FDiecast%20Tracker%2Fdiecast-tracker-backup.zip"),
        )
        assertEquals(PlaceKind.Phone, BackupPlaces.kind("content://com.android.providers.downloads.documents/document/msf%3A1234"))
        assertEquals(PlaceKind.Drive, BackupPlaces.kind("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dabc"))
        assertEquals(PlaceKind.Other, BackupPlaces.kind("content://com.dropbox.android.document/document/%2Fbackup.zip"))
    }

    @Test
    fun `a run while another is writing answers as asked, and writes nothing`() = runBlocking {
        val inside = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val held = AutoBackup(
            exporter,
            env.texts,
            env.root,
            settings = {
                inside.complete(Unit)
                go.await()
                settings
            },
            record = { at, error -> records += at to error },
            folderAt = { folder },
            copyAt = { copy },
            clock = { now },
            zone = ZoneOffset.UTC,
        )
        val first = async { held.run(now = true) }
        inside.await()

        assertFalse(held.run(now = true))
        // The weekly run counts the one already writing as its own.
        assertTrue(held.run(whenBusy = true))
        assertTrue(records.isEmpty())

        go.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, records.size)
    }

    private fun manifest(zip: ByteArray): String {
        val stream = ZipInputStream(ByteArrayInputStream(zip))
        generateSequence { stream.nextEntry }.first { it.name == WebBackupFormat.MANIFEST }
        return stream.readBytes().decodeToString()
    }

    private class FakeFolder : BackupFolder {
        val files = linkedMapOf<String, ByteArray>()
        var failAfterBytes: Int? = null

        override fun names() = files.keys.toList()

        override fun write(name: String): OutputStream {
            val limit = failAfterBytes
            return object : ByteArrayOutputStream() {
                init {
                    files[name] = ByteArray(0)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (limit != null && size() + len > limit) throw IOException("Penyimpanan penuh")
                    super.write(b, off, len)
                    files[name] = toByteArray()
                }
            }
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private class FakeCopy : BackupCopy {
        var bytes = ByteArray(0)
        var failure: Exception? = null

        override fun write(): OutputStream {
            failure?.let { throw it }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    bytes = toByteArray()
                }
            }
        }
    }

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
