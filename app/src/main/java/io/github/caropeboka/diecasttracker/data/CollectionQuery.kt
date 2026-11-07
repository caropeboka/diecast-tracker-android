package io.github.caropeboka.diecasttracker.data

import androidx.annotation.StringRes
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.caropeboka.diecasttracker.R

enum class CollectionSort(val key: String, @StringRes val label: Int, internal val orderBy: String) {
    Newest("newest", R.string.sort_newest, "d.created_at DESC, d.id DESC"),
    Oldest("oldest", R.string.sort_oldest, "d.created_at ASC, d.id ASC"),
    Name("name", R.string.sort_name, "d.name COLLATE NOCASE ASC, d.id DESC"),
    Brand("brand", R.string.sort_brand, "d.brand IS NULL, d.brand COLLATE NOCASE ASC, d.name COLLATE NOCASE ASC"),
    Year("year", R.string.sort_year, "d.year IS NULL, d.year DESC, d.name COLLATE NOCASE ASC"),
    Price("price", R.string.sort_price, "d.purchase_price IS NULL, d.purchase_price DESC, d.id DESC"),
    Value("value", R.string.sort_value, "d.market_value IS NULL, d.market_value DESC, d.id DESC");

    companion object {
        fun fromKey(key: String?): CollectionSort = entries.find { it.key == key } ?: Newest
    }
}

/** A piece of data a diecast may still lack: the dashboard's "Kelengkapan data" (web: the `missing` filter). */
enum class MissingField(val key: String, @StringRes val label: Int, @StringRes val missing: Int, internal val condition: String) {
    Photo("photo", R.string.field_photo, R.string.missing_photo, "NOT EXISTS (SELECT 1 FROM diecast_photos p WHERE p.diecast_id = d.id)"),
    PurchasePrice("purchase_price", R.string.field_purchase_price, R.string.missing_purchase_price, "d.purchase_price IS NULL"),
    MarketValue("market_value", R.string.field_market_value, R.string.missing_market_value, "d.market_value IS NULL"),
    StorageLocation("storage_location", R.string.field_storage_location, R.string.missing_storage_location, "d.storage_location IS NULL"),
    Tags("tags", R.string.field_tags, R.string.missing_tags, "NOT EXISTS (SELECT 1 FROM diecast_tag dt WHERE dt.diecast_id = d.id)");

    companion object {
        fun fromKey(key: String?): MissingField? = entries.find { it.key == key }
    }
}

/** How the dashboard and the collection's scale filter call a diecast without a scale. */
const val UNKNOWN_SCALE = "?"

/**
 * Search, filters and sort of the collection screen (web: `DiecastController::index`).
 * Without a [status], "Ditemui" entries stay out: they are seen, not collected.
 * [make], [scale], [years] and [rare] come from the dashboard's links; [scale] [UNKNOWN_SCALE] = none given.
 */
data class CollectionFilter(
    val search: String = "",
    val brand: String? = null,
    val status: DiecastStatus? = null,
    val rarity: Rarity? = null,
    val tag: String? = null,
    val missing: MissingField? = null,
    val sort: CollectionSort = CollectionSort.Newest,
    val make: String? = null,
    val scale: String? = null,
    val years: IntRange? = null,
    /** Anything but regular. */
    val rare: Boolean = false,
) {
    val isFiltered get() =
        search.isNotBlank() || brand != null || status != null || rarity != null || tag != null || missing != null ||
            make != null || scale != null || years != null || rare
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
        if (filter.rare) where += "d.rarity != '${Rarity.Regular.key}'"

        filter.make?.let {
            where += "d.make = ? COLLATE NOCASE"
            args += it
        }

        when (val scale = filter.scale) {
            null -> Unit
            UNKNOWN_SCALE -> where += "d.scale IS NULL"
            else -> {
                where += "d.scale = ?"
                args += scale
            }
        }

        filter.years?.let {
            where += "d.year BETWEEN ? AND ?"
            args += it.first
            args += it.last
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
