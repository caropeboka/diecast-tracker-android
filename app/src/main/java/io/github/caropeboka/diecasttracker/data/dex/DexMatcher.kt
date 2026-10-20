package io.github.caropeboka.diecasttracker.data.dex

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.caropeboka.diecasttracker.data.CatalogMatch
import io.github.caropeboka.diecasttracker.data.DEFAULT_SCALE
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.DexDao
import io.github.caropeboka.diecasttracker.data.db.DexItemRow
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import kotlin.math.max
import kotlin.math.roundToInt

/** Numbers printed on a card: the line number ("61/250", "MB12", "No. 23"), the series number ("2/10") and a product code. */
data class CardNumbers(val main: Int? = null, val series: String? = null, val code: String? = null) {
    val isEmpty get() = main == null && series == null && code == null
}

/** A dex entry a diecast could be, with how sure the matcher is and the release year when it differs. */
data class DexSuggestion(val row: DexItemRow, val score: Int, val yearFix: Int?)

/** What a dex entry fills in on the add form, written the way the card prints it (web: `CatalogMatcher::prefill`). */
data class DexPrefill(
    val name: String,
    val brand: String,
    val series: String?,
    val collectionNumber: String,
    val year: Int?,
    val rarity: Rarity,
    val scale: String?,
)

/**
 * Finds which dex entry a diecast is: by the numbers printed on the card ("61/250 · 2/10", "MB12", "MGT00512"),
 * the model name, the series and the year — across years, because the year read from a card is often the casting's
 * copyright year rather than the release year (web: `CatalogMatcher.php`).
 */
class DexMatcher(private val dao: DexDao) {
    /** Best candidates, one per dex entry, best first. */
    suspend fun suggest(diecast: DiecastEntity, limit: Int = 3): List<DexSuggestion> {
        val brand = diecast.brand?.trim().orEmpty()
        if (brand.isEmpty()) return emptyList()

        val catalogIds = dao.catalogIdsForBrand(brand)
        val numbers = numbers(diecast.collectionNumber)
        val tokens = tokens(diecast.name)
        // The most distinctive words of the name, to find candidates when the numbers don't help.
        val keywords = tokens.filter { it.length >= 3 && !it.all(Char::isDigit) }.sortedByDescending { it.length }.take(2)

        if (catalogIds.isEmpty() || (numbers.isEmpty && keywords.isEmpty())) return emptyList()

        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        numbers.main?.let {
            conditions += "ci.sort = ?"
            conditions += "ci.number = ?"
            args += it
            args += it.toString()
        }
        numbers.code?.let {
            conditions += "ci.number = ?"
            args += it
        }
        numbers.series?.let {
            conditions += "ci.series_number = ?"
            args += it
        }
        keywords.forEach {
            conditions += "ci.name LIKE ?"
            args += "%$it%"
        }

        val sql = "SELECT ci.*, c.name AS catalog_name, c.year AS catalog_year, c.source AS catalog_source, c.brand AS catalog_brand " +
            "FROM catalog_items ci JOIN catalogs c ON c.id = ci.catalog_id " +
            "WHERE ci.catalog_id IN (${catalogIds.joinToString(",") { "?" }}) AND (${conditions.joinToString(" OR ")}) LIMIT 500"
        val candidates = dao.itemRows(SimpleSQLiteQuery(sql, (catalogIds + args).toTypedArray()))

        return candidates
            .map { it to score(diecast, it, numbers, tokens) }
            .filter { (_, score) -> score >= 30 }
            // Highest score first; on a tie, the main release over its colour variants.
            .sortedWith(compareByDescending<Pair<DexItemRow, Int>> { it.second }.thenBy { it.first.item.variant })
            // One suggestion per entry (its best variant).
            .distinctBy { (row, _) -> "${row.item.catalogId}|${row.item.number}" }
            .take(limit)
            .map { (row, score) -> DexSuggestion(row, score, row.catalogYear?.takeIf { it != diecast.year }) }
    }

    /**
     * Links the diecast when one entry clearly wins, and drops an automatic link that no longer holds. Links the
     * collector confirmed or rejected are left alone.
     */
    suspend fun autoLink(diecast: DiecastEntity): Long? {
        if (diecast.catalogMatch == CatalogMatch.Confirmed || diecast.catalogMatch == CatalogMatch.Ignored) return diecast.catalogItemId

        val suggestions = suggest(diecast, 2)
        val best = suggestions.getOrNull(0)
        val next = suggestions.getOrNull(1)
        val sure = best != null && best.score >= AUTO_SCORE && (next == null || best.score - next.score >= AUTO_MARGIN)
        val itemId = if (sure) best!!.row.item.id else null

        if (diecast.catalogItemId != itemId) {
            dao.setLink(diecast.id, itemId, itemId?.let { CatalogMatch.Auto.key })
        }
        return itemId
    }

