package io.github.caropeboka.diecasttracker.data

import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.DiecastTagCrossRef
import io.github.caropeboka.diecasttracker.data.db.DiecastWithDetails
import io.github.caropeboka.diecasttracker.data.db.MatchCandidate
import io.github.caropeboka.diecasttracker.data.db.PhotoEntity
import io.github.caropeboka.diecasttracker.data.db.PurchaseEventRow
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEventRow
import io.github.caropeboka.diecasttracker.data.db.TagEntity
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.util.naturalCaseInsensitiveOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Creates, updates and deletes diecasts together with their photos and tags (web: `DiecastWriter`).
 */
class DiecastRepository(
    private val db: AppDatabase,
    private val photos: PhotoStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dexMatcher: DexMatcher = DexMatcher(db.dex()),
) {
    private val dao = db.diecasts()
    private val sightingDao = db.sightings()

    fun observeCollection(filter: CollectionFilter): Flow<List<DiecastListItem>> =
        dao.observeList(CollectionQuery.build(filter))

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observePhotoCount(): Flow<Int> = dao.observePhotoCount()

    fun observeBrands(): Flow<List<String>> = dao.observeBrands()

    fun observeSeries(): Flow<List<String>> = dao.observeSeries()

    fun observeStorageLocations(): Flow<List<String>> = dao.observeStorageLocations()

    fun observeTagNames(): Flow<List<String>> = dao.observeTagNames()

    fun observePlaces(): Flow<List<String>> = dao.observePlaces().map { it.sortedWith(naturalCaseInsensitiveOrder) }

    suspend fun details(id: Long): DiecastWithDetails? = dao.getDetails(id)

    suspend fun matchCandidates(brand: String?): List<MatchCandidate> = dao.matchCandidates(brand)

    /** Cards for [ids], in no particular order. */
    suspend fun listItems(ids: Collection<Long>): List<DiecastListItem> =
        if (ids.isEmpty()) emptyList() else dao.list(CollectionQuery.byIds(ids))

    /**
     * Inserts ([DiecastEntity.id] = 0) or updates a diecast.
     *
     * @param photoNames ordered photo names, first = cover: saved ones are kept, pending ones are added,
     *   the rest removed. Null leaves photos untouched.
     * @param tagNames null leaves tags untouched.
     * @param firstSighting recorded with a new "Ditemui" diecast.
     * @return the diecast's id
     *
     * A new diecast may come with its Dex entry (chosen on the form); an existing one keeps its link, which changes only
     * through [io.github.caropeboka.diecasttracker.data.dex.DexRepository.link]. Unless the collector checked it, the
     * link is worked out again when the diecast is new or what identifies it changed (web: `DiecastWriter`).
     */
    suspend fun save(
        diecast: DiecastEntity,
        photoNames: List<String>? = null,
        tagNames: List<String>? = null,
        firstSighting: SightingEntity? = null,
    ): Long {
        val toPromote = mutableListOf<String>()
        val toDelete = mutableListOf<String>()
        var rematch = false

        val id = db.withTransaction {
            val now = clock()
            val existing = if (diecast.id != 0L) checkNotNull(dao.get(diecast.id)) { "Diecast ${diecast.id} not found" } else null

            // A value typed in by hand (a price check, later, sets its own source).
            val valueChanged = existing == null || existing.marketValue != diecast.marketValue
            val sourceGiven = diecast.marketValueSource != existing?.marketValueSource
            val record = diecast.copy(
                marketValueSource = if (valueChanged && !sourceGiven) diecast.marketValue?.let { ValueSource.Manual } else diecast.marketValueSource,
                marketValueUpdatedAt = if (valueChanged && !sourceGiven) diecast.marketValue?.let { now } else diecast.marketValueUpdatedAt,
                catalogItemId = if (existing != null) existing.catalogItemId else diecast.catalogItemId,
                catalogMatch = if (existing != null) existing.catalogMatch else diecast.catalogMatch,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            rematch = existing == null || existing.identity() != record.identity()

            val id = if (existing == null) dao.insert(record) else record.id.also { dao.update(record) }

            if (photoNames != null) {
                syncPhotos(id, photoNames, toPromote, toDelete)
            }
            if (tagNames != null) {
                syncTags(id, tagNames)
            }
            if (existing == null && firstSighting != null) {
                sightingDao.insert(firstSighting.copy(id = 0, diecastId = id, createdAt = now))
            }
            id
        }

        // Files move only once the rows are committed; housekeeping() finishes the job after a crash.
        toPromote.forEach(photos::promote)
        toDelete.forEach(photos::delete)

        if (rematch) dao.get(id)?.let { dexMatcher.autoLink(it) }

        return id
    }

    /** What decides which Dex entry a diecast is. */
    private fun DiecastEntity.identity() = listOf(name, brand, series, collectionNumber, year, rarity)

    fun observeSightings(diecastId: Long): Flow<List<SightingEntity>> = sightingDao.observeFor(diecastId)

    /** Everything the explore map shows, live. */
    fun observeExploreEvents(): Flow<Pair<List<SightingEventRow>, List<PurchaseEventRow>>> =
        combine(db.places().observeSightingEvents(), db.places().observePurchaseEvents(), ::Pair)

    /** Records a "Ditemui" sighting (web: `SightingLog`); a pending photo moves into storage once it is saved. */
    suspend fun addSighting(sighting: SightingEntity): Long {
        val photo = sighting.photoFile?.takeIf(photos::isPending)
        val id = sightingDao.insert(sighting.copy(id = 0, photoFile = photo, createdAt = clock()))
        photo?.let(photos::promote)
        return id
    }

    suspend fun deleteSighting(id: Long) {
        val sighting = sightingDao.get(id) ?: return
        sightingDao.delete(id)
        photos.delete(sighting.photoFile)
    }

    /** Deletes a diecast with its photos, sightings and now-unused tags. */
    suspend fun delete(id: Long) {
        val files = db.withTransaction {
            val files = dao.photos(id).map { it.fileName } + sightingDao.photoFiles(id)
            dao.delete(id)
            dao.pruneTags()
            files
        }
        files.forEach(photos::delete)
    }

    /** See [PhotoStore.housekeeping]. */
    suspend fun housekeeping() {
        val referenced = dao.allPhotoFiles() + sightingDao.allPhotoFiles()
        photos.housekeeping(referenced.toSet())
    }

    /** Must run inside a transaction. */
    internal suspend fun syncTags(diecastId: Long, names: List<String>) {
        val wanted = names
            .map { it.trim().take(MAX_TAG_LENGTH).trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }

        val known = dao.allTags().associateBy { it.name.lowercase() }
        val ids = wanted.map { name -> known[name.lowercase()]?.id ?: dao.insertTag(TagEntity(name = name)) }

        dao.clearTags(diecastId)
        dao.insertTagRefs(ids.map { DiecastTagCrossRef(diecastId, it) })
        dao.pruneTags()
    }

    private suspend fun syncPhotos(
        diecastId: Long,
        names: List<String>,
        toPromote: MutableList<String>,
        toDelete: MutableList<String>,
    ) {
        val existing = dao.photos(diecastId).associateBy { it.fileName }
        val keep = mutableSetOf<String>()
        var position = 0

        for (name in names.distinct()) {
            val photo = existing[name]
            when {
                photo != null -> {
                    dao.movePhoto(photo.id, position++)
                    keep += name
                }
                photos.isPending(name) -> {
                    dao.insertPhoto(PhotoEntity(diecastId = diecastId, fileName = name, position = position++))
                    toPromote += name
                }
                // Anything else (a made-up or vanished name) is ignored.
            }
        }

        val removed = existing.values.filter { it.fileName !in keep }
        dao.deletePhotos(removed.map { it.id })
        toDelete += removed.map { it.fileName }
    }
}
