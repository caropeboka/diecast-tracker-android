package io.github.caropeboka.diecasttracker.data

import io.github.caropeboka.diecasttracker.data.ai.ScanFields
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem

/** Finds diecasts in the collection that look like a scanned one: "do I already have this?" (web: `CollectionMatcher`). */
class CollectionMatcher(private val repository: DiecastRepository) {
    /** Best match first. */
    suspend fun find(fields: ScanFields, limit: Int = 5): List<DiecastListItem> {
        val name = normalize(fields.name)
        if (name.isEmpty()) return emptyList()

        val number = normalize(fields.collectionNumber)
        val scores = repository.matchCandidates(fields.brand?.takeIf(String::isNotBlank))
            .map { candidate ->
                var score = similarity(name, normalize(candidate.name))
                if (number.isNotEmpty() && number == normalize(candidate.collectionNumber)) score += 20
                candidate.id to score
            }
            .filter { (_, score) -> score >= 75 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)

        val items = repository.listItems(scores.map { it.first }).associateBy { it.id }
        return scores.mapNotNull { (id, _) -> items[id] }
    }

    companion object {
        /**
         * Two numbers as printed that can't be the same release: "4/250 · 1/5" and "3/5" differ in the series' fifth,
         * "15" and "23" too. Unknown (null, or nothing to compare like "1/5" against "61/250") is not different: the
         * AI often misses a number. Cards of the same name with other numbers are other cars (the 2026 Batman
         * series has two "Batmobile" cards).
         */
        fun otherNumber(a: String?, b: String?): Boolean {
            val first = fractions(a)
            val second = fractions(b)
            if (first.isNotEmpty() || second.isNotEmpty()) {
                return first.keys.intersect(second.keys).any { first[it] != second[it] }
            }
            val x = a.orEmpty().filter(Char::isDigit).trimStart('0')
            val y = b.orEmpty().filter(Char::isDigit).trimStart('0')
            return x.isNotEmpty() && y.isNotEmpty() && x != y
        }

        /** "61/250 · 2/10" → {250: 61, 10: 2}: each number by what it counts out of. */
        private fun fractions(value: String?): Map<Int, Int> = FRACTION.findAll(value.orEmpty())
            .mapNotNull { match ->
                val (part, whole) = match.destructured
                whole.toIntOrNull()?.let { it to (part.toIntOrNull() ?: return@mapNotNull null) }
            }
            .toMap()

        private val FRACTION = Regex("(\\d{1,4})\\s*/\\s*(\\d{1,4})")

        /** 0–100. Containment counts as a strong match ("Datsun 510" vs "'71 Datsun 510"). */
        fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a in b || b in a) return 90 + 10.0 * minOf(a.length, b.length) / maxOf(a.length, b.length)
            return similarText(a, b) * 2 * 100.0 / (a.length + b.length)
        }

        /** Lowercase letters and digits only. */
        fun normalize(value: String?): String = buildString {
            value.orEmpty().forEach { c ->
                when (c) {
                    in 'a'..'z', in '0'..'9' -> append(c)
                    in 'A'..'Z' -> append(c + ('a' - 'A'))
                }
            }
        }

        /**
         * PHP's `similar_text`: the longest common substring, plus the same for what is left on either side of it.
         * Ported as is so a scan finds the same matches as on the web.
         */
        fun similarText(a: String, b: String): Int {
            var max = 0
            var first = 0
            var second = 0
            for (i in a.indices) {
                for (j in b.indices) {
                    var length = 0
                    while (i + length < a.length && j + length < b.length && a[i + length] == b[j + length]) length++
                    if (length > max) {
                        max = length
                        first = i
                        second = j
                    }
                }
            }
            if (max == 0) return 0

            return max +
                similarText(a.substring(0, first), b.substring(0, second)) +
                similarText(a.substring(first + max), b.substring(second + max))
        }
    }
}
