package io.github.caropeboka.diecasttracker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.price.Marketplace
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import java.text.Normalizer
import java.util.Locale

/**
 * Logos of the shops, AI services, diecast brands and car makers the app names, only to show which one is meant; they
 * belong to their owners (README: "Logos"). Services are square tiles (`logo_<name>`, 96 px, or vectors from Simple
 * Icons); diecast brands are 2:1 tiles of their wordmark; car makers square tiles of their emblem (`make_<name>`);
 * Treasure Hunts the circle-flame their cards carry.
 * The big Hot Wheels logo on the garage's board is another file, kept out of git.
 */
object Logos {
    @DrawableRes
    val serpApi = R.drawable.logo_serpapi

    @DrawableRes
    val googleLens = R.drawable.logo_google_lens

    @DrawableRes
    val googleShopping = R.drawable.logo_google_shopping

    @DrawableRes
    fun of(market: Marketplace): Int = when (market) {
        Marketplace.Tokopedia -> R.drawable.logo_tokopedia
        Marketplace.Shopee -> R.drawable.logo_shopee
        Marketplace.Lazada -> R.drawable.logo_lazada
        Marketplace.GoogleShopping -> R.drawable.logo_google_shopping
        Marketplace.Facebook -> R.drawable.logo_facebook
        Marketplace.Olx -> R.drawable.logo_olx
        Marketplace.Carousell -> R.drawable.logo_carousell
        Marketplace.EbaySold -> R.drawable.logo_ebay
        Marketplace.YahooAuctionsSold -> R.drawable.logo_yahoo_auctions
        Marketplace.MercariSold -> R.drawable.logo_mercari
    }

    /** A listing's marketplace as the AI names it ("tokopedia", "google", "yahoo_auctions"…); null without a logo. */
    @DrawableRes
    fun ofMarket(key: String?): Int? = when (key?.lowercase(Locale.ROOT)) {
        "tokopedia" -> R.drawable.logo_tokopedia
        "shopee" -> R.drawable.logo_shopee
        "lazada" -> R.drawable.logo_lazada
        "google" -> R.drawable.logo_google_shopping
        "facebook" -> R.drawable.logo_facebook
        "olx" -> R.drawable.logo_olx
        "carousell" -> R.drawable.logo_carousell
        "ebay" -> R.drawable.logo_ebay
        "yahoo_auctions" -> R.drawable.logo_yahoo_auctions
        "mercari" -> R.drawable.logo_mercari
        else -> null
    }

    /** Null for the collector's own OpenAI-compatible server. */
    @DrawableRes
    fun of(provider: AiProvider): Int? = when (provider) {
        AiProvider.Gemini -> R.drawable.logo_gemini
        AiProvider.Groq -> R.drawable.logo_groq
        AiProvider.Mistral -> R.drawable.logo_mistral
        AiProvider.OpenRouter -> R.drawable.logo_openrouter
        AiProvider.Cloudflare -> R.drawable.logo_cloudflare
        AiProvider.Nvidia -> R.drawable.logo_nvidia
        AiProvider.Zai -> R.drawable.logo_zai
        AiProvider.Ovh -> R.drawable.logo_ovh
        AiProvider.Custom -> null
    }

    /** An AI named as the log and "read by" name it: a provider's label or title (a custom server's name has none). */
    @DrawableRes
    fun ofProvider(name: String?): Int? =
        AiProvider.entries.firstOrNull { it != AiProvider.Custom && (it.label == name || it.title == name) }?.let(::of)

    /** A diecast brand's wordmark however it is written: "Hot Wheels", "hotwheels", "Tomica Premium", "Kaido House"… */
    @DrawableRes
    fun ofBrand(brand: String?): Int? {
        val key = brand?.lowercase(Locale.ROOT)?.filter(Char::isLetterOrDigit)?.takeIf { it.isNotEmpty() } ?: return null
        return BRANDS.firstOrNull { (prefix, _) -> key.startsWith(prefix) }?.second
    }

    private val BRANDS = listOf(
        "hotwheels" to R.drawable.logo_hot_wheels,
        "matchbox" to R.drawable.logo_matchbox,
        "tomica" to R.drawable.logo_tomica,
        "minigt" to R.drawable.logo_mini_gt,
        "kaidohouse" to R.drawable.logo_kaido_house,
        "bburago" to R.drawable.logo_bburago,
        "burago" to R.drawable.logo_bburago,
        "maisto" to R.drawable.logo_maisto,
        "rollspeed" to R.drawable.logo_roll_speed,
        "takaratomy" to R.drawable.logo_takara_tomy,
    )

    /**
     * A car maker's emblem however it is written: "Mercedes-Benz", "mercedes", "Citroën", "VW", "Red Bull Racing",
     * "Land Rover Defender". The first three, two or one words are tried as one, so a model after the name doesn't
     * matter, but a word is never cut short ("Man" is MAN, "Mansory" nothing).
     */
    @DrawableRes
    fun ofMake(make: String?): Int? {
        val plain = make?.let { Normalizer.normalize(it.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "") }
        val words = plain?.split(NOT_LETTERS)?.filter(String::isNotEmpty).orEmpty()
        return (minOf(3, words.size) downTo 1).firstNotNullOfOrNull { n -> MAKES[words.take(n).joinToString("")] }
    }

