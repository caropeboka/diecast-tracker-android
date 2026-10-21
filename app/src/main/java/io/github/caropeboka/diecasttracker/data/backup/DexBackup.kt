package io.github.caropeboka.diecasttracker.data.backup

import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.CatalogEntity
import io.github.caropeboka.diecasttracker.data.db.CatalogItemEntity
import io.github.caropeboka.diecasttracker.data.dex.DexMatcher
import io.github.caropeboka.diecasttracker.data.dex.WikiSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

/**
 * The Dex part of a backup made on the phone: `dex.json` with every list (wiki years and own dexes) and the own
 * dexes' photos under `dex/`. Wiki thumbnails are left out; they download again. The web's backups have neither, and
 * its restore ignores both. Diecasts point at [Item.id] through `dex_item` in `diecasts.json`.
 */
object DexBackup {
    const val ENTRY = "dex.json"
    const val FORMAT = "diecast-tracker-dex"
    val PHOTO_ENTRY = Regex("dex/[A-Za-z0-9._-]+\\.(jpe?g|png|webp)", RegexOption.IGNORE_CASE)

    private val JSON = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Manifest(val format: String = FORMAT, val version: Int = 1, val catalogs: List<Catalog> = emptyList())

    @Serializable
    data class Catalog(
        val brand: String,
        val name: String,
        val year: Int? = null,
        /** `hotwheels_wiki`, `matchbox_wiki` or `custom`. */
        val source: String,
        @SerialName("source_page") val sourcePage: String? = null,
        @SerialName("synced_at") val syncedAt: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        val items: List<Item> = emptyList(),
    )

    @Serializable
    data class Item(
        /** The id on the phone that made the backup: only means something inside it. */
        val id: Long,
        val number: String,
        val sort: Int = 0,
        val variant: Int = 1,
        @SerialName("variant_label") val variantLabel: String? = null,
        val name: String,
        val series: String? = null,
        @SerialName("series_number") val seriesNumber: String? = null,
        @SerialName("toy_code") val toyCode: String? = null,
        @SerialName("new") val isNew: Boolean = false,
        @SerialName("th") val isTh: Boolean = false,
        @SerialName("sth") val isSth: Boolean = false,
        val exclusive: String? = null,
        @SerialName("image_file") val imageFile: String? = null,
        @SerialName("image_url") val imageUrl: String? = null,
        /** Own dexes: the photo's entry in the zip, e.g. "dex/abc.jpg". */
        val photo: String? = null,
    )

    fun encode(catalogs: List<Catalog>): String = JSON.encodeToString(Manifest(catalogs = catalogs))

    /** Null when the file is damaged or something else. */
    fun decode(text: String): Manifest? =
        runCatching { JSON.decodeFromString<Manifest>(text) }.getOrNull()?.takeIf { it.format == FORMAT }

    /** [photo] gives an item's entry in the zip, if it has a photo to keep. */
    fun catalog(catalog: CatalogEntity, items: List<CatalogItemEntity>, photo: (CatalogItemEntity) -> String?) = Catalog(
        brand = catalog.brand,
        name = catalog.name,
        year = catalog.year,
        source = catalog.source,
        sourcePage = catalog.sourcePage,
        syncedAt = catalog.syncedAt?.let(WebBackupFormat::timestamp),
        createdAt = catalog.createdAt.takeIf { it > 0 }?.let(WebBackupFormat::timestamp),
        items = items.map {
            Item(
                id = it.id,
                number = it.number,
                sort = it.sort,
                variant = it.variant,
                variantLabel = it.variantLabel,
                name = it.name,
                series = it.series,
                seriesNumber = it.seriesNumber,
                toyCode = it.toyCode,
                isNew = it.isNew,
                isTh = it.isTh,
                isSth = it.isSth,
                exclusive = it.exclusive,
                imageFile = it.imageFile,
                imageUrl = it.imageUrl,
                photo = photo(it),
            )
        },
    )
}

