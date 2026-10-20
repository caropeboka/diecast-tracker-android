package io.github.caropeboka.diecasttracker.data.dex

import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class SyncResult(val catalogId: Long, val name: String, val items: Int, val added: Int, val removed: Int, val linked: Int)

/**
 * Brings a wiki year list into the Dex, keeping item ids (and so the links to the collection) stable across
 * re-syncs, and downloads the thumbnails a batch at a time (web: `CatalogSync.php`).
 */
class DexSync(
    private val db: AppDatabase,
    private val wiki: WikiCatalog,
    private val matcher: DexMatcher,
    /** Thumbnails go under `<root>/dex/<catalog id>/`. */
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.dex()

    suspend fun sync(source: WikiSource, year: Int): SyncResult {
        val (page, rows) = wiki.fetch(source, year)
        if (rows.isEmpty()) throw DexSyncException("Daftar $year di wiki belum berisi data.")

        val stale = mutableListOf<String?>()
        val name = "${source.label} $year"

        val (catalogId, counts) = db.withTransaction {
            val known = dao.catalogFor(source.key, year)
            val catalog = known?.copy(brand = source.brand, name = name, sourcePage = page)?.also { dao.updateCatalog(it) }
                ?: CatalogEntity(brand = source.brand, name = name, year = year, source = source.key, sourcePage = page, createdAt = clock())
                    .let { it.copy(id = dao.insertCatalog(it)) }

            val existing = dao.items(catalog.id).associateBy { "${it.number}|${it.variant}" }
            val seen = mutableSetOf<String>()
            var added = 0

            for (row in rows) {
                val key = "${row.number}|${row.variant}"
                seen += key
                val item = existing[key]
                if (item == null) {
                    dao.insertItem(row.toItem(catalog.id))
                    added++
                } else {
                    // A new photo on the wiki: fetch it again.
                    val newPhoto = item.imageFile != row.imageFile
                    if (newPhoto) stale += item.imagePath
                    dao.updateItem(
                        row.toItem(catalog.id).copy(
                            id = item.id,
                            imageUrl = item.imageUrl.takeIf { !newPhoto },
                            imagePath = item.imagePath.takeIf { !newPhoto },
                        ),
                    )
                }
            }

            val gone = existing.filterKeys { it !in seen }.values
            stale += gone.map { it.imagePath }
            forget(gone.map { it.id })

            catalog.id to (added to gone.size)
        }

        deleteFiles(stale)
        resolveImageUrls(source, catalogId)
        dao.catalog(catalogId)?.let { dao.updateCatalog(it.copy(syncedAt = clock())) }

        return SyncResult(catalogId, name, dao.items(catalogId).size, counts.first, counts.second, matcher.autoLinkAll())
    }

    /**
     * Downloads up to [limit] missing thumbnails ([prefer]'s first) and returns how many are still missing, or null
     * when the connection failed (try again later).
     */
    suspend fun downloadImages(limit: Int = 12, prefer: Long = 0): Int? {
        val items = dao.missingImages(limit, prefer)
        if (items.isEmpty()) return 0

        // All at once: one round trip for the batch.
        val images = wiki.download(items.associate { it.id to it.imageUrl!! })
        var trouble = false

        for (item in items) {
            when (val image = images[item.id] ?: Download.Retry) {
                Download.Retry -> trouble = true
                Download.Missing -> dao.clearImageUrl(item.id)
                is Download.Image -> {
                    val path = "dex/${item.catalogId}/${item.id}-${md5(item.imageFile.orEmpty()).take(8)}.${image.extension}"
                    withContext(Dispatchers.IO) {
                        File(root, path).apply { parentFile?.mkdirs() }.writeBytes(image.bytes)
                    }
                    dao.setImagePath(item.id, path)
                }
            }
        }

        return if (trouble) null else dao.countMissingImages()
    }

    suspend fun delete(catalogId: Long) {
        db.withTransaction {
            forget(dao.items(catalogId).map { it.id })
            dao.deleteCatalog(catalogId)
        }
        withContext(Dispatchers.IO) { File(root, "dex/$catalogId").deleteRecursively() }
    }

    fun file(path: String) = File(root, path)

    /** Removes items; diecasts linked to them become unmatched (so they can be matched again). Inside a transaction. */
    private suspend fun forget(ids: List<Long>) {
        ids.chunked(500).forEach {
            dao.unlink(it)
            dao.deleteItems(it)
        }
    }

    private suspend fun resolveImageUrls(source: WikiSource, catalogId: Long) {
        val files = dao.filesWithoutUrl(catalogId)
        if (files.isEmpty()) return

        wiki.imageUrls(source, files).forEach { (file, url) -> dao.setImageUrl(catalogId, file, url) }
    }

    private suspend fun deleteFiles(paths: List<String?>) = withContext(Dispatchers.IO) {
        paths.filterNotNull().forEach { File(root, it).delete() }
    }

    private fun WikiItem.toItem(catalogId: Long) = CatalogItemEntity(
        catalogId = catalogId,
        number = number,
        sort = sort,
        variant = variant,
        variantLabel = variantLabel,
        name = name,
        series = series,
        seriesNumber = seriesNumber,
        toyCode = toyCode,
        isNew = isNew,
        isTh = isTh,
        isSth = isSth,
        exclusive = exclusive,
        imageFile = imageFile,
    )

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
