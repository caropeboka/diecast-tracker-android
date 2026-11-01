package io.github.caropeboka.diecasttracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.locale.Texts
import java.io.File
import java.util.zip.ZipInputStream

/** The app's strings in the test's language (English, unless the test sets qualifiers such as "in"). */
fun testTexts(): Texts = Texts(ApplicationProvider.getApplicationContext())

/** An in-memory database and a photo store in a fresh folder. */
class TestEnvironment(val now: () -> Long = { 1_000L }) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val root: File = File(context.cacheDir, "test-${System.nanoTime()}").apply { mkdirs() }
    val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    val texts = Texts(context)
    val photos = PhotoStore(context, root)
    val repository = DiecastRepository(db, photos, texts, now)

    val savedFiles get() = File(root, "photos").list().orEmpty().toSet()
    val pendingFiles get() = File(root, "photos-tmp").list().orEmpty().toSet()

    /** A pending photo, as if just taken. */
    fun pendingPhoto(): String = checkNotNull(photos.addFromBackup(fixtureJpeg))

    fun close() {
        db.close()
        root.deleteRecursively()
    }

    companion object {
        /** A real JPEG from the web backup fixture. */
        val fixtureJpeg: ByteArray by lazy {
            val zip = ZipInputStream(TestEnvironment::class.java.getResourceAsStream("/web-backup-v1.zip")!!)
            generateSequence { zip.nextEntry }.first { it.name.endsWith(".jpg") }
            zip.readBytes()
        }
    }
}
