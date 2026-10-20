package io.github.caropeboka.diecasttracker.data.dex

import androidx.room.withTransaction
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalDate

class DexCustomException(message: String) : Exception(message)

/** A release for an own dex, as typed, imported from a spreadsheet or read from a checklist. */
@Serializable
data class DexRow(
    val number: String? = null,
    val name: String = "",
    val series: String? = null,
    val seriesNumber: String? = null,
    val variantLabel: String? = null,
)

/** [linked]: diecasts linked to entries (from the collection). */
data class AddResult(val added: Int, val skipped: Int, val items: List<CatalogItemEntity> = emptyList(), val linked: Int = 0) {
    val summary get() = "$added entri ditambahkan" + if (skipped > 0) ", $skipped dilewati (sudah ada / kosong)." else "."
}

enum class CatalogField { Brand, Name, Year }

enum class RowField { Number, Name, Series, SeriesNumber, Variant }

/** What happens to an entry's photo when it's edited; [Replace] takes a pending photo of the [PhotoStore]. */
sealed interface PhotoEdit {
    data object Keep : PhotoEdit

    data object Remove : PhotoEdit

    data class Replace(val pendingName: String) : PhotoEdit
}

/**
 * Dex lists the collector builds for brands without a wiki list (Mini GT, Tarmac, Inno64, Roll Speed…): by hand, from
 * a spreadsheet, from checklist screenshots read by the AI, or from the collection (web: `CatalogImport.php` and
 * `CustomDexController.php`). Unlike the web, entries can be edited too.
 */
