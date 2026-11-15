package io.github.caropeboka.diecasttracker.data.receipt

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.MAX_MONEY
import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.ai.AiVision
import io.github.caropeboka.diecasttracker.data.db.AppDatabase
import io.github.caropeboka.diecasttracker.data.db.ReceiptEntity
import io.github.caropeboka.diecasttracker.data.db.ReceiptUse
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.places.PlaceResult
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.util.ExchangeRates
import io.github.caropeboka.diecasttracker.util.array
import io.github.caropeboka.diecasttracker.util.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToLong

// Receipts: a photo of what was paid, read by the AI to fill in the purchase price (and the date and store, with the
// store's point on the map when its address is printed, when the form has none), then kept as proof. One receipt can hold several diecasts, each taking one of its lines.

/** One product on a receipt; [price] is what one of them cost in Rupiah, after the discounts printed for the line. */
@Serializable
data class ReceiptLine(
    /** As printed, abbreviations and all ("HW BASIC CAR ASST"). */
    val name: String,
    val quantity: Int = 1,
    /** Null when it couldn't be read, or converted from another currency. */
    val price: Long? = null,
    /** One's price as printed, when the receipt isn't in Rupiah. */
    val original: Double? = null,
    /** A diecast or another toy car; false for a bag, a drink, a fee. */
    val diecast: Boolean = true,
)

class ReceiptException(message: String) : Exception(message)

/** The receipts' photos: JPEGs in `files/receipts`, apart from the diecasts' photos. */
class ReceiptStore(root: File) {
    private val dir = File(root, "receipts")

    fun file(name: String) = File(dir, name)

    /** Keeps a photo prepared for the AI; returns its name. */
    fun save(bytes: ByteArray): String {
        val name = "${UUID.randomUUID()}.jpg"
        File(dir.apply { mkdirs() }, name).writeBytes(bytes)
        return name
    }

    fun delete(name: String?) {
        if (name != null && NAME.matches(name)) file(name).delete()
    }

    /** Removes photos no receipt holds once they are older than [before] (a younger one may be being read). */
    fun housekeeping(used: Set<String>, before: Long) {
        dir.listFiles()?.filter { it.name !in used && it.lastModified() < before }?.forEach(File::delete)
    }

    companion object {
        val NAME = Regex("[0-9a-f-]{36}\\.jpg")
    }
}

/**
 * What the AI read from a receipt, not kept yet. [matches] pairs the diecasts it was told about (by their index) with
 * the lines they most likely are (by index); a line of several can take as many.
 */
