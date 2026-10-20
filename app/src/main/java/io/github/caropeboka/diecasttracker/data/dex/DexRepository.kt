package io.github.caropeboka.diecasttracker.data.dex

import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.DexItemRow
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.ReviewRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class CatalogSummary(val catalog: CatalogEntity, val counts: DexCounts)

/** A dex entry as the UI shows it, with the release year when it differs from the diecast's. */
data class DexRef(val row: DexItemRow, val yearFix: Int? = null)

/** A diecast's place in the Dex: its entry (and how it got it), or suggestions when it has none. */
data class DexLinkState(val linked: DexRef?, val match: CatalogMatch?, val suggestions: List<DexRef>)

/** A diecast to check in "Cocokkan Dex". */
data class ReviewItem(val row: ReviewRow, val link: DexLinkState)

enum class LinkAction { Confirm, Link, Ignore }

/** What the Dex screens read and the link actions they take (web: `DexController`). */
class DexRepository(db: AppDatabase, private val matcher: DexMatcher) {
    private val dao = db.dex()
    private val diecasts = db.diecasts()

    fun observeCatalogs(): Flow<List<CatalogSummary>> = combine(dao.observeCatalogs(), dao.observeProgressRows()) { catalogs, rows ->
        val counts = DexProgress.perCatalog(rows)
        catalogs.map { CatalogSummary(it, counts[it.id] ?: DexCounts()) }
    }

    fun observeCatalog(id: Long): Flow<CatalogEntity?> = dao.observeCatalog(id)

    suspend fun catalog(id: Long): CatalogEntity? = dao.catalog(id)

    fun observeEntries(catalogId: Long): Flow<List<DexEntry>> =
        combine(dao.observeItems(catalogId), dao.observeLinked(catalogId), DexProgress::entries)

    fun observeMissingImages(): Flow<Int> = dao.observeMissingImages()

    fun observeReview(limit: Int = REVIEW_LIMIT): Flow<List<ReviewRow>> = dao.observeReview(limit)

    fun observeReviewCount(): Flow<Int> = dao.observeReviewCount()

    suspend fun countMissingImages(): Int = dao.countMissingImages()

    suspend fun item(id: Long): DexItemRow? = dao.itemRow(id)

    suspend fun prefill(row: DexItemRow): DexPrefill = matcher.prefill(row)

    /** The best entry for a diecast that isn't saved yet (the AI scan), when the matcher is sure enough to propose it. */
    suspend fun suggestFor(probe: DiecastEntity): DexRef? =
        matcher.suggest(probe, 1).firstOrNull()?.takeIf { it.score >= DexMatcher.AUTO_SCORE }?.let { DexRef(it.row, it.yearFix) }

    suspend fun linkState(diecast: DiecastEntity): DexLinkState {
        val linked = diecast.catalogItemId?.let { dao.itemRow(it) }?.let { DexRef(it, it.catalogYear?.takeIf { year -> year != diecast.year }) }
        val suggestions = if (linked == null && diecast.catalogMatch != CatalogMatch.Ignored) {
            matcher.suggest(diecast).map { DexRef(it.row, it.yearFix) }
        } else {
            emptyList()
        }
        return DexLinkState(linked, diecast.catalogMatch, suggestions)
    }

    suspend fun review(row: ReviewRow) = ReviewItem(row, linkState(row.diecast))

    /**
     * Confirms, changes or drops a diecast's dex entry, and with [fixYear] takes the entry's release year.
     * Returns the diecast as saved.
     */
    suspend fun link(diecastId: Long, action: LinkAction, itemId: Long? = null, fixYear: Boolean = false): DiecastEntity? {
        val diecast = diecasts.get(diecastId) ?: return null
        val target = when (action) {
            LinkAction.Confirm -> diecast.catalogItemId ?: return diecast
            LinkAction.Link -> itemId ?: return diecast
            LinkAction.Ignore -> null
        }
        dao.setLink(diecastId, target, (if (target == null) CatalogMatch.Ignored else CatalogMatch.Confirmed).key)

        if (fixYear && target != null) {
            dao.itemRow(target)?.catalogYear?.let { dao.setYear(diecastId, it) }
        }
        return diecasts.get(diecastId)
    }

    companion object {
        const val REVIEW_LIMIT = 60
    }
}
