package io.github.caropeboka.diecasttracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface DiecastDao {
    /** See [io.github.caropeboka.diecasttracker.data.CollectionQuery]. */
    @RawQuery(
        observedEntities = [
            DiecastEntity::class, PhotoEntity::class, SightingEntity::class, TagEntity::class, DiecastTagCrossRef::class,
        ],
    )
    fun observeList(query: SupportSQLiteQuery): Flow<List<DiecastListItem>>

    @RawQuery
    suspend fun list(query: SupportSQLiteQuery): List<DiecastListItem>

    /** Every status counts ("do I already have, want or know this one?"); a diecast without brand may be any brand. */
    @Query("SELECT id, name, collection_number FROM diecasts WHERE :brand IS NULL OR brand IS NULL OR brand = :brand COLLATE NOCASE")
    suspend fun matchCandidates(brand: String?): List<MatchCandidate>

    @Transaction
    @Query("SELECT * FROM diecasts WHERE id = :id")
    suspend fun getDetails(id: Long): DiecastWithDetails?

    @Query("SELECT * FROM diecasts WHERE id = :id")
    suspend fun get(id: Long): DiecastEntity?

    /** Every diecast as the dashboard adds it up (see [io.github.caropeboka.diecasttracker.data.dashboard.Dashboard]). */
    @Query(
        """
        SELECT d.id, d.name, d.brand, d.series, d.color, d.year, d.rarity, d.status, d.quantity,
            d.purchase_price, d.market_value, d.target_price,
            (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS cover_file,
            (SELECT COUNT(*) FROM sightings s WHERE s.diecast_id = d.id) AS sightings_count,
            (SELECT MIN(s.price) FROM sightings s WHERE s.diecast_id = d.id) AS sightings_min_price,
            d.make, d.scale, d.storage_location, d.created_at,
            EXISTS (SELECT 1 FROM diecast_tag dt WHERE dt.diecast_id = d.id) AS has_tags
        FROM diecasts d ORDER BY d.id
        """,
    )
    fun observeDashboard(): Flow<List<DashboardRow>>

    /** Everything, for a backup. */
    @Transaction
    @Query("SELECT * FROM diecasts ORDER BY id")
    suspend fun allDetails(): List<DiecastWithDetails>

    /** A restored diecast keeps the times it was added and last changed. */
    @Query("UPDATE diecasts SET created_at = :createdAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun setTimestamps(id: Long, createdAt: Long, updatedAt: Long)

    @Insert
    suspend fun insert(diecast: DiecastEntity): Long

    @Update
    suspend fun update(diecast: DiecastEntity)

    @Query("DELETE FROM diecasts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM diecasts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM diecasts")
    fun observeCount(): Flow<Int>

    // Photos

    @Query("SELECT * FROM diecast_photos WHERE diecast_id = :diecastId")
    suspend fun photos(diecastId: Long): List<PhotoEntity>

    @Insert
    suspend fun insertPhoto(photo: PhotoEntity): Long

    @Query("UPDATE diecast_photos SET position = :position WHERE id = :id")
    suspend fun movePhoto(id: Long, position: Int)

    @Query("DELETE FROM diecast_photos WHERE id IN (:ids)")
    suspend fun deletePhotos(ids: List<Long>)

    @Query("SELECT file_name FROM diecast_photos")
    suspend fun allPhotoFiles(): List<String>

    @Query("SELECT COUNT(*) FROM diecast_photos")
    fun observePhotoCount(): Flow<Int>

    // Tags

    @Query("SELECT * FROM tags")
    suspend fun allTags(): List<TagEntity>

    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    @Query("DELETE FROM diecast_tag WHERE diecast_id = :diecastId")
    suspend fun clearTags(diecastId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRefs(refs: List<DiecastTagCrossRef>)

    /** Tags no diecast uses any more. */
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM diecast_tag)")
    suspend fun pruneTags()

    @Query("SELECT name FROM tags ORDER BY name COLLATE NOCASE")
    fun observeTagNames(): Flow<List<String>>

    // Suggestions for the form and the filters (one spelling per value, ignoring case)

    @Query("SELECT brand FROM diecasts WHERE brand IS NOT NULL GROUP BY brand COLLATE NOCASE ORDER BY brand COLLATE NOCASE")
    fun observeBrands(): Flow<List<String>>

    @Query("SELECT series FROM diecasts WHERE series IS NOT NULL GROUP BY series COLLATE NOCASE ORDER BY series COLLATE NOCASE")
    fun observeSeries(): Flow<List<String>>

    @Query(
        "SELECT storage_location FROM diecasts WHERE storage_location IS NOT NULL " +
            "GROUP BY storage_location COLLATE NOCASE ORDER BY storage_location COLLATE NOCASE",
    )
    fun observeStorageLocations(): Flow<List<String>>

    /** Stores and places used before: bought there or spotted there. */
    @Query(
        "SELECT purchase_place FROM diecasts WHERE purchase_place IS NOT NULL " +
            "UNION SELECT place FROM sightings WHERE place IS NOT NULL",
    )
    fun observePlaces(): Flow<List<String>>
}

@Dao
interface SightingDao {
    @Insert
    suspend fun insert(sighting: SightingEntity): Long

    @Query("SELECT * FROM sightings WHERE id = :id")
    suspend fun get(id: Long): SightingEntity?

    @Query("DELETE FROM sightings WHERE id = :id")
    suspend fun delete(id: Long)

    /** Newest first, like the web's "Catatan temuan". */
    @Query("SELECT * FROM sightings WHERE diecast_id = :diecastId ORDER BY seen_on DESC, id DESC")
    fun observeFor(diecastId: Long): Flow<List<SightingEntity>>

    @Query("SELECT photo_file FROM sightings WHERE diecast_id = :diecastId AND photo_file IS NOT NULL")
    suspend fun photoFiles(diecastId: Long): List<String>

    @Query("SELECT photo_file FROM sightings WHERE photo_file IS NOT NULL")
    suspend fun allPhotoFiles(): List<String>
}

/** Market prices from marketplace screenshots (web: `PriceChecker`). */
@Dao
interface PriceCheckDao {
    @Insert
    suspend fun insert(check: PriceCheckEntity): Long

    @Query("SELECT * FROM price_checks WHERE id = :id")
    suspend fun get(id: Long): PriceCheckEntity?

    /** Newest first. */
    @Query("SELECT * FROM price_checks WHERE diecast_id = :diecastId ORDER BY created_at DESC, id DESC")
    fun observeFor(diecastId: Long): Flow<List<PriceCheckEntity>>

    /** Everything, for a backup. */
    @Query("SELECT * FROM price_checks ORDER BY diecast_id, created_at, id")
    suspend fun all(): List<PriceCheckEntity>

    @Query("UPDATE price_checks SET applied_at = :at WHERE id = :id")
    suspend fun markApplied(id: Long, at: Long)

    @Query("DELETE FROM price_checks WHERE id = :id")
    suspend fun delete(id: Long)

    /** The diecast's market value now comes from a price check. */
    @Query(
        "UPDATE diecasts SET market_value = :value, market_value_source = 'price_check', market_value_updated_at = :at, " +
            "updated_at = :at WHERE id = :diecastId",
    )
    suspend fun setMarketValue(diecastId: Long, value: Long, at: Long)
}

/** Places: where things were spotted and bought (web: `PlaceLookup`, `ExploreController`), and the OpenStreetMap cache. */
@Dao
interface PlaceDao {
    /** Named places with a point inside the box: sightings first, then purchases. */
    @Query(
        """
        SELECT place, latitude, longitude, seen_on, NULL AS purchase_date, created_at FROM sightings
        WHERE place IS NOT NULL
            AND latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng
        UNION ALL
        SELECT purchase_place, purchase_latitude, purchase_longitude, NULL, purchase_date, created_at FROM diecasts
        WHERE purchase_place IS NOT NULL
            AND purchase_latitude BETWEEN :minLat AND :maxLat AND purchase_longitude BETWEEN :minLng AND :maxLng
        """,
    )
    suspend fun spots(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double): List<SpotRow>

    @Query(
        """
        SELECT s.id, s.diecast_id, d.name, d.brand,
            COALESCE(
                s.photo_file,
                (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1)
            ) AS photo_file,
            s.place, s.latitude, s.longitude, s.seen_on, s.price
        FROM sightings s JOIN diecasts d ON d.id = s.diecast_id
        ORDER BY s.id
        """,
    )
    fun observeSightingEvents(): Flow<List<SightingEventRow>>

    /** Diecasts in the garage (owned or sold) bought at a known place. */
    @Query(
        """
        SELECT d.id, d.name, d.brand,
            (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS photo_file,
            d.purchase_place AS place, d.purchase_latitude AS latitude, d.purchase_longitude AS longitude,
            d.purchase_date, d.created_at, d.purchase_price AS price
        FROM diecasts d
        WHERE d.status IN ('owned', 'sold') AND (d.purchase_place IS NOT NULL OR d.purchase_latitude IS NOT NULL)
        ORDER BY d.id
        """,
    )
    fun observePurchaseEvents(): Flow<List<PurchaseEventRow>>

    @Query("SELECT * FROM place_cache WHERE `key` = :key AND fetched_at >= :since")
    suspend fun cached(key: String, since: Long): PlaceCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(entry: PlaceCacheEntity)

    @Query("DELETE FROM place_cache WHERE fetched_at < :before")
    suspend fun pruneCache(before: Long)
}

/** The Dex: catalogs, their items, and which diecast is which item (web: the `Services/Dex` classes). */
@Dao
interface DexDao {
    // Catalogs

    @Query("SELECT * FROM catalogs ORDER BY brand COLLATE NOCASE, year DESC, name COLLATE NOCASE")
    fun observeCatalogs(): Flow<List<CatalogEntity>>

    @Query("SELECT * FROM catalogs WHERE id = :id")
    suspend fun catalog(id: Long): CatalogEntity?

    @Query("SELECT * FROM catalogs WHERE id = :id")
    fun observeCatalog(id: Long): Flow<CatalogEntity?>

    @Query("SELECT * FROM catalogs WHERE source = :source AND year = :year")
    suspend fun catalogFor(source: String, year: Int): CatalogEntity?

    @Query("SELECT * FROM catalogs ORDER BY id")
    suspend fun allCatalogs(): List<CatalogEntity>

    @Query("SELECT * FROM catalogs WHERE source = 'custom' AND lower(name) = lower(:name) LIMIT 1")
    suspend fun customNamed(name: String): CatalogEntity?

    @Query("SELECT id FROM catalogs WHERE source = 'custom'")
    suspend fun customCatalogIds(): List<Long>

    /** Catalog ids of a brand, any spelling. */
    @Query("SELECT id FROM catalogs WHERE lower(brand) = lower(:brand)")
    suspend fun catalogIdsForBrand(brand: String): List<Long>

    /** Own dexes are told apart by name. */
    @Query("SELECT COUNT(*) FROM catalogs WHERE source = 'custom' AND lower(name) = lower(:name) AND id != :exceptId")
    suspend fun countCustomNamed(name: String, exceptId: Long): Int

    @Insert
    suspend fun insertCatalog(catalog: CatalogEntity): Long

    @Update
    suspend fun updateCatalog(catalog: CatalogEntity)

    @Query("DELETE FROM catalogs WHERE id = :id")
    suspend fun deleteCatalog(id: Long)

    // Items

    @Query("SELECT * FROM catalog_items WHERE catalog_id = :catalogId ORDER BY sort, variant, id")
    suspend fun items(catalogId: Long): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_items WHERE catalog_id = :catalogId ORDER BY sort, variant, id")
    fun observeItems(catalogId: Long): Flow<List<CatalogItemEntity>>

    @Query("SELECT * FROM catalog_items WHERE id = :id")
    suspend fun item(id: Long): CatalogItemEntity?

    @Query(
        "SELECT ci.*, c.name AS catalog_name, c.year AS catalog_year, c.source AS catalog_source, c.brand AS catalog_brand " +
            "FROM catalog_items ci JOIN catalogs c ON c.id = ci.catalog_id WHERE ci.id = :id",
    )
    suspend fun itemRow(id: Long): DexItemRow?

    /** See [io.github.caropeboka.diecasttracker.data.dex.DexMatcher]: candidates found by number, series number or name. */
    @RawQuery(observedEntities = [CatalogItemEntity::class, CatalogEntity::class])
    suspend fun itemRows(query: SupportSQLiteQuery): List<DexItemRow>

    @Query("SELECT MAX(sort) FROM catalog_items WHERE catalog_id = :catalogId")
    suspend fun maxSort(catalogId: Long): Int?

    @Insert
    suspend fun insertItem(item: CatalogItemEntity): Long

    @Update
    suspend fun updateItem(item: CatalogItemEntity)

    @Query("DELETE FROM catalog_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    // Thumbnails

    @Query(
        "SELECT DISTINCT image_file FROM catalog_items " +
            "WHERE catalog_id = :catalogId AND image_file IS NOT NULL AND image_url IS NULL AND image_path IS NULL",
    )
    suspend fun filesWithoutUrl(catalogId: Long): List<String>

    @Query("UPDATE catalog_items SET image_url = :url WHERE catalog_id = :catalogId AND image_file = :file")
    suspend fun setImageUrl(catalogId: Long, file: String, url: String)

    /** Thumbnails still to download, the preferred dex first. */
    @Query(
        "SELECT * FROM catalog_items WHERE image_url IS NOT NULL AND image_path IS NULL " +
            "ORDER BY CASE WHEN catalog_id = :prefer THEN 0 ELSE 1 END, catalog_id, sort, variant LIMIT :limit",
    )
    suspend fun missingImages(limit: Int, prefer: Long): List<CatalogItemEntity>

    @Query("SELECT COUNT(*) FROM catalog_items WHERE image_url IS NOT NULL AND image_path IS NULL")
    suspend fun countMissingImages(): Int

    @Query("SELECT COUNT(*) FROM catalog_items WHERE image_url IS NOT NULL AND image_path IS NULL")
    fun observeMissingImages(): Flow<Int>

    @Query("UPDATE catalog_items SET image_path = :path WHERE id = :id")
    suspend fun setImagePath(id: Long, path: String)

    /** Not on the image CDN: don't ask again (a re-sync looks it up anew). */
    @Query("UPDATE catalog_items SET image_url = NULL WHERE id = :id")
    suspend fun clearImageUrl(id: Long)

    @Query("SELECT image_path FROM catalog_items WHERE image_path IS NOT NULL")
    suspend fun allImagePaths(): List<String>

    // Links

    /** Diecasts linked to items that are going away become unmatched, so they can be matched again. */
    @Query("UPDATE diecasts SET catalog_item_id = NULL, catalog_match = NULL WHERE catalog_item_id IN (:itemIds)")
    suspend fun unlink(itemIds: List<Long>)

    @Query("UPDATE diecasts SET catalog_item_id = :itemId, catalog_match = :match WHERE id = :diecastId")
    suspend fun setLink(diecastId: Long, itemId: Long?, match: String?)

    @Query("UPDATE diecasts SET year = :year WHERE id = :diecastId")
    suspend fun setYear(diecastId: Long, year: Int?)

    /** What automatic linking may (re)consider: not checked by the collector yet. */
    @Query("SELECT * FROM diecasts WHERE brand IS NOT NULL AND (catalog_match IS NULL OR catalog_match = 'auto') ORDER BY id")
    suspend fun linkable(): List<DiecastEntity>

    /** A brand's diecasts not confirmed in a dex yet, with their covers: what can seed an own dex. */
    @Query(
        "SELECT d.*, (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS cover_file " +
            "FROM diecasts d WHERE lower(d.brand) = lower(:brand) AND (d.catalog_match IS NULL OR d.catalog_match != 'confirmed') ORDER BY d.id",
    )
    suspend fun brandDiecasts(brand: String): List<ReviewRow>

    // Progress

    @Query("SELECT ci.catalog_id, ci.number, d.status FROM catalog_items ci LEFT JOIN diecasts d ON d.catalog_item_id = ci.id")
    fun observeProgressRows(): Flow<List<ProgressRow>>

    @Query(
        "SELECT d.id, d.name, d.status, d.catalog_item_id, d.catalog_match, " +
            "(SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS cover_file " +
            "FROM diecasts d JOIN catalog_items ci ON ci.id = d.catalog_item_id WHERE ci.catalog_id = :catalogId ORDER BY d.id",
    )
    fun observeLinked(catalogId: Long): Flow<List<LinkedDiecastRow>>

    // Review ("Cocokkan Dex"): diecasts of a brand that has a dex, auto-linked or not linked yet

    @Query(
        "SELECT d.*, (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS cover_file " +
            "FROM diecasts d WHERE lower(d.brand) IN (SELECT lower(brand) FROM catalogs) " +
            "AND (d.catalog_match IS NULL OR d.catalog_match = 'auto') " +
            "ORDER BY CASE WHEN d.catalog_match = 'auto' THEN 0 ELSE 1 END, d.id DESC LIMIT :limit",
    )
    fun observeReview(limit: Int): Flow<List<ReviewRow>>

    @Query(
        "SELECT COUNT(*) FROM diecasts d WHERE lower(d.brand) IN (SELECT lower(brand) FROM catalogs) " +
            "AND (d.catalog_match IS NULL OR d.catalog_match = 'auto')",
    )
    fun observeReviewCount(): Flow<Int>
}
