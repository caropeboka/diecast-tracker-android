package io.github.caropeboka.diecasttracker.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

val INDONESIAN: Locale = Locale.forLanguageTag("id-ID")

/** What prices are shown in. Everything is stored in whole Rupiah; the others are converted at [ExchangeRates]. */
enum class Currency(val key: String, val code: String, val symbol: String, val decimals: Int) {
    IDR("idr", "IDR", "Rp", 0),
    USD("usd", "USD", "$", 2),
    CNY("cny", "CNY", "¥", 2),
    ;

    companion object {
        fun of(key: String?) = entries.find { it.key == key } ?: IDR
    }
}

/** How much one US dollar and one yuan cost in Rupiah, and from when. */
@Serializable
data class ExchangeRates(val usd: Double, val cny: Double, val date: String, val source: String, val fetchedAt: Long) {
    fun idrPer(currency: Currency): Double? = when (currency) {
        Currency.IDR -> 1.0
        Currency.USD -> usd.takeIf { it > 0 }
        Currency.CNY -> cny.takeIf { it > 0 }
    }
}

/** How dates are written; [Language] the way the app's language writes them ("18 Okt 2026", "2026年10月18日"). */
enum class DateStyle(val key: String, val pattern: String?, val shortPattern: String?) {
    Language("language", null, null),
    Iso("iso", "yyyy-MM-dd", "MM-dd"),
    DayMonthYear("dmy", "dd-MM-yyyy", "dd-MM"),
    DaySlash("dmy_slash", "dd/MM/yyyy", "dd/MM"),
    MonthDayYear("mdy", "MM/dd/yyyy", "MM/dd"),
    ;

    companion object {
        fun of(key: String?) = entries.find { it.key == key } ?: Language
    }
}

/**
 * The choices every formatted number and date follows. Compose state, so screens redraw when one changes; also
 * readable outside a screen. [locale] is the app's language, set by the activity.
 */
object Formats {
    var locale: Locale by mutableStateOf(Locale.ENGLISH)
    var currency: Currency by mutableStateOf(Currency.IDR)
    var rates: ExchangeRates? by mutableStateOf(null)
    var dateStyle: DateStyle by mutableStateOf(DateStyle.Language)

    /** The currency prices are actually shown in: Rupiah until there's a rate for the chosen one. */
    val shownCurrency: Currency get() = currency.takeIf { rates?.idrPer(it) != null || it == Currency.IDR } ?: Currency.IDR

    internal val language: String get() = locale.language.let { if (it == "in") "id" else it }
}

/** Indonesian groups with dots and has a decimal comma; English and Chinese the other way round. */
private val groupMark get() = if (Formats.language == "id") '.' else ','
private val decimalMark get() = if (Formats.language == "id") ',' else '.'

/** "35.000" (or "35,000" outside Indonesian). */
fun groupThousands(value: Long): String {
    val digits = abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(groupMark.toString()).reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** A Rupiah amount in the chosen currency, without the symbol: 35000 → 35000.0 or 1.93. */
fun convertFromIdr(idr: Long): Double = idr / (Formats.rates?.idrPer(Formats.shownCurrency) ?: 1.0)

/** An amount typed in the chosen currency, in whole Rupiah. */
fun convertToIdr(amount: Double): Long = (amount * (Formats.rates?.idrPer(Formats.shownCurrency) ?: 1.0)).roundToLong()

/** "Rp 35.000", "$1.93", "¥12.83"; "-" when empty. */
fun formatMoney(idr: Long?): String {
    if (idr == null) return "-"
    val currency = Formats.shownCurrency
    val amount = convertFromIdr(idr)
    val sign = if (amount < 0) "-" else ""
    return sign + symbol(currency) + number(abs(amount), currency.decimals)
}

/** Short form for tight spots: "Rp 54,5 rb", "Rp 54.5K", "Rp 5.5万", "$3.58", "$1.2K". */
fun formatMoneyCompact(idr: Long?): String {
    if (idr == null) return "-"
    val currency = Formats.shownCurrency
    val amount = convertFromIdr(idr)
    val sign = if (amount < 0) "-" else ""

    val units = when (Formats.language) {
        "id" -> listOf(1e12 to " T", 1e9 to " M", 1e6 to " jt", 1e3 to " rb")
        "zh" -> listOf(1e12 to "万亿", 1e8 to "亿", 1e4 to "万")
        else -> listOf(1e12 to "T", 1e9 to "B", 1e6 to "M", 1e3 to "K")
    }
    // The biggest unit that still shows at least "1" once rounded (999.950 → "1 jt", not "1.000 rb").
    val (size, unit) = units.firstOrNull { abs((amount * 10 / it.first).roundToLong()) >= 10 }
        ?: return formatMoney(idr)
    val tenths = abs((amount * 10 / size).roundToLong())
    val fraction = tenths % 10
    return sign + symbol(currency) + groupThousands(tenths / 10) + (if (fraction != 0L) "$decimalMark$fraction" else "") + unit
}

private fun symbol(currency: Currency) = if (currency == Currency.IDR) "Rp " else currency.symbol

private fun number(value: Double, decimals: Int): String {
    if (decimals == 0) return groupThousands(value.roundToLong())
    val cents = (value * 100).roundToLong()
    return groupThousands(cents / 100) + decimalMark + (cents % 100).toString().padStart(2, '0')
}

/** "850 KB", "12,3 MB" (1 KB = 1024 bytes). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val tenths = (value * 10).roundToLong()
    return if (value >= 100 || tenths % 10 == 0L) "${value.roundToLong()} ${units[unit]}" else "${tenths / 10}$decimalMark${tenths % 10} ${units[unit]}"
}

/** "18 Okt 2026", "18 Oct 2026", "2026年10月18日", or the chosen pattern ("2026-10-18"). */
fun formatDate(date: LocalDate?, style: DateStyle = Formats.dateStyle): String {
    date ?: return "-"
    val pattern = style.pattern ?: if (Formats.language == "zh") "yyyy年M月d日" else "d MMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, Formats.locale))
}

/** "18 Okt", "10月18日", or the chosen pattern without the year ("10-18"). */
fun formatDayMonth(date: LocalDate): String {
    val pattern = Formats.dateStyle.shortPattern ?: if (Formats.language == "zh") "M月d日" else "d MMM"
    return date.format(DateTimeFormatter.ofPattern(pattern, Formats.locale))
}

/** "Minggu, 18 Oktober 2026", "2026年10月18日 星期日", or the weekday and the chosen pattern. */
fun formatDayLong(date: LocalDate): String {
    val locale = Formats.locale
    val weekday = date.format(DateTimeFormatter.ofPattern("EEEE", locale))
    val style = Formats.dateStyle
    return when {
        style.pattern != null -> "$weekday, ${date.format(DateTimeFormatter.ofPattern(style.pattern, locale))}"
        Formats.language == "zh" -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", locale)) + " " + weekday
        else -> "$weekday, ${date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))}"
    }
}

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