    /** The circle-flame on a Treasure Hunt's card: silver, gold for a Super. The other rarities have none. */
    @DrawableRes
    fun of(rarity: Rarity?): Int? = when (rarity) {
        Rarity.TreasureHunt -> R.drawable.logo_treasure_hunt
        Rarity.SuperTreasureHunt -> R.drawable.logo_super_treasure_hunt
        else -> null
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_LETTERS = Regex("[^a-z0-9]+")

    private val MAKES = mapOf(
        "abarth" to R.drawable.make_abarth, "acura" to R.drawable.make_acura, "alfa" to R.drawable.make_alfa_romeo,
        "alfaromeo" to R.drawable.make_alfa_romeo, "alpine" to R.drawable.make_alpine, "amc" to R.drawable.make_amc,
        "americanmotors" to R.drawable.make_amc, "amg" to R.drawable.make_mercedes_amg,
        "ariel" to R.drawable.make_ariel, "astonmartin" to R.drawable.make_aston_martin,
        "audi" to R.drawable.make_audi, "bentley" to R.drawable.make_bentley, "benz" to R.drawable.make_mercedes_benz,
        "bmw" to R.drawable.make_bmw, "bugatti" to R.drawable.make_bugatti, "buick" to R.drawable.make_buick,
        "byd" to R.drawable.make_byd, "cadillac" to R.drawable.make_cadillac, "caterham" to R.drawable.make_caterham,
        "chery" to R.drawable.make_chery, "chevrolet" to R.drawable.make_chevrolet,
        "chevy" to R.drawable.make_chevrolet, "chrysler" to R.drawable.make_chrysler,
        "citroen" to R.drawable.make_citroen, "cupra" to R.drawable.make_cupra,
        "daihatsu" to R.drawable.make_daihatsu, "datsun" to R.drawable.make_datsun, "delorean" to R.drawable.make_dmc,
        "dmc" to R.drawable.make_dmc, "dodge" to R.drawable.make_dodge, "ds" to R.drawable.make_ds,
        "dsautomobiles" to R.drawable.make_ds, "ducati" to R.drawable.make_ducati,
        "ferrari" to R.drawable.make_ferrari, "fiat" to R.drawable.make_fiat, "fisker" to R.drawable.make_fisker,
        "ford" to R.drawable.make_ford, "geely" to R.drawable.make_geely, "genesis" to R.drawable.make_genesis,
        "gmc" to R.drawable.make_gmc, "haval" to R.drawable.make_haval, "hennessey" to R.drawable.make_hennessey,
        "hino" to R.drawable.make_hino, "holden" to R.drawable.make_holden, "honda" to R.drawable.make_honda,
        "hongqi" to R.drawable.make_hongqi, "hotwheels" to R.drawable.make_hot_wheels,
        "hudson" to R.drawable.make_hudson, "hummer" to R.drawable.make_hummer, "hyundai" to R.drawable.make_hyundai,
        "indycar" to R.drawable.make_indycar, "infiniti" to R.drawable.make_infiniti,
        "isuzu" to R.drawable.make_isuzu, "iveco" to R.drawable.make_iveco, "jaguar" to R.drawable.make_jaguar,
        "jeep" to R.drawable.make_jeep, "kawasaki" to R.drawable.make_kawasaki,
        "kenworth" to R.drawable.make_kenworth, "kia" to R.drawable.make_kia,
        "koenigsegg" to R.drawable.make_koenigsegg, "lada" to R.drawable.make_lada,
        "lamborghini" to R.drawable.make_lamborghini, "lancia" to R.drawable.make_lancia,
        "landrover" to R.drawable.make_land_rover, "lexus" to R.drawable.make_lexus,
        "lincoln" to R.drawable.make_lincoln, "lotus" to R.drawable.make_lotus, "lucid" to R.drawable.make_lucid,
        "mack" to R.drawable.make_mack, "mahindra" to R.drawable.make_mahindra, "man" to R.drawable.make_man,
        "maserati" to R.drawable.make_maserati, "mazda" to R.drawable.make_mazda,
        "mclaren" to R.drawable.make_mclaren, "mercedes" to R.drawable.make_mercedes_benz,
        "mercedesamg" to R.drawable.make_mercedes_amg, "mercedesbenz" to R.drawable.make_mercedes_benz,
        "mercury" to R.drawable.make_mercury, "mg" to R.drawable.make_mg, "mini" to R.drawable.make_mini,
        "mitsubishi" to R.drawable.make_mitsubishi, "morgan" to R.drawable.make_morgan, "nio" to R.drawable.make_nio,
        "nissan" to R.drawable.make_nissan, "noble" to R.drawable.make_noble,
        "oldsmobile" to R.drawable.make_oldsmobile, "opel" to R.drawable.make_opel,
        "packard" to R.drawable.make_packard, "pagani" to R.drawable.make_pagani,
        "perodua" to R.drawable.make_perodua, "peterbilt" to R.drawable.make_peterbilt,
        "peugeot" to R.drawable.make_peugeot, "plymouth" to R.drawable.make_plymouth,
        "polestar" to R.drawable.make_polestar, "pontiac" to R.drawable.make_pontiac,
        "porsche" to R.drawable.make_porsche, "proton" to R.drawable.make_proton, "ram" to R.drawable.make_ram,
        "rangerover" to R.drawable.make_land_rover, "redbull" to R.drawable.make_red_bull,
        "redbullracing" to R.drawable.make_red_bull, "renault" to R.drawable.make_renault,
        "rimac" to R.drawable.make_rimac, "rivian" to R.drawable.make_rivian,
        "rollsroyce" to R.drawable.make_rolls_royce, "ruf" to R.drawable.make_ruf, "saab" to R.drawable.make_saab,
        "saleen" to R.drawable.make_saleen, "scania" to R.drawable.make_scania, "scion" to R.drawable.make_scion,
        "seat" to R.drawable.make_seat, "skoda" to R.drawable.make_skoda, "smart" to R.drawable.make_smart,
        "spyker" to R.drawable.make_spyker, "studebaker" to R.drawable.make_studebaker,
        "subaru" to R.drawable.make_subaru, "suzuki" to R.drawable.make_suzuki, "tata" to R.drawable.make_tata,
        "tesla" to R.drawable.make_tesla, "toyota" to R.drawable.make_toyota, "tvr" to R.drawable.make_tvr,
        "vauxhall" to R.drawable.make_vauxhall, "vector" to R.drawable.make_vector,
        "volkswagen" to R.drawable.make_volkswagen, "volvo" to R.drawable.make_volvo,
        "vw" to R.drawable.make_volkswagen, "wuling" to R.drawable.make_wuling, "xpeng" to R.drawable.make_xpeng,
        "yamaha" to R.drawable.make_yamaha, "zeekr" to R.drawable.make_zeekr, "zenvo" to R.drawable.make_zenvo,
    )
}

/** A service's square logo, its corners rounded like an app icon. Next to its name, so it says nothing itself. */
@Composable
fun LogoTile(@DrawableRes logo: Int, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Image(
        painterResource(logo),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.22f)),
    )
}

