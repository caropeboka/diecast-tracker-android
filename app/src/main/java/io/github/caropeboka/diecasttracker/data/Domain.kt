package io.github.caropeboka.diecasttracker.data

/**
 * Value lists of the web app (`app/Models/Diecast.php`). [key] is what the web stores and what its
 * backups contain; [label] is what the UI shows.
 */
enum class Rarity(val key: String, val label: String) {
    Regular("regular", "Regular"),
    TreasureHunt("treasure_hunt", "Treasure Hunt"),
    SuperTreasureHunt("super_treasure_hunt", "Super Treasure Hunt"),
    Chase("chase", "Chase"),
    Limited("limited", "Limited"),
    Exclusive("exclusive", "Exclusive");

    companion object {
        fun fromKey(key: String?): Rarity? = entries.find { it.key == key }
    }
}

enum class Packaging(val key: String, val label: String) {
    Carded("carded", "Carded (blister)"),
    Boxed("boxed", "Box"),
    Loose("loose", "Loose");

    companion object {
        fun fromKey(key: String?): Packaging? = entries.find { it.key == key }
    }
}

enum class Condition(val key: String, val label: String) {
    Mint("mint", "Mint"),
    NearMint("near_mint", "Near mint"),
    Good("good", "Good"),
    Fair("fair", "Fair"),
    Damaged("damaged", "Rusak");

    companion object {
        fun fromKey(key: String?): Condition? = entries.find { it.key == key }
    }
}

/** [Spotted] = "Ditemui": seen somewhere (store, friend, event) but not owned. */
enum class DiecastStatus(val key: String, val label: String) {
    Owned("owned", "Dimiliki"),
    Wishlist("wishlist", "Wishlist"),
    Spotted("spotted", "Ditemui"),
    Sold("sold", "Terjual");

    /** Not in the garage: no purchase or storage details to keep. */
    val isOwnedOrSold get() = this == Owned || this == Sold

    companion object {
        fun fromKey(key: String?): DiecastStatus? = entries.find { it.key == key }
    }
}

/** Where a market value came from: typed in, or taken from a marketplace price check (phase 5). */
enum class ValueSource(val key: String) {
    Manual("manual"),
    PriceCheck("price_check");

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

val SCALES = listOf("1:64", "1:43", "1:32", "1:24", "1:18", "1:87", "1:100")

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
const val MIN_YEAR = 1950