data class ReceiptReading(
    val store: String?,
    /** The branch's address as printed; null for an online order or when only a head office's is printed. */
    val address: String? = null,
    /** What the AI would search OpenStreetMap for to find [address], most precise first. */
    val mapQueries: List<String> = emptyList(),
    /** The chain's name in its area ("Indomaret, Pangkalan, Teluknaga"), for when the map lacks the street. */
    val shopQuery: String? = null,
    /** Where [address] is, once [ReceiptReader.locate] found it. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val boughtOn: LocalDate?,
    val total: Long?,
    val currency: String?,
    val lines: List<ReceiptLine>,
    val matches: Map<Int, Int>,
    val note: String?,
    val readBy: String?,
)

/** Reads a receipt's photo with the AI; prices in another currency are converted at [rates]. */
class ReceiptReader(
    private val vision: AiVision,
    private val texts: Texts,
    private val rates: suspend () -> ExchangeRates? = { null },
    private val today: () -> LocalDate = LocalDate::now,
    /** Finds a shop from its name, the searches of its address and the shop's own (`PlaceLookup.locate`). */
    private val places: suspend (name: String?, queries: List<String>, shop: String?) -> PlaceResult? = { _, _, _ -> null },
) {
    val enabled: Flow<Boolean> = vision.enabled

    /** [reading] with the point of its store's address, when the map finds it; it stays as it was otherwise. */
    suspend fun locate(reading: ReceiptReading): ReceiptReading {
        if (reading.mapQueries.isEmpty() && reading.shopQuery == null) return reading
        val place = withTimeoutOrNull(LOCATE_TIMEOUT_MS) { places(reading.store, reading.mapQueries, reading.shopQuery) } ?: return reading
        return reading.copy(latitude = place.lat, longitude = place.lng)
    }

    /**
     * [image]: the receipt as JPEG. [items]: the diecasts it may hold, one line each ("Name: …, brand: …"), to be
     * paired with its lines. Not a receipt, or nothing on it legible: a [ReceiptException].
     */
    suspend fun read(image: ByteArray, items: List<String>, onEvent: (AiEvent) -> Unit = {}): ReceiptReading {
        if (!vision.isEnabled()) throw AiException(texts(R.string.receipt_needs_ai))
        val system = SYSTEM_PROMPT.replace("{language}", texts(R.string.ai_answer_language))
        val answer = vision.extract(listOf(image), system, instruction(items), SCHEMA, onEvent)
        return reading(answer.data, items.size, answer.by.takeIf { answer.provider != AiProvider.Gemini })
    }

    private suspend fun reading(data: JsonObject, itemCount: Int, readBy: String?): ReceiptReading {
        if ((data["is_receipt"] as? JsonPrimitive)?.booleanOrNull == false) throw ReceiptException(texts(R.string.receipt_not_receipt))

        val currency = data.string("currency")?.trim()?.uppercase()?.takeIf { CURRENCY.matches(it) } ?: IDR
        val rate = if (currency == IDR) 1.0 else rates()?.idrPer(currency)

        // Rupiah has no decimals: "33.900" written as text is 33900 ("33.900,00" too); elsewhere "1,100" is 1100.
        fun amount(json: JsonObject, key: String): Double? = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { value ->
            when {
                !value.isString -> value.doubleOrNull
                currency == IDR -> value.content.trim().replace(CENTS, "").filter(Char::isDigit).toDoubleOrNull()
                else -> value.content.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
            }
        }?.takeIf { it > 0 }

        fun idr(value: Double?): Long? = value?.let { amount -> rate?.let { (amount * it).roundToLong() } }?.takeIf { it in 1..MAX_MONEY }

        val lines = data.array("lines").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { json ->
            val name = json.string("name")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val unit = amount(json, "unit_price")
            ReceiptLine(
                name = name.take(MAX_NAME),
                quantity = (json["quantity"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }?.coerceIn(1, MAX_QUANTITY) ?: 1,
                price = idr(unit),
                original = unit.takeIf { currency != IDR },
                diecast = (json["is_diecast"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        if (lines.isEmpty()) throw ReceiptException(texts(R.string.receipt_no_lines))

        // Each diecast takes one line, and a line no more diecasts than it holds.
        val taken = mutableMapOf<Int, Int>()
        val matches = mutableMapOf<Int, Int>()
        data.array("matches").orEmpty().mapNotNull { it as? JsonObject }.forEach { json ->
            val item = (json["item"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val line = (json["line"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            if (item !in 0 until itemCount || line !in lines.indices || item in matches) return@forEach
            if (taken.getOrDefault(line, 0) >= lines[line].quantity) return@forEach
            taken[line] = taken.getOrDefault(line, 0) + 1
            matches[item] = line
        }

        val date = data.string("date")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it <= today() && it.year >= MIN_YEAR }
        val notes = listOfNotNull(
            data.string("note")?.trim()?.ifEmpty { null },
            texts(R.string.receipt_no_rate, currency).takeIf { rate == null },
        )

        val address = data.string("address")?.trim()?.ifEmpty { null }?.take(MAX_ADDRESS)
        return ReceiptReading(
            store = data.string("store")?.trim()?.ifEmpty { null }?.take(MAX_STORE),
            address = address,
            mapQueries = if (address == null) emptyList() else data.array("map_queries").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.ifEmpty { null }?.take(MAX_STORE) }
                .distinct()
                .take(MAX_QUERIES),
            shopQuery = data.string("shop_query")?.trim()?.ifEmpty { null }?.take(MAX_STORE)?.takeIf { address != null },
            boughtOn = date,
            total = idr(amount(data, "total")),
            currency = currency.takeIf { it != IDR },
            lines = lines,
            matches = matches,
            note = notes.joinToString("\n").ifEmpty { null },
            readBy = readBy,
        )
    }

    private fun instruction(items: List<String>): String = buildString {
        append("Read this receipt and fill in the fields.")
        if (items.isNotEmpty()) {
            append("\n\nThe collector bought these diecasts, numbered from 0. Match each one with the line that is most likely it:\n")
            items.forEachIndexed { i, item -> append("$i. $item\n") }
        }
    }

    private companion object {
        const val IDR = "IDR"

        /** A decimal part after the last separator: "33.900,00", "33,900.00". */
        val CENTS = Regex("[.,]\\d{1,2}$")
        val CURRENCY = Regex("^[A-Z]{3}$")
        const val MIN_YEAR = 1990
        const val MAX_NAME = 200
        const val MAX_STORE = 255
        const val MAX_ADDRESS = 500
        const val MAX_QUERIES = 3
        const val MAX_QUANTITY = 999

        /** Nominatim answers a search in a second or two; four of them, a second apart, with room to spare. */
        const val LOCATE_TIMEOUT_MS = 25_000L

        val SYSTEM_PROMPT = """
            You read shop receipts for a diecast car collector's tracker, to fill in what each diecast cost.

            The photo may be a printed receipt (Indomaret, Alfamart, a toy shop, a hobby shop, a supermarket), a handwritten
            nota, an invoice, or a screenshot of an online order (Tokopedia, Shopee, Lazada, TikTok Shop, eBay). Read every
            product line in the order printed.

            - is_receipt: false when the photo is not a receipt, invoice or order at all; the other fields may then be empty.
            - store: the shop as printed, with its branch if shown (e.g. "Indomaret Cibinong Raya", "Toys Kingdom Grand
              Indonesia"); for an online order the shop's name and the marketplace (e.g. "Diecast Corner · Tokopedia").
              null if not shown.
            - address: the street address of the branch where it was bought, as printed (e.g. "Jl. Tegar Beriman No. 5,
              Tengah, Cibinong, Kab. Bogor"). null for an online order, when none is printed, or when the only address is
              the company's head office or tax address (e.g. Indomaret's "PT Indomarco Prima", Alfamart's "PT Sumber
              Alfaria Trijaya" with an address in another city than the branch).
            - map_queries: when there is an address, up to 3 searches that would find the branch on OpenStreetMap, the
              most precise first: the mall or building it is in with its city ("Grand Indonesia, Jakarta Pusat"), then
              the street with its kelurahan or desa and its kecamatan or city ("Jalan Tegar Beriman, Tengah, Cibinong").
              Write abbreviations out ("Jl." → "Jalan", "Kab." → "Kabupaten") and leave out the shop's own name, house
              and block numbers, RT/RW, floors, postal codes and words like "Kel." or "Kec.". An empty list without an
              address.
            - shop_query: when there is an address, one more search: the chain's or shop's name alone with the kelurahan
              or desa, the kecamatan and the kabupaten or city ("Indomaret, Pangkalan, Teluknaga, Kabupaten Tangerang"),
              for a street OpenStreetMap doesn't know. null without an address.
            - date: the purchase date as YYYY-MM-DD. Indonesian receipts print the day first ("12.11.26", "12/11/2026").
              null if not shown.
            - currency: the ISO code of the prices: IDR for "Rp" or an Indonesian receipt, JPY for "円" or "¥" in Japan, USD
              for "$", and so on.
            - total: what was paid in all (after discounts; not the cash handed over or the change).
            - lines: every product line:
              - name: as printed, abbreviations kept (e.g. "HW BASIC CAR ASST", "MATCHBOX 5PK", "TOMICA NO.23 GT-R").
              - quantity: how many, 1 if not printed.
              - unit_price: what ONE cost after the discounts printed for that line. Lines such as "DISC", "HEMAT",
                "POTONGAN" or a crossed-out price belong to the product above them: subtract them, spread over its
                quantity. null if unreadable.
              - is_diecast: true for diecast and toy cars (Hot Wheels or "HW", Matchbox or "MBX", Tomica, Mini GT,
                Majorette, Maisto, Bburago, Greenlight, Tarmac Works, Inno64…), false for anything else (bags, food and
                drinks, batteries, admin or shipping fees, services).
            - matches: for the numbered diecasts in the instruction, the line each one is: {"item": its number, "line": the
              line's index in lines, from 0}. A line of quantity N can match up to N diecasts. Leave a diecast out when no
              line fits; an empty list when no diecasts are listed.
            - note: one short sentence in {language} when something is unclear (blurry, cut off, a discount or voucher for
              the whole receipt that isn't in the lines), else null.

            Prices and totals are plain numbers in the receipt's currency: no currency signs, no thousands separators
            ("33.900" → 33900, "Rp 35,000" → 35000). A wrong number is worse than null.
        """.trimIndent()

        val SCHEMA: JsonObject = run {
            fun type(name: String) = buildJsonObject { put("type", name) }
            fun nullable(name: String) = buildJsonObject {
                putJsonArray("anyOf") {
                    addJsonObject { put("type", name) }
                    addJsonObject { put("type", "null") }
                }
            }
            fun objectOf(properties: Map<String, JsonObject>) = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                putJsonArray("required") { properties.keys.forEach { add(it) } }
                put("additionalProperties", false)
            }
            fun arrayOf(items: JsonObject) = buildJsonObject {
                put("type", "array")
                put("items", items)
            }

            val line = objectOf(
                linkedMapOf(
                    "name" to type("string"),
                    "quantity" to type("integer"),
                    "unit_price" to nullable("number"),
                    "is_diecast" to type("boolean"),
                ),
            )
            val match = objectOf(linkedMapOf("item" to type("integer"), "line" to type("integer")))
            objectOf(
                linkedMapOf(
                    "is_receipt" to type("boolean"),
                    "store" to nullable("string"),
                    "address" to nullable("string"),
                    "map_queries" to arrayOf(type("string")),
                    "shop_query" to nullable("string"),
                    "date" to nullable("string"),
                    "currency" to type("string"),
                    "total" to nullable("number"),
                    "lines" to arrayOf(line),
                    "matches" to arrayOf(match),
                    "note" to nullable("string"),
                ),
            )
        }
    }
}

/** The receipts kept, the diecasts bought with them, and the clean-up of ones nothing points at. */
class ReceiptRepository(
    private val db: AppDatabase,
    private val store: ReceiptStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.receipts()

    fun file(receipt: ReceiptEntity): File = store.file(receipt.photo)

    fun observe(id: Long): Flow<ReceiptEntity?> = dao.observe(id)

    suspend fun get(id: Long): ReceiptEntity? = dao.get(id)

    /** The diecasts bought with it, and their lines. */
    fun observeUses(id: Long): Flow<List<ReceiptUse>> = dao.observeUses(id)

    /** Read in the last two weeks, newest first: the next diecast of the same purchase takes another line. */
    fun observeRecent(): Flow<List<ReceiptEntity>> = dao.observeRecent(clock() - RECENT_MS, RECENT_LIMIT)

    /** Keeps [photo] (already in the [ReceiptStore]) with what was read from it. */
    suspend fun add(photo: String, reading: ReceiptReading): ReceiptEntity {
        val receipt = ReceiptEntity(
            photo = photo,
            store = reading.store,
            address = reading.address,
            latitude = reading.latitude,
            longitude = reading.longitude,
            boughtOn = reading.boughtOn,
            total = reading.total,
            currency = reading.currency,
            lines = reading.lines,
            readBy = reading.readBy,
            note = reading.note,
            createdAt = clock(),
        )
        return receipt.copy(id = dao.insert(receipt))
    }

    /**
     * The store's address and point from [reading], a receipt read again: one read before addresses were, or whose
     * store the map didn't find then. What the new reading lacks stays as it was.
     */
    suspend fun setPlace(receipt: ReceiptEntity, reading: ReceiptReading): ReceiptEntity {
        val found = reading.latitude != null && reading.longitude != null
        val next = receipt.copy(
            address = reading.address ?: receipt.address,
            latitude = if (found) reading.latitude else receipt.latitude,
            longitude = if (found) reading.longitude else receipt.longitude,
        )
        dao.setPlace(receipt.id, next.address, next.latitude, next.longitude)
        return next
    }

    /** At start-up: receipts nothing was bought with for a day (an abandoned form's), and photos no receipt holds. */
    suspend fun housekeeping() {
        val before = clock() - DAY_MS
        dao.unusedBefore(before).forEach { receipt ->
            dao.delete(receipt.id)
            store.delete(receipt.photo)
        }
        val used = dao.allPhotos().toSet()
        withContext(Dispatchers.IO) { store.housekeeping(used, before) }
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val RECENT_MS = 14 * DAY_MS
        const val RECENT_LIMIT = 3
    }
}

/** A diecast described for the AI to find it on a receipt. */
fun receiptItem(name: String, brand: String?, series: String?, number: String?): String =
    listOfNotNull(
        name.trim().ifEmpty { null }?.let { "name: $it" },
        brand?.trim()?.ifEmpty { null }?.let { "brand: $it" },
        series?.trim()?.ifEmpty { null }?.let { "series: $it" },
        number?.trim()?.ifEmpty { null }?.let { "number: $it" },
    ).joinToString(", ").ifEmpty { "a diecast" }

/** What a receipt gives "Bought at": its store (its address when no name is printed), with the store's point if found. */
val ReceiptEntity.place: PlaceValue?
    get() = (store ?: address)?.let { PlaceValue(it, latitude, longitude) }
