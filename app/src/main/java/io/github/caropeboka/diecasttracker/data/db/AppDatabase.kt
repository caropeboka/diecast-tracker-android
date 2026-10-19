package io.github.caropeboka.diecasttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The collection lives only here, so schema changes always get a real migration (the exported schemas in
 * `app/schemas` are the reference) — never a destructive fallback.
 */
@Database(
    entities = [
        DiecastEntity::class,
        PhotoEntity::class,
        TagEntity::class,
        DiecastTagCrossRef::class,
        SightingEntity::class,
        PlaceCacheEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diecasts(): DiecastDao

    abstract fun sightings(): SightingDao

    abstract fun places(): PlaceDao

    companion object {
        const val NAME = "diecast-tracker.db"

        /** Phase 3: the OpenStreetMap cache. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `place_cache` " +
                        "(`key` TEXT NOT NULL, `body` TEXT NOT NULL, `fetched_at` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).addMigrations(*MIGRATIONS).build()
    }
}
