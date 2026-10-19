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
