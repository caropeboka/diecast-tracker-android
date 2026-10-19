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

    @Query("SELECT photo_file FROM sightings WHERE diecast_id = :diecastId AND photo_file IS NOT NULL")
    suspend fun photoFiles(diecastId: Long): List<String>

    @Query("SELECT photo_file FROM sightings WHERE photo_file IS NOT NULL")
    suspend fun allPhotoFiles(): List<String>
}
