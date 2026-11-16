package io.github.caropeboka.diecasttracker.data.tidy

import androidx.annotation.StringRes
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.COMMON_BRANDS
import io.github.caropeboka.diecasttracker.data.CollectionMatcher
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.SCALES
import io.github.caropeboka.diecasttracker.data.db.DashboardRow
import io.github.caropeboka.diecasttracker.data.tidyScale
import java.text.Normalizer

/** The fields whose spellings are compared. */
enum class TidyField(val key: String, @StringRes val label: Int) {
    Brand("brand", R.string.field_brand),
    Make("make", R.string.field_make),
    Series("series", R.string.field_series),
    Scale("scale", R.string.field_scale),
}

/** What the tidy-up looks at of one diecast. */
data class TidyRow(
    val id: Long,
    val name: String,
    val brand: String? = null,
    val make: String? = null,
    val series: String? = null,
    val scale: String? = null,
    val number: String? = null,
    val color: String? = null,
    val year: Int? = null,
    val rarity: Rarity = Rarity.Regular,
) {
    fun value(field: TidyField): String? = when (field) {
        TidyField.Brand -> brand
        TidyField.Make -> make
        TidyField.Series -> series
        TidyField.Scale -> scale
    }
}

fun DashboardRow.toTidyRow() = TidyRow(
    id = item.id,
    name = item.name,
    brand = item.brand,
    make = make,
    series = item.series,
    scale = scale,
    number = item.collectionNumber,
    color = item.color,
    year = item.year,
    rarity = item.rarity,
)

/** One way a value is written, by the diecasts [ids] called [names]. */
data class Spelling(val text: String, val ids: List<Long>, val names: List<String>) {
    val count get() = ids.size
}

/**
 * Spellings of one [field] that look like one value. [sure]: they differ only in case, spaces, dashes or accents
 * ("Mini GT", "MINI-GT", "Citroen", "Citroën") or in how a scale is written ("1/64"); otherwise they are only alike
 * ("Hot Wheel", "Hot Wheels"). A group may hold one spelling that is better written another way ("1/60", "hot
 * wheels"). [options]: what they can all become, the suggested one first: the brand's or scale's usual writing, else
 * the spelling most diecasts use.
 */
data class SpellingGroup(val field: TidyField, val spellings: List<Spelling>, val sure: Boolean, val options: List<String>) {
    val suggested get() = options.first()
    val diecasts get() = spellings.sumOf { it.count }

    /** For remembering in the UI which option is picked. */
    val key get() = field.key + "|" + spellings.map { it.text }.sorted().joinToString(SEPARATOR)
}

/**
 * Diecasts that look entered more than once (oldest first): one name and brand, and no number, colour, year, scale or
 * rarity that tells them apart. The status doesn't count: a wishlist entry for a car already owned is one too many.
 */
data class DuplicateGroup(val ids: List<Long>)

data class TidyReport(val spellings: List<SpellingGroup> = emptyList(), val duplicates: List<DuplicateGroup> = emptyList()) {
    val count get() = spellings.size + duplicates.size
}

private const val SEPARATOR = "\u001F"

/**
 * Finds what is written in more than one way and what looks entered twice. Nothing is changed here; what the
 * collector leaves as it is is remembered as pairs ([ignoreKeys]), so a new spelling or diecast still shows.
 */
object Tidy {
    /** Shorter ones are too easily alike by chance ("Mini", "Mino"). */
    private const val ALIKE_MIN_LENGTH = 5

    fun report(rows: List<TidyRow>, ignored: Set<String> = emptySet()): TidyReport = TidyReport(
        spellings = TidyField.entries.flatMap { spellings(rows, it, ignored) },
        duplicates = duplicates(rows, ignored),
    )

    /**
     * What "leave as it is" remembers for [group]: every pair in it, and each spelling on its own (so "HOT WHEELS"
     * doesn't come back alone, offered as "Hot Wheels"). "Not the same" on spellings only alike remembers only the
     * pairs that are only alike: two of them written the same but for case come back as a group of their own.
     */
    fun ignoreKeys(group: SpellingGroup): Set<String> {
        val texts = group.spellings.map { it.text }
        if (!group.sure) {
            return pairs(texts) { a, b -> if (keyOf(group.field, a) == keyOf(group.field, b)) "" else pair(group.field, a, b) } - ""
        }
        return pairs(texts) { a, b -> pair(group.field, a, b) } + texts.map { single(group.field, it) }
    }

    /** What "not a duplicate" remembers for [group]: every pair of diecasts in it. */
    fun ignoreKeys(group: DuplicateGroup): Set<String> = pairs(group.ids) { a, b -> duplicate(a, b) }