/**
 * Puts a backup's Dex back, inside the importer's transaction. A list the phone already has (that wiki year, or an
 * own dex of the same name) is reused and its entries matched: wiki ones by number and variant, own ones by number,
 * name and variant label. An own dex gets the entries it lacks; a wiki year keeps what the wiki says.
 */
internal class DexRestorer(db: AppDatabase, private val photos: PhotoStore, private val root: File, private val now: () -> Long) {
    private val dao = db.dex()

    /** Photos written into dex folders: to remove again when the import fails. */
    val written = mutableListOf<File>()

    /** @return item id in the backup → item id here */
    suspend fun restore(zip: ZipFile, catalogs: List<DexBackup.Catalog>): Map<Long, Long> {
        val ids = mutableMapOf<Long, Long>()

        for (catalog in catalogs) {
            val custom = catalog.source == DexMatcher.CUSTOM
            if (catalog.brand.isBlank() || catalog.name.isBlank() || (!custom && WikiSource.fromKey(catalog.source) == null)) continue

            val local = when {
                custom -> dao.customNamed(catalog.name)
                catalog.year != null -> dao.catalogFor(catalog.source, catalog.year)
                else -> null
            }
            val catalogId = local?.id ?: dao.insertCatalog(
                CatalogEntity(
                    brand = catalog.brand,
                    name = catalog.name,
                    year = catalog.year,
                    source = catalog.source,
                    sourcePage = catalog.sourcePage,
                    syncedAt = catalog.syncedAt?.let(WebBackupFormat::parseTimestamp),
                    createdAt = catalog.createdAt?.let(WebBackupFormat::parseTimestamp) ?: now(),
                ),
            )
            val existing = if (local != null) dao.items(catalogId) else emptyList()
            val byKey = existing.associateBy { key(it.number, it.variant, it.name, it.variantLabel, custom) }
            // Highest variant per number, for entries added to an own dex that already has that number.
            val variants = existing.groupBy { it.number }.mapValues { (_, items) -> items.maxOf { it.variant } }.toMutableMap()

            for (item in catalog.items) {
                if (item.number.isBlank() || item.name.isBlank()) continue

                val match = byKey[key(item.number, item.variant, item.name, item.variantLabel, custom)]
                if (match != null) {
                    ids[item.id] = match.id
                    continue
                }
                if (local != null && !custom) continue

                val variant = variants[item.number]?.takeIf { local != null }?.plus(1) ?: item.variant
                variants[item.number] = maxOf(variants[item.number] ?: 0, variant)

                val id = dao.insertItem(
                    CatalogItemEntity(
                        catalogId = catalogId,
                        number = item.number,
                        sort = item.sort,
                        variant = variant,
                        variantLabel = item.variantLabel,
                        name = item.name,
                        series = item.series,
                        seriesNumber = item.seriesNumber,
                        toyCode = item.toyCode,
                        isNew = item.isNew,
                        isTh = item.isTh,
                        isSth = item.isSth,
                        exclusive = item.exclusive,
                        imageFile = item.imageFile,
                        imageUrl = item.imageUrl.takeIf { !custom },
                        imagePath = item.photo?.takeIf { custom }?.let { extract(zip, it, catalogId) },
                    ),
                )
                ids[item.id] = id
            }
        }
        return ids
    }

    private fun key(number: String, variant: Int, name: String, variantLabel: String?, custom: Boolean) =
        if (custom) "$number|${name.trim()}|${variantLabel.orEmpty().trim()}".lowercase() else "$number|$variant"

    /** Copies an own dex's photo out of the zip into its folder; returns its path under [root]. */
    private fun extract(zip: ZipFile, name: String, catalogId: Long): String? {
        if (!DexBackup.PHOTO_ENTRY.matches(name)) return null

        val entry = zip.getEntry(name)?.takeIf { it.size in 0..WebBackupFormat.MAX_PHOTO_BYTES } ?: return null
        val pending = photos.addFromBackup(zip.getInputStream(entry).use { it.readBytes() }) ?: return null
        val path = "dex/$catalogId/$pending"
        val target = File(root, path)

        photos.moveTo(pending, target)
        written += target
        return path
    }
}
