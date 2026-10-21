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
        CatalogEntity::class,
        CatalogItemEntity::class,
        PriceCheckEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diecasts(): DiecastDao

    abstract fun sightings(): SightingDao

    abstract fun places(): PlaceDao

    abstract fun dex(): DexDao

    abstract fun priceChecks(): PriceCheckDao

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

        /** Phase 4: the Dex (catalogs, their items) and each diecast's link to an item. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `diecasts` ADD COLUMN `catalog_item_id` INTEGER")
                db.execSQL("ALTER TABLE `diecasts` ADD COLUMN `catalog_match` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diecasts_catalog_item_id` ON `diecasts` (`catalog_item_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalogs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`brand` TEXT NOT NULL, `name` TEXT NOT NULL, `year` INTEGER, `source` TEXT NOT NULL, " +
                        "`source_page` TEXT, `synced_at` INTEGER, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_catalogs_source_year_name` ON `catalogs` (`source`, `year`, `name`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalog_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`catalog_id` INTEGER NOT NULL, `number` TEXT NOT NULL, `sort` INTEGER NOT NULL, `variant` INTEGER NOT NULL, " +
                        "`variant_label` TEXT, `name` TEXT NOT NULL, `series` TEXT, `series_number` TEXT, `toy_code` TEXT, " +
                        "`flag_new` INTEGER NOT NULL DEFAULT 0, `flag_th` INTEGER NOT NULL DEFAULT 0, " +
                        "`flag_sth` INTEGER NOT NULL DEFAULT 0, `exclusive` TEXT, `image_file` TEXT, `image_url` TEXT, " +
                        "`image_path` TEXT, FOREIGN KEY(`catalog_id`) REFERENCES `catalogs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_catalog_id_sort` ON `catalog_items` (`catalog_id`, `sort`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_catalog_id_number` ON `catalog_items` (`catalog_id`, `number`)",
                )
            }
        }

        /** Phase 5: market prices read from marketplace screenshots. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `price_checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`diecast_id` INTEGER NOT NULL, `screenshots` INTEGER NOT NULL, `listings` TEXT NOT NULL, `stats` TEXT, " +
                        "`accuracy` TEXT NOT NULL, `reasons` TEXT NOT NULL, `tips` TEXT NOT NULL, `ai_note` TEXT, " +
                        "`applied_at` INTEGER, `created_at` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`diecast_id`) REFERENCES `diecasts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_price_checks_diecast_id` ON `price_checks` (`diecast_id`)")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).addMigrations(*MIGRATIONS).build()
    }
}
