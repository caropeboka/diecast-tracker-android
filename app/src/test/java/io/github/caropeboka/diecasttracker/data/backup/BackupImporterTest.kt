package io.github.caropeboka.diecasttracker.data.backup

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.CollectionFilter
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.TestEnvironment
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupImporterTest {
    private val env = TestEnvironment()
    private val repository = env.repository
    private val importer = BackupImporter(
        env.context, env.db, repository, env.photos, CoroutineScope(Dispatchers.Unconfined), today = { LocalDate.of(2026, 10, 18) },
    )

    /** Made by the web app's own CollectionBackup: tools/make-web-backup-fixture.php. */
    private val fixture: File by lazy {
        File(env.root, "backup.zip").apply {
            writeBytes(BackupImporterTest::class.java.getResourceAsStream("/web-backup-v1.zip")!!.readBytes())
        }
    }

    @After
    fun tearDown() = env.close()

    private suspend fun byName(name: String) =
        repository.observeCollection(CollectionFilter(search = name, status = null)).first()
            .ifEmpty { repository.observeCollection(CollectionFilter(search = name, status = DiecastStatus.Spotted)).first() }
            .single()
            .let { repository.details(it.id)!! }

    @Test
    fun `restores records, photos, tags and sightings of a web backup`() = runBlocking {
        val result = importer.import(fixture, replace = false)

        assertEquals(7, result.imported)
        assertEquals(
            listOf("Item 7 (?): Nama wajib diisi.", "Item 8 (Ford Model T): Tahun harus antara 1950 dan 2027."),
            result.skipped,
        )
        assertEquals(LocalDate.of(2026, 10, 18), result.exportedOn)

        val datsun = byName("Datsun")
        assertEquals(35_000L, datsun.diecast.purchasePrice)
        assertEquals(ValueSource.Manual, datsun.diecast.marketValueSource)
        assertEquals(listOf("Favorit", "JDM"), datsun.orderedTags.map { it.name })
        assertEquals(2, datsun.photos.size)

        // The web's own restore would drop this .webp photo; here it becomes a JPEG.
        assertEquals(1, byName("Countach").photos.size)

        val porsche = byName("Porsche")
        assertEquals(DiecastStatus.Spotted, porsche.diecast.status)
        assertEquals(listOf("Gramedia Matraman", "Toys Kingdom"), porsche.orderedSightings.map { it.place })
        assertEquals(1, porsche.sightings.count { it.photoFile != null })

        assertTrue(byName("Path trick").photos.isEmpty())

        // 2 + 1 + 1 + 1 diecast photos and 1 sighting photo, all moved out of pending.
        assertEquals(6, env.savedFiles.size)
        assertTrue(env.pendingFiles.isEmpty())
    }

    @Test
    fun `replace wipes the current collection and its photos first`() = runBlocking {
        val oldPhoto = env.pendingPhoto()
        repository.save(DiecastEntity(name = "Will be replaced"), listOf(oldPhoto), listOf("Lama"))

        importer.import(fixture, replace = true)

        assertEquals(7, env.db.diecasts().observeCount().first())
        assertFalse(oldPhoto in env.savedFiles)
        assertFalse("Lama" in repository.observeTagNames().first())
    }

    @Test
    fun `merge adds to the current collection`() = runBlocking {
        repository.save(DiecastEntity(name = "Already here"))

        importer.import(fixture, replace = false)

        assertEquals(8, env.db.diecasts().observeCount().first())
    }

    @Test
    fun `a zip without diecasts json is not a backup`() {
        val zip = File(env.root, "random.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("hello.txt"))
            it.write("hi".toByteArray())
        }

        val error = assertThrows(BackupException::class.java) { runBlocking { importer.import(zip, replace = false) } }
        assertEquals("Ini bukan file backup Diecast Tracker.", error.message)
    }

    @Test
    fun `a file that is not a zip is refused`() {
        val file = File(env.root, "photo.jpg").apply { writeBytes(TestEnvironment.fixtureJpeg) }

        val error = assertThrows(BackupException::class.java) { runBlocking { importer.import(file, replace = false) } }
        assertEquals("File ZIP tidak bisa dibuka.", error.message)
    }

    @Test
    fun `replace with nothing valid leaves the collection alone`() = runBlocking {
        repository.save(DiecastEntity(name = "Keep me"))
        val zip = File(env.root, "empty.zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("diecasts.json"))
            it.write("""{"format": "diecast-tracker-backup", "version": 1, "diecasts": [{"name": null}]}""".toByteArray())
        }

        assertThrows(BackupException::class.java) { runBlocking { importer.import(zip, replace = true) } }
        assertEquals(1, env.db.diecasts().observeCount().first())
    }
}
