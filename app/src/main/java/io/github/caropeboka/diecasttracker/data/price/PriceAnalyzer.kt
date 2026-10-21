package io.github.caropeboka.diecasttracker.data.price

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How a listing compares with the diecast being priced, as the AI judged it. */
@Serializable
enum class ListingMatch(val key: String, val reason: String) {
    @SerialName("match") Match("match", ""),
    @SerialName("different_variant") DifferentVariant("different_variant", "Varian berbeda"),
    @SerialName("different_condition") DifferentCondition("different_condition", "Kondisi berbeda"),
    @SerialName("bundle") Bundle("bundle", "Paket / borongan"),
    @SerialName("different_item") DifferentItem("different_item", "Barang lain"),
    @SerialName("unclear") Unclear("unclear", "Tidak jelas");

    companion object {
        fun fromKey(key: String?): ListingMatch = entries.find { it.key == key } ?: Unclear
    }
}

@Serializable
enum class ListingStatus {
    @SerialName("included") Included,

    /** A match, but its price is far from the others. */
    @SerialName("outlier") Outlier,
    @SerialName("excluded") Excluded,
}

/** One product listing read from a marketplace screenshot, and whether it counts. */
@Serializable
data class PriceListing(
    val title: String,
    /** What a buyer pays now, whole Rupiah; the lowest of a range. */
    @SerialName("price_idr") val price: Long,
    @SerialName("is_price_range") val isPriceRange: Boolean = false,
    val shop: String? = null,
    val location: String? = null,
    val sold: Int? = null,
    val rating: Double? = null,
    @SerialName("is_ad") val isAd: Boolean = false,
    /** tokopedia, shopee, bukalapak, facebook or other. */
    val marketplace: String = "other",
    val match: ListingMatch = ListingMatch.Unclear,
    /** The AI's few words on [match]. */
    val reason: String? = null,
    val status: ListingStatus = ListingStatus.Excluded,
    @SerialName("excluded_reason") val excludedReason: String? = null,
)

/** Over the counted listings only. [spread]: the middle half's width against the median. */
@Serializable
data class PriceStats(
    val count: Int,
    val min: Long,
    val q1: Long,
    val median: Long,
    /** The median rounded to a tidy Rp 500: what "Pakai" saves. */
    val suggested: Long,
    val q3: Long,
    val max: Long,
    val spread: Double,
)

enum class PriceAccuracy(val key: String, val label: String) {
    High("high", "Akurasi tinggi"),
    Medium("medium", "Akurasi sedang"),
    Low("low", "Akurasi rendah"),
    Insufficient("insufficient", "Data kurang");

    companion object {
        fun fromKey(key: String?): PriceAccuracy = entries.find { it.key == key } ?: Insufficient
    }
}

data class PriceAnalysis(
    /** Counted first (cheapest first), then outliers, then the rest. */
    val listings: List<PriceListing>,
    val stats: PriceStats?,
    val accuracy: PriceAccuracy,
    val reasons: List<String>,
    val tips: List<String>,
)

/**
 * Turns listings read from marketplace screenshots into a market price: which listings count, the median and spread,
 * and how far the result can be trusted (web: `PriceAnalyzer.php`). Pure arithmetic, no AI, so the numbers are exact.
 */
object PriceAnalyzer {
    fun analyze(read: List<PriceListing>): PriceAnalysis {
        var listings = dedupe(read).map { listing ->
            val usable = listing.match == ListingMatch.Match && listing.price > 0
            listing.copy(
                status = if (usable) ListingStatus.Included else ListingStatus.Excluded,
                excludedReason = if (usable) null else listing.match.takeIf { it != ListingMatch.Match }?.reason ?: ListingMatch.Unclear.reason,
            )
        }
        listings = markOutliers(listings)

        val prices = listings.filter { it.status == ListingStatus.Included }.map { it.price }.sorted()
        val stats = if (prices.isEmpty()) null else stats(prices)
        val (accuracy, reasons, tips) = judge(listings, stats)

        val sorted = listings.sortedWith(compareBy<PriceListing> { it.status.ordinal }.thenBy { it.price })

        return PriceAnalysis(sorted, stats, accuracy, reasons, tips)
    }

    /** Overlapping screenshots (scrolling) show the same listing twice. */
    private fun dedupe(listings: List<PriceListing>): List<PriceListing> = listings
        .filter { it.title.isNotBlank() }
        .distinctBy { listing ->
            listOf(
                listing.title.replace(WHITESPACE, " ").lowercase().take(40),
                listing.shop.orEmpty().lowercase(),
                listing.price.toString(),
            ).joinToString("|")
        }