    /** Lower case letters and digits, accents dropped ("Citroën" = "citroen", "MINI-GT" = "minigt"). */
    fun key(value: String?): String {
        val plain = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD)
        return buildString {
            plain.forEach { c -> if (Character.isLetterOrDigit(c)) append(c.lowercaseChar()) }
        }
    }

    /** "1/64", "1 : 64" and "1:64" are one scale; other scales are never alike. */
    private fun scaleKey(value: String) = tidyScale(value).filterNot(Char::isWhitespace).lowercase()

    private fun keyOf(field: TidyField, value: String) = if (field == TidyField.Scale) scaleKey(value) else key(value)

    private fun spellings(rows: List<TidyRow>, field: TidyField, ignored: Set<String>): List<SpellingGroup> {
        val byText = rows
            .mapNotNull { row -> row.value(field)?.takeIf(String::isNotBlank)?.let { it to row } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { keyOf(field, it).isNotEmpty() }
        val texts = byText.keys.toList()
        val keys = texts.map { keyOf(field, it) }

        val groups = UnionFind(texts.size)
        for (i in texts.indices) {
            for (j in i + 1 until texts.size) {
                if (pair(field, texts[i], texts[j]) in ignored) continue
                if (keys[i] == keys[j] || (field != TidyField.Scale && alike(keys[i], keys[j]))) groups.join(i, j)
            }
        }

        return groups.sets()
            .mapNotNull { members ->
                val spellings = members
                    .map { index -> byText.getValue(texts[index]).let { found -> Spelling(texts[index], found.map { it.id }, found.map { it.name }) } }
                    .sortedWith(preferred)
                val usual = spellings.firstNotNullOfOrNull { usual(field, it.text) }
                if (spellings.size == 1 && (usual == null || usual == spellings[0].text || single(field, spellings[0].text) in ignored)) {
                    return@mapNotNull null
                }
                SpellingGroup(
                    field = field,
                    spellings = spellings,
                    sure = members.map { keys[it] }.distinct().size == 1,
                    options = (listOfNotNull(usual) + spellings.map { it.text }).distinct(),
                )
            }
            .sortedByDescending { it.diecasts }
    }

    /** The usual way to write [text]: a well-known brand of the same letters, or a scale with a colon. */
    private fun usual(field: TidyField, text: String): String? = when (field) {
        TidyField.Brand -> key(text).let { wanted -> COMMON_BRANDS.find { key(it) == wanted } }
        TidyField.Scale -> tidyScale(text).let { tidy -> SCALES.find { it == tidy } ?: tidy.takeIf { it != text } }
        else -> null
    }

    /** Most used first; on a tie written in both cases, with its accents, then the longer (a letter left out is the usual slip). */
    private val preferred = compareByDescending<Spelling> { it.count }
        .thenByDescending { text -> text.text.any(Char::isUpperCase) && text.text.any(Char::isLowerCase) }
        .thenByDescending { text -> text.text.any { it.code > 127 } }
        .thenByDescending { it.text.length }
        .thenBy { it.text }

    /**
     * One slip apart: a letter left out, one too many, a wrong one or two swapped ("Lamborgini", "Hot Wheel").
     * A word for another is not a slip ("Thrill Racers - Ice", "Thrill Racers - Space").
     */
    private fun alike(a: String, b: String): Boolean {
        if (minOf(a.length, b.length) < ALIKE_MIN_LENGTH) return false
        // Numbers tell lines and years apart ("Mainline 2024", "Batman 2").
        if (a.filter(Char::isDigit) != b.filter(Char::isDigit)) return false
        return oneSlip(a, b)
    }

    private fun oneSlip(a: String, b: String): Boolean {
        if (a == b || kotlin.math.abs(a.length - b.length) > 1) return a == b
        var start = 0
        while (start < minOf(a.length, b.length) && a[start] == b[start]) start++
        var endA = a.length
        var endB = b.length
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val left = a.substring(start, endA)
        val right = b.substring(start, endB)
        return (left.length <= 1 && right.length <= 1) ||
            (left.length == 2 && right.length == 2 && left[0] == right[1] && left[1] == right[0])
    }

    private fun duplicates(rows: List<TidyRow>, ignored: Set<String>): List<DuplicateGroup> =
        rows
            .filter { key(it.name).isNotEmpty() }
            .groupBy { key(it.name) + "|" + key(it.brand) }
            .values
            .filter { it.size > 1 }
            .flatMap { bucket ->
                // Each joins the first group it could be a copy of every member of, so one without a number doesn't
                // tie "137/250" and "12/250" together.
                val groups = mutableListOf<MutableList<TidyRow>>()
                bucket.sortedBy { it.id }.forEach { row ->
                    val group = groups.find { members -> members.all { duplicate(it.id, row.id) !in ignored && sameCar(it, row) } }
                    if (group != null) group += row else groups += mutableListOf(row)
                }
                groups.filter { it.size > 1 }.map { members -> DuplicateGroup(members.map { it.id }) }
            }
            .sortedBy { it.ids.first() }

    /** Nothing given tells them apart; what is missing on one side is not a difference. */
    private fun sameCar(a: TidyRow, b: TidyRow): Boolean =
        a.rarity == b.rarity &&
            !CollectionMatcher.otherNumber(a.number, b.number) &&
            agree(key(a.color), key(b.color)) &&
            (a.year == null || b.year == null || a.year == b.year) &&
            agree(a.scale?.let(::scaleKey).orEmpty(), b.scale?.let(::scaleKey).orEmpty())

    private fun agree(a: String, b: String) = a.isEmpty() || b.isEmpty() || a == b

    private fun pair(field: TidyField, a: String, b: String): String =
        field.key + "|" + listOf(a, b).sorted().joinToString(SEPARATOR)

    private fun single(field: TidyField, text: String) = field.key + "|" + text

    private fun duplicate(a: Long, b: Long) = "dup|${minOf(a, b)},${maxOf(a, b)}"

    private fun <T> pairs(items: List<T>, key: (T, T) -> String): Set<String> = buildSet {
        for (i in items.indices) for (j in i + 1 until items.size) add(key(items[i], items[j]))
    }
}

private class UnionFind(size: Int) {
    private val parent = IntArray(size) { it }

    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }

    fun join(a: Int, b: Int) {
        val x = find(a)
        val y = find(b)
        if (x != y) parent[maxOf(x, y)] = minOf(x, y)
    }

    /** Each set's members in order, the sets by their first member. */
    fun sets(): List<List<Int>> = parent.indices.groupBy(::find).values.toList()
}
