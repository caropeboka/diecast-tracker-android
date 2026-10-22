package io.github.caropeboka.diecasttracker.data.scan

import io.github.caropeboka.diecasttracker.data.CollectionFilter
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.DEFAULT_SCALE
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.DiecastScanner
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.db.ScanItemEntity
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** What a check says, strongest first (web: `verdictOf`). */
enum class Verdict { Owned, Wishlist, Spotted, New, Failed }

/** Owned beats wishlist beats seen; sold ones don't make it "owned" any more. */
fun verdictOf(failed: Boolean, matches: List<DiecastListItem>): Verdict {
    if (failed) return Verdict.Failed
    val statuses = matches.map { it.status }
    return when {
        DiecastStatus.Owned in statuses -> Verdict.Owned
        DiecastStatus.Wishlist in statuses -> Verdict.Wishlist
        DiecastStatus.Spotted in statuses -> Verdict.Spotted
        else -> Verdict.New
    }
}

class StoreCheckException(message: String) : Exception(message)

/**
 * "Do I already have this?" (web: `CheckController`): snap a diecast at the shelf, the AI reads it at once and the
 * collection is searched for it; then one tap logs it as seen here, or bought, or one more. Checks that never became
 * anything are only kept for a day.
 */
class StoreCheck(
    db: AppDatabase,
    private val photos: PhotoStore,
    private val scanner: DiecastScanner,
    private val matcher: CollectionMatcher,
    private val repository: DiecastRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.scanItems()

    /** False once the check was logged, added through the form (which takes it out) or pruned. */
    suspend fun isOpen(id: Long): Boolean = dao.get(id) != null

    /**
     * Reads [photoName] (a pending photo) right away: the collector is waiting at the shelf. The AI's problems become
     * a failed check with the reason, never an exception.
     */
    suspend fun check(photoName: String): ScanItemEntity {
        val item = ScanItemEntity(photoFile = photoName, source = ScanSource.Check, createdAt = clock())
        return read(item.copy(id = dao.insert(item)))
    }

    /** Another go at a check that failed (no signal at the shelf, a busy AI). Null when it is gone. */
    suspend fun retry(id: Long): ScanItemEntity? = dao.get(id)?.let { read(it) }

    private suspend fun read(item: ScanItemEntity): ScanItemEntity {
        val read = try {
            item.copy(status = ScanItemStatus.Done, fields = scanner.scan(listOf(item.photoFile)), error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            item.copy(status = ScanItemStatus.Failed, error = e.message)
        } catch (e: Exception) {
            item.copy(status = ScanItemStatus.Failed, error = "Cek gagal (${e.message ?: e::class.simpleName}).")
        }
        dao.update(read)
        return read
    }

    suspend fun matches(fields: ScanFields): List<DiecastListItem> = matcher.find(fields)

    /** Typed instead of photographed (no AI, works offline): every status, the ones in the garage first. */
    suspend fun search(query: String): List<DiecastListItem> {
        val text = query.trim()
        if (text.length < 2) return emptyList()
        val found = repository.observeCollection(CollectionFilter(search = text)).first() +
            repository.observeCollection(CollectionFilter(search = text, status = DiecastStatus.Spotted)).first()
        return found.sortedBy { STATUS_ORDER.indexOf(it.status) }.take(8)
    }

    /**
     * "Ditemui" on an entry already in the collection: a sighting here, with the check's photo (if any) as its photo.
     * The check is done with.
     */
    suspend fun spotExisting(
        diecastId: Long,
        place: PlaceValue,
        price: Long?,
        today: LocalDate,
        checkId: Long? = null,
        photo: String? = null,
    ) {
        repository.addSighting(sighting(diecastId, place, price, today, photo))
        checkId?.let { dao.delete(it) }
    }

    /**
     * Not in the collection: a new "Ditemui" diecast from what was read or typed (the check's photo as its photo) with
     * its first sighting here. Returns its id.
     */
    suspend fun spotNew(
        fields: ScanFields,
        aiScanned: Boolean,
        place: PlaceValue,
        price: Long?,
        today: LocalDate,
        checkId: Long? = null,
        photo: String? = null,
    ): Long {
        val name = fields.name?.trim().orEmpty()
        if (name.isEmpty()) throw StoreCheckException("AI tidak menemukan nama diecast ini — tambahkan lewat form.")

        val diecast = DiecastEntity(
            name = name,
            brand = fields.brand,
            make = fields.make,
            series = fields.series,
            collectionNumber = fields.collectionNumber,
            year = fields.year,
            scale = fields.scale ?: DEFAULT_SCALE,
            color = fields.color,
            rarity = fields.rarity ?: Rarity.Regular,
            packaging = fields.packaging ?: Packaging.Carded,
            condition = fields.condition,
            status = DiecastStatus.Spotted,
            notes = fields.notes,
            aiScanned = aiScanned,
        )
        val id = repository.save(diecast, listOfNotNull(photo), emptyList(), sighting(0, place, price, today, photo = null))
        checkId?.let { dao.delete(it) }
        return id
    }

    /** Checks older than [before] that never became anything, with their photos. */
    suspend fun prune(before: Long) {
        dao.checksBefore(before).forEach { item ->
            dao.delete(item.id)
            if (photos.isPending(item.photoFile)) photos.delete(item.photoFile)
        }
    }

    /** The point is kept only while it is fresh (web: `freshCoordinates`). */
    private fun sighting(diecastId: Long, place: PlaceValue, price: Long?, today: LocalDate, photo: String?): SightingEntity {
        val point = place.takeIf { it.isFresh(clock()) }
        return SightingEntity(
            diecastId = diecastId,
            place = place.name.trim().ifEmpty { null },
            latitude = point?.lat,
            longitude = point?.lng,
            seenOn = today,
            price = price,
            photoFile = photo,
        )
    }

    companion object {
        /** How long a check that never became anything is kept (web: a day). */
        const val KEEP_MS = 24 * 60 * 60 * 1000L

        private val STATUS_ORDER = listOf(DiecastStatus.Owned, DiecastStatus.Wishlist, DiecastStatus.Spotted, DiecastStatus.Sold)
    }
}
