package io.github.caropeboka.diecasttracker.data

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R

/**
 * Value lists of the web app (`app/Models/Diecast.php`). [key] is what the web stores and what its
 * backups contain; [label] is what the UI shows, [sheetLabel] what the web's Excel files say.
 */
enum class Rarity(val key: String, @StringRes val label: Int, val sheetLabel: String) {
    Regular("regular", R.string.rarity_regular, "Regular"),
    TreasureHunt("treasure_hunt", R.string.rarity_treasure_hunt, "Treasure Hunt"),
    SuperTreasureHunt("super_treasure_hunt", R.string.rarity_super_treasure_hunt, "Super Treasure Hunt"),
    Chase("chase", R.string.rarity_chase, "Chase"),
    Limited("limited", R.string.rarity_limited, "Limited"),
    Exclusive("exclusive", R.string.rarity_exclusive, "Exclusive");

    companion object {
        fun fromKey(key: String?): Rarity? = entries.find { it.key == key }
    }
}

/** The web's sheet says "Carded" where its form says "Carded (blister)". */
enum class Packaging(val key: String, @StringRes val label: Int, val sheetLabel: String) {
    Carded("carded", R.string.packaging_carded, "Carded"),
    Boxed("boxed", R.string.packaging_boxed, "Box"),
    Loose("loose", R.string.packaging_loose, "Loose");

    companion object {
        fun fromKey(key: String?): Packaging? = entries.find { it.key == key }
    }
}

enum class Condition(val key: String, @StringRes val label: Int, val sheetLabel: String) {
    Mint("mint", R.string.condition_mint, "Mint"),
    NearMint("near_mint", R.string.condition_near_mint, "Near mint"),
    Good("good", R.string.condition_good, "Good"),
    Fair("fair", R.string.condition_fair, "Fair"),
    Damaged("damaged", R.string.condition_damaged, "Rusak");

    companion object {
        fun fromKey(key: String?): Condition? = entries.find { it.key == key }
    }
}

/** [Spotted] = "Ditemui": seen somewhere (store, friend, event) but not owned. */
enum class DiecastStatus(val key: String, @StringRes val label: Int, val sheetLabel: String) {
    Owned("owned", R.string.status_owned, "Dimiliki"),
    Wishlist("wishlist", R.string.status_wishlist, "Wishlist"),
    Spotted("spotted", R.string.status_spotted, "Ditemui"),
    Sold("sold", R.string.status_sold, "Terjual");

    /** Not in the garage: no purchase or storage details to keep. */
    val isOwnedOrSold get() = this == Owned || this == Sold

    companion object {
        fun fromKey(key: String?): DiecastStatus? = entries.find { it.key == key }
    }
}

/** Where a market value came from: typed in, or taken from a marketplace price check (phase 5). */
enum class ValueSource(val key: String) {
    Manual("manual"),
    PriceCheck("price_check"),

    /** The AI's rough estimate from the web. */
    AiEstimate("ai_estimate");

    companion object {
        fun fromKey(key: String?): ValueSource? = entries.find { it.key == key }
    }
}

/** How a diecast got its Dex entry: by the matcher, checked by the collector, or "not in the Dex". */
enum class CatalogMatch(val key: String) {
    Auto("auto"),
    Confirmed("confirmed"),
    Ignored("ignored");

    companion object {
        fun fromKey(key: String?): CatalogMatch? = entries.find { it.key == key }
    }
}

val SCALES = listOf("1:64", "1:62", "1:60", "1:43", "1:32", "1:24", "1:18", "1:87", "1:100")

const val DEFAULT_SCALE = "1:64"

/** Offered in the brand field next to the brands already in the collection. */
val COMMON_BRANDS = listOf(
    "Hot Wheels", "Matchbox", "Tomica", "Mini GT", "Inno64", "Tarmac Works", "Pop Race", "Kaido House",
    "Majorette", "Greenlight", "Johnny Lightning", "M2 Machines", "Maisto", "Bburago",
)

const val MAX_PHOTOS = 8
const val MAX_TAGS = 20
const val MAX_TAG_LENGTH = 50
const val MAX_MONEY = 999_999_999_999L
const val MAX_QUANTITY = 9999
const val MIN_YEAR = 1950
