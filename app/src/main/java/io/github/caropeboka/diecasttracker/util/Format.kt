package io.github.caropeboka.diecasttracker.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

val INDONESIAN: Locale = Locale.forLanguageTag("id-ID")

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", INDONESIAN)

/** "35.000" */
fun groupThousands(value: Long): String {
    val digits = abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** "Rp 35.000" (id-ID, no decimals); "-" when empty. */
fun formatRupiah(value: Long?): String = if (value == null) "-" else "Rp ${groupThousands(value)}"

/** Short form for tight spots: "Rp 54,5 rb", "Rp 1,2 jt", "Rp 3 M". */
fun formatRupiahCompact(value: Long?): String {
    if (value == null) return "-"

    val units = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "M", 1_000_000L to "jt", 1_000L to "rb")
    // The biggest unit that still shows at least "1" once rounded (999.950 → "1 jt", not "1.000 rb").
    val (size, unit) = units.firstOrNull { abs((value * 10.0 / it.first).roundToLong()) >= 10 }
        ?: return formatRupiah(value)
    val tenths = (value * 10.0 / size).roundToLong()
    val whole = tenths / 10
    val fraction = abs(tenths % 10)

    return "Rp ${groupThousands(whole)}${if (fraction != 0L) ",$fraction" else ""} $unit"
}

/** "18 Okt 2026" */
fun formatDate(date: LocalDate?): String = date?.format(SHORT_DATE) ?: "-"

/** "18 Okt" */
fun formatDayMonth(date: LocalDate): String = date.format(DAY_MONTH)

/** "Minggu, 18 Oktober 2026" */
fun formatDayLong(date: LocalDate): String = date.format(LONG_DAY)

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", INDONESIAN)
private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", INDONESIAN)

/** Like PHP's SORT_NATURAL | SORT_FLAG_CASE: "Toko 2" before "toko 10". */
val naturalCaseInsensitiveOrder: Comparator<String> = Comparator { a, b ->
    val chunk = Regex("\\d+|\\D+")
    val left = chunk.findAll(a.lowercase(INDONESIAN)).map { it.value }.toList()
    val right = chunk.findAll(b.lowercase(INDONESIAN)).map { it.value }.toList()

    for (i in 0 until minOf(left.size, right.size)) {
        val x = left[i]
        val y = right[i]
        val result = if (x[0].isDigit() && y[0].isDigit()) {
            compareValuesBy(x, y, { it.trimStart('0').length }, { it.trimStart('0') })
        } else {
            x.compareTo(y)
        }
        if (result != 0) return@Comparator result
    }
    left.size.compareTo(right.size)
}
