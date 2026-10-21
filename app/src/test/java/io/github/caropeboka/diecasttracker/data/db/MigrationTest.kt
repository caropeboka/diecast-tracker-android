package io.github.caropeboka.diecasttracker.data.db

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.data.price.PriceAccuracy
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
    fun `every older version migrates to the latest and keeps the collection`() = runBlocking {
        for (version in 1 until LATEST) {
            createFromSchema(version) { db ->
                db.execSQL(
                    "INSERT INTO diecasts (id, name, brand, rarity, packaging, status, quantity, ai_scanned, created_at, updated_at) " +
                        "VALUES (1, 'Datsun 510', 'Hot Wheels', 'regular', 'carded', 'owned', 1, 0, 1, 1)",
                )
                db.execSQL("INSERT INTO sightings (diecast_id, place, seen_on, created_at) VALUES (1, 'Toys Kingdom GI', '2026-10-01', 1)")
            }

            val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*AppDatabase.MIGRATIONS).build()
            try {
                assertEquals("v$version", "Datsun 510", db.diecasts().get(1)?.name)
                assertEquals(listOf("Toys Kingdom GI"), db.sightings().observeFor(1).first().map { it.place })

                // What later versions added works on the migrated database.
                db.places().cache(PlaceCacheEntity("osm-reverse:1,2", "{}", 5))
                assertEquals("{}", db.places().cached("osm-reverse:1,2", since = 0)?.body)

                val catalog = db.dex().insertCatalog(CatalogEntity(brand = "Hot Wheels", name = "Hot Wheels Mainline 2026", year = 2026, source = "hotwheels_wiki"))
                val item = db.dex().insertItem(CatalogItemEntity(catalogId = catalog, number = "061", sort = 61, name = "Datsun 510"))
                db.dex().setLink(1, item, "confirmed")
                assertEquals(item, db.diecasts().get(1)?.catalogItemId)

                db.priceChecks().insert(
                    PriceCheckEntity(
                        diecastId = 1, screenshots = 1, listings = emptyList(), stats = null, accuracy = PriceAccuracy.Insufficient,
                        reasons = listOf("Tidak ada listing yang cocok dengan diecast ini."), tips = emptyList(), aiNote = null, createdAt = 5,
                    ),
                )
                assertEquals(PriceAccuracy.Insufficient, db.priceChecks().observeFor(1).first().single().accuracy)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    private companion object {
        const val LATEST = 4
    }
}