/** A service's logo and a label inside a button, like [IconLabel]. */
@Composable
fun LogoLabel(@DrawableRes logo: Int, text: String) {
    LogoTile(logo, ButtonDefaults.IconSize)
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text, maxLines = 1)
}

/**
 * A diecast brand's wordmark, twice as wide as [height]. Without a logo: its initials in a plain tile when
 * [fallback], else nothing (where the name is written next to it anyway).
 */
@Composable
fun BrandLogo(brand: String?, height: Dp, modifier: Modifier = Modifier, fallback: Boolean = true) {
    val logo = Logos.ofBrand(brand)
    when {
        logo != null -> Image(
            painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.height(height).aspectRatio(2f).clip(RoundedCornerShape(height * 0.2f)),
        )
        fallback && !brand.isNullOrBlank() -> InitialsTile(brand, height, aspect = 2f, corner = 0.2f, modifier)
    }
}

/** A car maker's emblem as a square tile. Without one: its initials when [fallback], else nothing. */
@Composable
fun MakeLogo(make: String?, size: Dp, modifier: Modifier = Modifier, fallback: Boolean = true) {
    val logo = Logos.ofMake(make)
    when {
        logo != null -> LogoTile(logo, size, modifier)
        fallback && !make.isNullOrBlank() -> InitialsTile(make, size, aspect = 1f, corner = 0.22f, modifier)
    }
}

/** A name's initials in a plain tile, where a logo would be. */
@Composable
private fun InitialsTile(name: String, height: Dp, aspect: Float, corner: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(height * corner)
    Box(
        modifier
            .height(height)
            .aspectRatio(aspect)
            .clip(shape)
            .background(Garage.Muted)
            .border(1.dp, Garage.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        val fontSize = with(LocalDensity.current) { (height * 0.42f).toSp() }
        Text(initials(name), color = Garage.MutedForeground, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** "Moto Cross" → "MC", "Roll Speed" → "RS", "Siku" → "SI". */
internal fun initials(name: String): String {
    val words = name.split(Regex("[\\s\\p{Z}\\-_/.]+")).filter { word -> word.any(Char::isLetterOrDigit) }
    return when {
        words.size >= 2 -> words.take(2).joinToString("") { it.first(Char::isLetterOrDigit).uppercase() }
        words.size == 1 -> words[0].filter(Char::isLetterOrDigit).take(2).uppercase()
        else -> "?"
    }
}
