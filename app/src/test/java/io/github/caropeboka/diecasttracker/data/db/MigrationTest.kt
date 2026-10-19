package io.github.caropeboka.diecasttracker.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Every exported schema version must open, migrated, without losing the collection. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    /** A database exactly as version [version] of the app created it, from its exported schema. */
    private fun createFromSchema(version: Int, fill: (SQLiteDatabase) -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/${AppDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject

        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        schema.getValue("entities").jsonArray.map { it.jsonObject }.forEach { entity ->
            val table = entity.getValue("tableName").jsonPrimitive.content
            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            entity["indices"]?.jsonArray?.forEach { index ->
                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        fill(db)
        db.close()
    }

    @Test
    fun `version 1 migrates to 2 and keeps the collection`() = runBlocking {
        createFromSchema(1) { db ->
            db.execSQL(
                "INSERT INTO diecasts (id, name, rarity, packaging, status, quantity, ai_scanned, created_at, updated_at) " +
                    "VALUES (1, 'Datsun 510', 'regular', 'carded', 'owned', 1, 0, 1, 1)",
            )
            db.execSQL("INSERT INTO sightings (diecast_id, place, seen_on, created_at) VALUES (1, 'Toys Kingdom GI', '2026-10-01', 1)")
        }

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            assertEquals("Datsun 510", db.diecasts().get(1)?.name)
            assertEquals(listOf("Toys Kingdom GI"), db.sightings().observeFor(1).first().map { it.place })

            db.places().cache(PlaceCacheEntity("osm-reverse:1,2", "{}", 5))
            assertEquals("{}", db.places().cached("osm-reverse:1,2", since = 0)?.body)
        } finally {
            db.close()
        }
    }
}