class DexCustom(
    private val db: AppDatabase,
    private val matcher: DexMatcher,
    private val photos: PhotoStore,
    /** Entry photos go under `<root>/dex/<catalog id>/`, next to the wiki thumbnails. */
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val dao = db.dex()

    suspend fun checkCatalog(brand: String, name: String, year: String, exceptId: Long = 0): Map<CatalogField, String> = buildMap {
        when {
            brand.isBlank() -> put(CatalogField.Brand, "Merek wajib diisi.")
            brand.trim().length > 100 -> put(CatalogField.Brand, "Maksimal 100 karakter.")
        }
        when {
            name.isBlank() -> put(CatalogField.Name, "Nama dex wajib diisi.")
            name.trim().length > 120 -> put(CatalogField.Name, "Maksimal 120 karakter.")
            dao.countCustomNamed(name.trim(), exceptId) > 0 -> put(CatalogField.Name, "Kamu sudah punya dex dengan nama itu.")
        }
        if (year.isNotBlank()) {
            val value = year.trim().toIntOrNull()
            val max = today().year + 1
            if (value == null || value !in MIN_YEAR..max) put(CatalogField.Year, "Tahun antara $MIN_YEAR dan $max.")
        }
    }

    /** Returns the new dex's id; [checkCatalog] first. */
    suspend fun create(brand: String, name: String, year: String): Long {
        check(checkCatalog(brand, name, year).isEmpty())
        return dao.insertCatalog(
            CatalogEntity(brand = brand.trim(), name = name.trim(), year = year.trim().toIntOrNull(), source = DexMatcher.CUSTOM, createdAt = clock()),
        )
    }

    suspend fun update(catalogId: Long, brand: String, name: String, year: String) {
        val catalog = custom(catalogId)
        check(checkCatalog(brand, name, year, catalogId).isEmpty())
        dao.updateCatalog(catalog.copy(brand = brand.trim(), name = name.trim(), year = year.trim().toIntOrNull()))
        // Another brand: other diecasts may now be this dex's.
        if (!catalog.brand.equals(brand.trim(), ignoreCase = true)) matcher.autoLinkAll()
    }

    /** Adds releases; exact duplicates (same number, name and variant) and rows without a name are skipped. */
    suspend fun add(catalogId: Long, rows: List<DexRow>): AddResult = add(catalogId, rows.map { Incoming(it) }, relink = true)

    /** One release typed in by hand, with an optional photo (a pending photo of the [PhotoStore]). Null: already there. */
    suspend fun addOne(catalogId: Long, row: DexRow, photo: String?): CatalogItemEntity? {
        custom(catalogId)
        val path = photo?.let { keep(catalogId, it) }
        val item = add(catalogId, listOf(Incoming(row, path)), relink = true).items.firstOrNull()
        if (item == null && path != null) deleteFile(path)
        return item
    }

    /**
     * Corrects an entry. Another number makes it a variant of that number's entry (or an entry of its own);
     * diecasts linked to it stay linked.
     */
    suspend fun edit(itemId: Long, row: DexRow, photo: PhotoEdit) {
        val item = dao.item(itemId) ?: throw DexCustomException("Entri tidak ditemukan.")
        custom(item.catalogId)
        val name = row.name.trim().take(255).ifEmpty { throw DexCustomException("Nama model wajib diisi.") }
        val number = number(row.number) ?: item.number
        val label = text(row.variantLabel, 255)

        val newPath = (photo as? PhotoEdit.Replace)?.let { keep(item.catalogId, it.pendingName) }
        try {
            db.withTransaction {
                val others = dao.items(item.catalogId).filter { it.id != itemId }
                if (others.any { key(it.number, it.name, it.variantLabel) == key(number, name, label) }) {
                    throw DexCustomException("Entri itu sudah ada di dex ini.")
                }

                val (sort, variant) = if (number == item.number) {
                    item.sort to item.variant
                } else {
                    val siblings = others.filter { it.number == number }
                    (siblings.firstOrNull()?.sort ?: digitsSort(number) ?: item.sort) to ((siblings.maxOfOrNull { it.variant } ?: 0) + 1)
                }

                dao.updateItem(
                    item.copy(
                        number = number,
                        sort = sort,
                        variant = variant,
                        variantLabel = label,
                        name = name,
                        series = text(row.series, 255),
                        seriesNumber = text(row.seriesNumber, 32),
                        imagePath = when (photo) {
                            PhotoEdit.Keep -> item.imagePath
                            PhotoEdit.Remove -> null
                            is PhotoEdit.Replace -> newPath
                        },
                    ),
                )
            }
        } catch (e: Exception) {
            newPath?.let { deleteFile(it) }
            throw e
        }

        if (photo != PhotoEdit.Keep) item.imagePath?.let { deleteFile(it) }
        matcher.autoLinkAll()
    }

    /** Removes an entry; diecasts linked to it become unmatched. */
    suspend fun delete(itemId: Long) {
        val item = dao.item(itemId) ?: return
        custom(item.catalogId)
        db.withTransaction {
            dao.unlink(listOf(itemId))
            dao.deleteItems(listOf(itemId))
        }
        item.imagePath?.let { deleteFile(it) }
    }

    /**
     * Rows of a CSV/XLSX with a header row: Nomor, Nama, Seri, Nomor seri, Varian (or the English names); at least
     * Nama. Only the first sheet is read.
     */
    suspend fun fromSpreadsheet(catalogId: Long, bytes: ByteArray): AddResult {
        custom(catalogId)
        val table = withContext(Dispatchers.Default) {
            try {
                SheetReader.read(bytes)
            } catch (e: SheetException) {
                throw DexCustomException(e.message.orEmpty())
            }
        }

        val headers = table.firstOrNull().orEmpty().map { HEADERS[NOT_KEY.replace(it.lowercase(), "")] }
        if ("name" !in headers) throw DexCustomException("Kolom \"Nama\" tidak ditemukan di baris pertama.")

        val rows = table.drop(1).mapNotNull { values ->
            val fields = headers.withIndex().filter { it.value != null }.associate { (i, field) -> field!! to values.getOrNull(i)?.trim().orEmpty() }
            if (fields.values.all(String::isEmpty)) return@mapNotNull null
            DexRow(
                number = fields["number"],
                name = fields["name"].orEmpty(),
                series = fields["series"],
                seriesNumber = fields["series_number"],
                variantLabel = fields["variant_label"],
            )
        }
        return add(catalogId, rows)
    }

    /** Which of [rows] the dex already has (a checklist's rows, before they're added). */
    suspend fun known(catalogId: Long, rows: List<DexRow>): List<Boolean> {
        val keys = dao.items(catalogId).map { key(it.number, it.name, it.variantLabel) }.toSet()
        return rows.map { row -> number(row.number)?.let { key(it, row.name.trim(), text(row.variantLabel, 255)) in keys } ?: false }
    }

    /**
     * The collection's diecasts of this brand become entries, linked to them, each with its cover photo. Unlike the
     * web, a diecast without a number whose model is on the list already (typed or imported before) is linked to
     * that entry, or added as another colour of it, instead of becoming a second entry.
     */
    suspend fun fromCollection(catalogId: Long): AddResult {
        val catalog = custom(catalogId)
        val diecasts = dao.brandDiecasts(catalog.brand)
        val listed = dao.items(catalogId)

        val rows = diecasts.map { row ->
            val numbers = DexMatcher.numbers(row.diecast.collectionNumber)
            val made = DexRow(
                number = numbers.code ?: numbers.main?.toString(),
                name = row.diecast.name,
                series = row.diecast.series,
                seriesNumber = numbers.series,
                variantLabel = row.diecast.color,
            )
            val sameModel = if (number(made.number) == null) listed.filter { it.name.equals(made.name.trim(), ignoreCase = true) } else emptyList()
            if (sameModel.isEmpty()) return@map made

            val label = text(made.variantLabel, 255)
            val sameColour = if (label == null) sameModel.minBy { it.variant } else sameModel.firstOrNull { it.variantLabel.equals(label, ignoreCase = true) }
            // That entry when the colour matches (or there's none to tell); another colour becomes a variant of it.
            sameColour?.let { made.copy(number = it.number, variantLabel = it.variantLabel) } ?: made.copy(number = sameModel.first().number)
        }

        val incoming = diecasts.zip(rows).map { (row, made) ->
            val cover = row.coverFile?.let(photos::file)?.takeIf(File::exists)
            val copy = cover?.let {
                val path = "dex/$catalogId/${it.name}"
                withContext(Dispatchers.IO) { it.copyTo(File(root, path), overwrite = true) }
                path
            }
            Incoming(made, copy)
        }

        val result = add(catalogId, incoming, relink = false)

        // Link each diecast to the entry made from it (or the one it duplicated).
        val items = dao.items(catalogId)
        var linked = 0
        diecasts.zip(incoming).forEach { (row, made) ->
            val number = number(made.row.number)
            val item = items.firstOrNull {
                it.name.equals(made.row.name.trim(), ignoreCase = true) &&
                    (number == null || it.number == number) &&
                    it.variantLabel.orEmpty().equals(made.row.variantLabel?.trim().orEmpty(), ignoreCase = true)
            }
            if (item != null) {
                dao.setLink(row.diecast.id, item.id, CatalogMatch.Confirmed.key)
                linked++
            }
            if (made.imagePath != null && item?.imagePath != made.imagePath) deleteFile(made.imagePath)
        }
        return result.copy(linked = linked)
    }

    private data class Incoming(val row: DexRow, val imagePath: String? = null)

    private suspend fun add(catalogId: Long, rows: List<Incoming>, relink: Boolean): AddResult {
        var skipped = 0
        val created = mutableListOf<CatalogItemEntity>()

        db.withTransaction {
            val items = dao.items(catalogId)
            val known = items.map { key(it.number, it.name, it.variantLabel) }.toMutableSet()
            val variants = items.groupBy { it.number }.mapValues { (_, group) -> group.maxOf { it.variant } }.toMutableMap()
            // Variants share their number's place in the list.
            val sorts = items.groupBy { it.number }.mapValues { (_, group) -> group.first().sort }.toMutableMap()
            var nextSort = items.maxOfOrNull { it.sort } ?: 0

            for ((row, imagePath) in rows) {
                val name = row.name.trim().take(255)
                if (name.isEmpty()) {
                    skipped++
                    continue
                }

                // No number on the list: number them in order.
                val number = number(row.number) ?: (nextSort + 1).toString()
                val label = text(row.variantLabel, 255)
                val key = key(number, name, label)
                if (key in known) {
                    skipped++
                    continue
                }

                val sort = sorts[number] ?: digitsSort(number) ?: (nextSort + 1)
                sorts[number] = sort
                nextSort = maxOf(nextSort, sort)
                val variant = (variants[number] ?: 0) + 1
                variants[number] = variant
                known += key

                val item = CatalogItemEntity(
                    catalogId = catalogId,
                    number = number,
                    sort = sort,
                    variant = variant,
                    variantLabel = label,
                    name = name,
                    series = text(row.series, 255),
                    seriesNumber = text(row.seriesNumber, 32),
                    imagePath = imagePath,
                )
                created += item.copy(id = dao.insertItem(item))
            }
        }

        if (relink && created.isNotEmpty()) matcher.autoLinkAll()
        return AddResult(created.size, skipped, created)
    }

    /** Only own dexes are edited by hand; wiki lists change through a sync. */
    private suspend fun custom(catalogId: Long): CatalogEntity {
        val catalog = dao.catalog(catalogId) ?: throw DexCustomException("Dex tidak ditemukan.")
        if (catalog.source != DexMatcher.CUSTOM) throw DexCustomException("Daftar dari wiki diubah lewat sinkron, bukan manual.")
        return catalog
    }

    /** Moves a pending photo into the dex's folder; returns its path under [root]. */
    private suspend fun keep(catalogId: Long, pendingName: String): String = withContext(Dispatchers.IO) {
        val path = "dex/$catalogId/$pendingName"
        val source = photos.file(pendingName)
        val target = File(root, path).apply { parentFile?.mkdirs() }
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        path
    }

    private suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) { File(root, path).delete() }

    companion object {
        const val MIN_YEAR = 1950

        /** Spreadsheet headers (lowercased, letters/digits only) → row field. */
        private val HEADERS = mapOf(
            "nomor" to "number", "no" to "number", "number" to "number", "#" to "number", "kode" to "number", "code" to "number",
            "nama" to "name", "name" to "name", "model" to "name", "namamodel" to "name", "modelname" to "name",
            "seri" to "series", "series" to "series", "subseri" to "series",
            "nomorseri" to "series_number", "seriesnumber" to "series_number", "series#" to "series_number",
            "varian" to "variant_label", "variant" to "variant_label", "warna" to "variant_label", "color" to "variant_label",
        )
        private val NOT_KEY = Regex("[^a-z0-9#]")
        private val NUMBER_PREFIX = Regex("^(?:#|no\\.?)\\s*", RegexOption.IGNORE_CASE)

        fun checkRow(row: DexRow): Map<RowField, String> = buildMap {
            fun length(field: RowField, value: String?, max: Int) {
                if (value.orEmpty().trim().length > max) put(field, "Maksimal $max karakter.")
            }
            if (row.name.isBlank()) put(RowField.Name, "Nama model wajib diisi.")
            length(RowField.Name, row.name, 255)
            length(RowField.Number, row.number, 32)
            length(RowField.Series, row.series, 255)
            length(RowField.SeriesNumber, row.seriesNumber, 32)
            length(RowField.Variant, row.variantLabel, 255)
        }

        /** "#12" / "No. 12" → "12"; codes kept as printed, uppercased. */
        fun number(value: String?): String? =
            NUMBER_PREFIX.replace(value.orEmpty().trim(), "").uppercase().take(32).ifEmpty { null }

        private fun text(value: String?, max: Int): String? = value?.trim()?.take(max)?.ifEmpty { null }

        private fun key(number: String, name: String, variant: String?) = "$number|${name.trim()}|${variant.orEmpty().trim()}".lowercase()

        /** A plain number keeps its place ("12" → 12); codes are numbered in the order they come. */
        private fun digitsSort(number: String): Int? = number.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
    }
}
