package io.github.caropeboka.diecasttracker.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DEFAULT_SCALE
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.price.PriceAccuracy
import io.github.caropeboka.diecasttracker.data.price.PriceListing
import io.github.caropeboka.diecasttracker.data.price.PriceStats
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import java.time.LocalDate

/**
 * One diecast (or one line of identical ones, see [quantity]). Columns follow the web app's `diecasts` table.
 * Money is whole Rupiah.
 */
@Entity(
    tableName = "diecasts",
    indices = [Index("brand"), Index("series"), Index("status"), Index("catalog_item_id")],
)
data class DiecastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val make: String? = null,
    val series: String? = null,
    /** As printed on the card, e.g. "61/250 · 2/10". */
    @ColumnInfo(name = "collection_number") val collectionNumber: String? = null,
    val year: Int? = null,
    val scale: String? = DEFAULT_SCALE,
    val color: String? = null,
    @ColumnInfo(defaultValue = "regular") val rarity: Rarity = Rarity.Regular,
    @ColumnInfo(defaultValue = "carded") val packaging: Packaging = Packaging.Carded,
    val condition: Condition? = null,
    @ColumnInfo(defaultValue = "owned") val status: DiecastStatus = DiecastStatus.Owned,
    @ColumnInfo(defaultValue = "1") val quantity: Int = 1,
    @ColumnInfo(name = "purchase_price") val purchasePrice: Long? = null,
    @ColumnInfo(name = "market_value") val marketValue: Long? = null,
    @ColumnInfo(name = "market_value_source") val marketValueSource: ValueSource? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "market_value_updated_at") val marketValueUpdatedAt: Long? = null,
    /** Wishlist: the most you want to pay. */
    @ColumnInfo(name = "target_price") val targetPrice: Long? = null,
    @ColumnInfo(name = "purchase_date") val purchaseDate: LocalDate? = null,
    @ColumnInfo(name = "purchase_place") val purchasePlace: String? = null,
    @ColumnInfo(name = "purchase_latitude") val purchaseLatitude: Double? = null,
    @ColumnInfo(name = "purchase_longitude") val purchaseLongitude: Double? = null,
    @ColumnInfo(name = "storage_location") val storageLocation: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "ai_scanned", defaultValue = "0") val aiScanned: Boolean = false,
    /** The Dex entry (variant) this diecast is; kept in sync by the code, not a foreign key (web: `CatalogSync::forget`). */
    @ColumnInfo(name = "catalog_item_id") val catalogItemId: Long? = null,
    @ColumnInfo(name = "catalog_match") val catalogMatch: CatalogMatch? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    /** Epoch millis. */
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** A "dex": the full list of releases of a line (e.g. Hot Wheels mainline 2026), from a wiki or built by hand. */
@Entity(
    tableName = "catalogs",
    indices = [Index(value = ["source", "year", "name"], unique = true)],
)
data class CatalogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String,
    val name: String,
    val year: Int? = null,
    /** `hotwheels_wiki`, `matchbox_wiki` or `custom`. */
    val source: String,
    @ColumnInfo(name = "source_page") val sourcePage: String? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "synced_at") val syncedAt: Long? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