    /** Tukey fences: with enough listings, prices far outside the middle half are typos, wrong items or wishful sellers. */
    private fun markOutliers(listings: List<PriceListing>): List<PriceListing> {
        val prices = listings.filter { it.status == ListingStatus.Included }.map { it.price }.sorted()
        if (prices.size < 5) return listings

        val q1 = percentile(prices, 25.0)
        val q3 = percentile(prices, 75.0)
        val fence = 1.5 * (q3 - q1)

        return listings.map { listing ->
            val price = listing.price.toDouble()
            if (listing.status == ListingStatus.Included && (price < q1 - fence || price > q3 + fence)) {
                listing.copy(
                    status = ListingStatus.Outlier,
                    excludedReason = if (price > q3) "Harga jauh di atas yang lain" else "Harga jauh di bawah yang lain",
                )
            } else {
                listing
            }
        }
    }

    /** [prices] sorted ascending. */
    private fun stats(prices: List<Long>): PriceStats {
        val median = percentile(prices, 50.0)
        val q1 = percentile(prices, 25.0)
        val q3 = percentile(prices, 75.0)

        return PriceStats(
            count = prices.size,
            min = prices.first(),
            q1 = q1.roundToLong(),
            median = median.roundToLong(),
            suggested = (median / 500).roundToLong() * 500,
            q3 = q3.roundToLong(),
            max = prices.last(),
            spread = if (median > 0) ((q3 - q1) / median * 100).roundToLong() / 100.0 else 0.0,
        )
    }

    /** Linear interpolation between the closest ranks, like the web. */
    private fun percentile(sorted: List<Long>, percent: Double): Double {
        val index = percent / 100 * (sorted.size - 1)
        val lower = floor(index).toInt()
        val upper = ceil(index).toInt()
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower)
    }

    /** Scores how much to trust the median, and says why, in plain Indonesian. */
    private fun judge(listings: List<PriceListing>, stats: PriceStats?): Triple<PriceAccuracy, List<String>, List<String>> {
        val count = stats?.count ?: 0
        val reasons = mutableListOf<String>()
        val tips = mutableListOf<String>()

        val otherCondition = listings.count { it.match == ListingMatch.DifferentCondition }
        val unclear = listings.count { it.match == ListingMatch.Unclear }
        val sold = listings.count { it.status == ListingStatus.Included && (it.sold ?: 0) > 0 }

        if (stats == null || count < 2) {
            reasons += if (count == 1) {
                "Hanya 1 listing yang cocok — belum bisa disebut harga pasaran."
            } else {
                "Tidak ada listing yang cocok dengan diecast ini."
            }
            tips += "Coba kata kunci lain (misalnya tanpa nomor seri), lalu screenshot lagi."
            return Triple(PriceAccuracy.Insufficient, reasons, tips)
        }

        var score = when {
            count >= 8 -> 3
            count >= 5 -> 2
            count >= 3 -> 1
            else -> 0
        }
        reasons += "$count listing cocok dihitung" + if (score >= 2) " — cukup banyak." else " — masih sedikit."

        val spreadPercent = (stats.spread * 100).roundToInt()
        when {
            stats.spread <= 0.25 -> {
                score += 2
                reasons += "Harga cukup seragam (selisih tengah $spreadPercent% dari median)."
            }
            stats.spread <= 0.5 -> {
                score += 1
                reasons += "Harga agak bervariasi (selisih tengah $spreadPercent% dari median)."
            }
            else -> {
                reasons += "Harga sangat bervariasi (selisih tengah $spreadPercent% dari median)."
                tips += "Harga sangat beragam — pastikan varian dan kondisinya sama (misalnya STH vs biasa, carded vs loose)."
            }
        }

        if (sold >= 3) {
            score += 1
            reasons += "$sold listing menunjukkan jumlah terjual — ada bukti harga yang laku."
        } else {
            tips += "Urutkan hasil pencarian berdasarkan \"Terlaris\" supaya terlihat harga yang benar-benar laku."
        }

        if (unclear > 0 && unclear.toDouble() / listings.size.coerceAtLeast(1) > 0.4) {
            score -= 1
            reasons += "Banyak listing yang tidak jelas cocok atau tidak."
        }

        if (count < 5) tips += "Tambah screenshot (scroll ke bawah) supaya lebih banyak listing terbaca."
        if (otherCondition >= 3) tips += "Banyak listing beda kondisi — tambahkan kata \"carded\" atau \"loose\" di pencarian."

        val accuracy = when {
            score >= 5 -> PriceAccuracy.High
            score >= 3 -> PriceAccuracy.Medium
            else -> PriceAccuracy.Low
        }
        return Triple(accuracy, reasons, tips)
    }

    private val WHITESPACE = Regex("\\s+")
}
