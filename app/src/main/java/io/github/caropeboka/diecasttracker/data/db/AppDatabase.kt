package io.github.caropeboka.diecasttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diecasts(): DiecastDao

    abstract fun sightings(): SightingDao

    companion object {
        const val NAME = "diecast-tracker.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