/** One release in a dex. Rows sharing a [number] (colour variants, TH/STH, exclusives) form one entry. */
@Entity(
    tableName = "catalog_items",
    foreignKeys = [
        ForeignKey(
            entity = CatalogEntity::class,
            parentColumns = ["id"],
            childColumns = ["catalog_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("catalog_id", "sort"), Index("catalog_id", "number")],
)
data class CatalogItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "catalog_id") val catalogId: Long,
    /** "061", "MB12", "MGT00512". */
    val number: String,
    val sort: Int = 0,
    /** 1 = the main release. */
    val variant: Int = 1,
    @ColumnInfo(name = "variant_label") val variantLabel: String? = null,
    val name: String,
    val series: String? = null,
    @ColumnInfo(name = "series_number") val seriesNumber: String? = null,
    @ColumnInfo(name = "toy_code") val toyCode: String? = null,
    @ColumnInfo(name = "flag_new", defaultValue = "0") val isNew: Boolean = false,
    @ColumnInfo(name = "flag_th", defaultValue = "0") val isTh: Boolean = false,
    @ColumnInfo(name = "flag_sth", defaultValue = "0") val isSth: Boolean = false,
    /** The store, e.g. "Walmart". */
    val exclusive: String? = null,
    /** The wiki's file name, the thumbnail URL to fetch, and the downloaded file (under `files/`). */
    @ColumnInfo(name = "image_file") val imageFile: String? = null,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
)

/** Photos of a diecast, in order; the first one is the cover. [fileName] lives in the photo folder. */
@Entity(
    tableName = "diecast_photos",
    foreignKeys = [
        ForeignKey(
            entity = DiecastEntity::class,
            parentColumns = ["id"],
            childColumns = ["diecast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("diecast_id")],
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "diecast_id") val diecastId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val position: Int,
)

/** Tag names are unique regardless of case ("JDM" = "jdm"). */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

@Entity(
    tableName = "diecast_tag",
    primaryKeys = ["diecast_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = DiecastEntity::class,
            parentColumns = ["id"],
            childColumns = ["diecast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tag_id")],
)
data class DiecastTagCrossRef(
    @ColumnInfo(name = "diecast_id") val diecastId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

/** One time a diecast was seen somewhere ("Ditemui"), optionally with the asking price and a photo. */
@Entity(
    tableName = "sightings",
    foreignKeys = [
        ForeignKey(
            entity = DiecastEntity::class,
            parentColumns = ["id"],
            childColumns = ["diecast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("diecast_id", "seen_on")],
)
data class SightingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "diecast_id") val diecastId: Long,
    val place: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "seen_on") val seenOn: LocalDate,
    val price: Long? = null,
    val notes: String? = null,
    @ColumnInfo(name = "photo_file") val photoFile: String? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
)

/**
 * A market price worked out from marketplace screenshots (web: `price_checks`). The screenshots themselves aren't kept,
 * only what was read from them.
 */
@Entity(
    tableName = "price_checks",
    foreignKeys = [
        ForeignKey(
            entity = DiecastEntity::class,
            parentColumns = ["id"],
            childColumns = ["diecast_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("diecast_id")],
)
data class PriceCheckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "diecast_id") val diecastId: Long,
    /** How many screenshots were read. */
    val screenshots: Int,
    /** Every listing read, counted or not; stored as JSON. */
    val listings: List<PriceListing>,
    /** Null when no listing counts. */
    val stats: PriceStats?,
    val accuracy: PriceAccuracy,
    val reasons: List<String>,
    val tips: List<String>,
    @ColumnInfo(name = "ai_note") val aiNote: String?,
    /** Epoch millis: its price became the market value. */
    @ColumnInfo(name = "applied_at") val appliedAt: Long? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A photo in the bulk scan queue (web: `scan_items` with source "bulk"): one photo, one diecast. It leaves the queue
 * once saved or thrown away.
 */
@Entity(tableName = "scan_items")
data class ScanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A pending photo of the [io.github.caropeboka.diecasttracker.data.PhotoStore] until its diecast is saved. */
    @ColumnInfo(name = "photo_file") val photoFile: String,
    val status: ScanItemStatus = ScanItemStatus.Pending,
    /** What the AI read; null until then (or when it failed). */
    val fields: ScanFields? = null,
    /** Why the AI couldn't read it. */
    val error: String? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A diecast with everything the edit screen shows. */
data class DiecastWithDetails(
    @Embedded val diecast: DiecastEntity,
    @Relation(parentColumn = "id", entityColumn = "diecast_id")
    val photos: List<PhotoEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(DiecastTagCrossRef::class, parentColumn = "diecast_id", entityColumn = "tag_id"),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumn = "id", entityColumn = "diecast_id")
    val sightings: List<SightingEntity>,
) {
    val orderedPhotos get() = photos.sortedWith(compareBy({ it.position }, { it.id }))
    val orderedTags get() = tags.sortedBy { it.name.lowercase() }

    /** Newest first. */
    val orderedSightings get() = sightings.sortedWith(compareByDescending<SightingEntity> { it.seenOn }.thenByDescending { it.id })
}

/** One card in the collection grid/list. */
data class DiecastListItem(
    val id: Long,
    val name: String,
    val brand: String?,
    val series: String?,
    val color: String?,
    val year: Int?,
    val rarity: Rarity,
    val status: DiecastStatus,
    val quantity: Int,
    @ColumnInfo(name = "purchase_price") val purchasePrice: Long?,
    @ColumnInfo(name = "market_value") val marketValue: Long?,
    @ColumnInfo(name = "target_price") val targetPrice: Long?,
    @ColumnInfo(name = "cover_file") val coverFile: String?,
    @ColumnInfo(name = "sightings_count") val sightingsCount: Int,
    @ColumnInfo(name = "sightings_min_price") val sightingsMinPrice: Long?,
)

/** A diecast with what the dashboard adds up; [item] doubles as its card. */
data class DashboardRow(
    @Embedded val item: DiecastListItem,
    val make: String?,
    val scale: String?,
    @ColumnInfo(name = "storage_location") val storageLocation: String?,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "has_tags") val hasTags: Boolean,
)

/**
 * An OpenStreetMap (Nominatim) answer kept for a while: its usage policy asks apps to cache, and the web caches
 * reverse lookups for 30 days and searches for 7. Failures are never stored.
 */
@Entity(tableName = "place_cache")
data class PlaceCacheEntity(
    @PrimaryKey val key: String,
    val body: String,
    /** Epoch millis. */
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

/** A named place with a point: where something was spotted ([date] = seen on) or bought. */
data class SpotRow(
    val place: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "seen_on") val seenOn: LocalDate?,
    @ColumnInfo(name = "purchase_date") val purchaseDate: LocalDate?,
    /** Epoch millis; dates a purchase without purchase date. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A sighting on the explore map; [photoFile] is the sighting's own photo, else the diecast's cover. */
data class SightingEventRow(
    val id: Long,
    @ColumnInfo(name = "diecast_id") val diecastId: Long,
    val name: String,
    val brand: String?,
    @ColumnInfo(name = "photo_file") val photoFile: String?,
    val place: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "seen_on") val seenOn: LocalDate,
    val price: Long?,
)

/** A diecast in the garage with a known purchase place, on the explore map. */
data class PurchaseEventRow(
    val id: Long,
    val name: String,
    val brand: String?,
    @ColumnInfo(name = "photo_file") val photoFile: String?,
    val place: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "purchase_date") val purchaseDate: LocalDate?,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val price: Long?,
)

/** What the duplicate check compares a scanned diecast against. */
data class MatchCandidate(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "collection_number") val collectionNumber: String?,
)

/** A dex item with what the UI and the matcher need of its catalog. */
data class DexItemRow(
    @Embedded val item: CatalogItemEntity,
    @ColumnInfo(name = "catalog_name") val catalogName: String,
    @ColumnInfo(name = "catalog_year") val catalogYear: Int?,
    @ColumnInfo(name = "catalog_source") val catalogSource: String,
    @ColumnInfo(name = "catalog_brand") val catalogBrand: String,
)

/** One dex item and the status of one diecast linked to it (null: none). */
data class ProgressRow(
    @ColumnInfo(name = "catalog_id") val catalogId: Long,
    val number: String,
    val status: DiecastStatus?,
)

/** A diecast linked to one of a dex's items. */
data class LinkedDiecastRow(
    val id: Long,
    val name: String,
    val status: DiecastStatus,
    @ColumnInfo(name = "catalog_item_id") val catalogItemId: Long,
    @ColumnInfo(name = "catalog_match") val catalogMatch: CatalogMatch?,
    @ColumnInfo(name = "cover_file") val coverFile: String?,
)

/** A diecast to check against the Dex, with its cover. */
data class ReviewRow(
    @Embedded val diecast: DiecastEntity,
    @ColumnInfo(name = "cover_file") val coverFile: String?,
)