    /** Re-runs automatic linking over the collection (after a sync); returns how many are linked. */
    suspend fun autoLinkAll(): Int = dao.linkable().count { autoLink(it) != null }

    suspend fun prefill(row: DexItemRow): DexPrefill {
        val item = row.item
        val wiki = WikiSource.fromKey(row.catalogSource)
        val number = if (wiki == WikiSource.HotWheels) "${item.sort}/${dao.maxSort(item.catalogId) ?: item.sort}" else item.number

        return DexPrefill(
            name = item.name,
            brand = row.catalogBrand,
            series = item.series,
            collectionNumber = item.seriesNumber?.let { "$number · $it" } ?: number,
            year = row.catalogYear,
            rarity = when {
                item.isSth -> Rarity.SuperTreasureHunt
                item.isTh -> Rarity.TreasureHunt
                else -> Rarity.Regular
            },
            scale = DEFAULT_SCALE.takeIf { wiki != null },
        )
    }

    private fun score(diecast: DiecastEntity, row: DexItemRow, numbers: CardNumbers, tokens: List<String>): Int {
        val item = row.item
        var score = 0
        val number = item.number.trimStart('0').uppercase()
        val wiki = row.catalogSource != CUSTOM

        if (numbers.code != null && item.number.uppercase() == numbers.code) {
            score += 60
        } else if (numbers.main != null && ((wiki && item.sort == numbers.main) || number == numbers.main.toString())) {
            score += 50
        }

        if (numbers.series != null && item.seriesNumber == numbers.series) score += 15

        if (item.series != null && diecast.series != null && similarity(tokens(item.series), tokens(diecast.series)) >= 0.6) {
            score += 15
        }

        score += (similarity(tokens, tokens(item.name)) * 40).roundToInt()

        if (row.catalogYear != null && row.catalogYear == diecast.year) score += 10

        // A Treasure Hunt on the card points to the TH variant of that number, and vice versa.
        val hunt = when (diecast.rarity) {
            Rarity.SuperTreasureHunt -> item.isSth
            Rarity.TreasureHunt -> item.isTh
            else -> null
        }
        if (hunt == true) {
            score += 5
        } else if (hunt == null && (item.isTh || item.isSth)) {
            score -= 5
        }

        return score
    }

    companion object {
        /** Link on its own only when this sure… */
        const val AUTO_SCORE = 90

        /** …and this far ahead of the next entry. */
        const val AUTO_MARGIN = 20

        const val CUSTOM = "custom"

        private val PAIR = Regex("(\\d{1,3})\\s*/\\s*(\\d{1,3})")
        private val LINE_NUMBER = Regex("\\b(?:MB|No\\.?|#)\\s*0*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
        private val CODE = Regex("\\b([A-Z]{2,5}-?\\d{3,6})\\b", RegexOption.IGNORE_CASE)
        private val MB_CODE = Regex("^MB\\d+$", RegexOption.IGNORE_CASE)
        private val SHORT_YEAR = Regex("['’`]\\d{2}\\b")
        private val FULL_YEAR = Regex("\\b(?:19|20)\\d{2}\\b")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        fun numbers(text: String?): CardNumbers {
            var main: Int? = null
            var series: String? = null
            val value = text.orEmpty()

            for (pair in PAIR.findAll(value)) {
                val number = pair.groupValues[1].toInt()
                val of = pair.groupValues[2].toInt()
                // Out of 50+ is the line ("61/250", "12/125"); a small total is the series ("2/10").
                if (of >= 50) {
                    if (main == null) main = number
                } else if (series == null) {
                    series = "$number/$of"
                }
            }

            if (main == null) LINE_NUMBER.find(value)?.let { main = it.groupValues[1].toInt() }

            val code = CODE.find(value)?.groupValues?.get(1)?.takeIf { !MB_CODE.matches(it) }?.uppercase()

            return CardNumbers(main, series, code)
        }

        /** Name words for comparison, ignoring years ("'95", "1995") and punctuation. */
        fun tokens(name: String?): List<String> {
            var text = name.orEmpty().lowercase()
            text = SHORT_YEAR.replace(text, " ")
            text = FULL_YEAR.replace(text, " ")
            text = NOT_WORD.replace(text, " ")
            return text.split(' ').filter(String::isNotEmpty).distinct()
        }

        /** Share of words in common (0–1). */
        fun similarity(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            return a.count { it in b }.toDouble() / max(a.size, b.size)
        }
    }
}
