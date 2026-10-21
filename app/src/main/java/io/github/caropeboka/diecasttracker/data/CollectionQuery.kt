package io.github.caropeboka.diecasttracker.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

enum class CollectionSort(val key: String, val label: String, internal val orderBy: String) {
    Newest("newest", "Terbaru", "d.created_at DESC, d.id DESC"),
    Oldest("oldest", "Terlama", "d.created_at ASC, d.id ASC"),
    Name("name", "Nama A–Z", "d.name COLLATE NOCASE ASC, d.id DESC"),
    Brand("brand", "Merek", "d.brand IS NULL, d.brand COLLATE NOCASE ASC, d.name COLLATE NOCASE ASC"),
    Year("year", "Tahun rilis", "d.year IS NULL, d.year DESC, d.name COLLATE NOCASE ASC"),
    Price("price", "Harga beli tertinggi", "d.purchase_price IS NULL, d.purchase_price DESC, d.id DESC"),
    Value("value", "Nilai pasaran tertinggi", "d.market_value IS NULL, d.market_value DESC, d.id DESC");

    companion object {
        fun fromKey(key: String?): CollectionSort = entries.find { it.key == key } ?: Newest
    }
}

/** A piece of data a diecast may still lack: the dashboard's "Kelengkapan data" (web: the `missing` filter). */
enum class MissingField(val key: String, val label: String, internal val condition: String) {
    Photo("photo", "Foto", "NOT EXISTS (SELECT 1 FROM diecast_photos p WHERE p.diecast_id = d.id)"),
    PurchasePrice("purchase_price", "Harga beli", "d.purchase_price IS NULL"),
    MarketValue("market_value", "Harga pasaran", "d.market_value IS NULL"),
    StorageLocation("storage_location", "Lokasi simpan", "d.storage_location IS NULL"),
    Tags("tags", "Tag", "NOT EXISTS (SELECT 1 FROM diecast_tag dt WHERE dt.diecast_id = d.id)");

    companion object {
        fun fromKey(key: String?): MissingField? = entries.find { it.key == key }
    }
}

/**
 * Search, filters and sort of the collection screen (web: `DiecastController::index`).
 * Without a [status], "Ditemui" entries stay out: they are seen, not collected.
 */
data class CollectionFilter(
    val search: String = "",
    val brand: String? = null,
    val status: DiecastStatus? = null,
    val rarity: Rarity? = null,
    val tag: String? = null,
    val missing: MissingField? = null,
    val sort: CollectionSort = CollectionSort.Newest,
) {
    val isFiltered get() =
        search.isNotBlank() || brand != null || status != null || rarity != null || tag != null || missing != null
}

object CollectionQuery {
    private val SEARCH_COLUMNS = listOf(
        "name", "brand", "make", "series", "color", "collection_number", "storage_location", "notes",
    )

    private const val HAS_TAG =
        "EXISTS (SELECT 1 FROM diecast_tag dt JOIN tags t ON t.id = dt.tag_id WHERE dt.diecast_id = d.id AND "

    fun build(filter: CollectionFilter): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        val search = filter.search.trim()
        if (search.isNotEmpty()) {
            val pattern = "%" + escapeLike(search) + "%"
            val anyColumn = SEARCH_COLUMNS.map { "d.$it LIKE ? ESCAPE '\\'" } + "${HAS_TAG}t.name LIKE ? ESCAPE '\\')"
            where += anyColumn.joinToString(" OR ", "(", ")")
            repeat(anyColumn.size) { args += pattern }
        }

        filter.brand?.let {
            where += "d.brand = ? COLLATE NOCASE"
            args += it
        }

        if (filter.status != null) {
            where += "d.status = ?"
            args += filter.status.key
        } else {
            where += "d.status != '${DiecastStatus.Spotted.key}'"
        }

        filter.rarity?.let {
            where += "d.rarity = ?"
            args += it.key
        }

        filter.tag?.let {
            where += "${HAS_TAG}t.name = ?)"
            args += it
        }

        filter.missing?.let { where += it.condition }

        val sql = "$SELECT_ITEMS WHERE ${where.joinToString(" AND ")} ORDER BY ${filter.sort.orderBy}"

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** The same cards for the given ids, e.g. the duplicate check's matches. */
    fun byIds(ids: Collection<Long>): SupportSQLiteQuery =
        SimpleSQLiteQuery("$SELECT_ITEMS WHERE d.id IN (${ids.joinToString(",") { "?" }})", ids.toTypedArray())

    private val SELECT_ITEMS = """
        SELECT d.id, d.name, d.brand, d.series, d.color, d.year, d.rarity, d.status, d.quantity,
            d.purchase_price, d.market_value, d.target_price,
            (SELECT p.file_name FROM diecast_photos p WHERE p.diecast_id = d.id ORDER BY p.position, p.id LIMIT 1) AS cover_file,
            (SELECT COUNT(*) FROM sightings s WHERE s.diecast_id = d.id) AS sightings_count,
            (SELECT MIN(s.price) FROM sightings s WHERE s.diecast_id = d.id) AS sightings_min_price
        FROM diecasts d
    """.trimIndent()

    private fun escapeLike(text: String) = text
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
