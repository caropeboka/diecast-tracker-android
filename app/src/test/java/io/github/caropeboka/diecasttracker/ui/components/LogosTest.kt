package io.github.caropeboka.diecasttracker.ui.components

import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.price.PriceChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogosTest {
    @Test
    fun `a diecast brand is found however it is written`() {
        assertEquals(R.drawable.logo_hot_wheels, Logos.ofBrand("Hot Wheels"))
        assertEquals(R.drawable.logo_hot_wheels, Logos.ofBrand("hotwheels"))
        assertEquals(R.drawable.logo_hot_wheels, Logos.ofBrand("HOT WHEELS Premium"))
        assertEquals(R.drawable.logo_matchbox, Logos.ofBrand("Matchbox"))
        assertEquals(R.drawable.logo_tomica, Logos.ofBrand("Tomica Premium"))
        assertEquals(R.drawable.logo_mini_gt, Logos.ofBrand("MINI-GT"))
        assertEquals(R.drawable.logo_kaido_house, Logos.ofBrand("Kaido House"))
        assertEquals(R.drawable.logo_bburago, Logos.ofBrand("Burago"))
        assertEquals(R.drawable.logo_maisto, Logos.ofBrand(" maisto "))
        assertEquals(R.drawable.logo_roll_speed, Logos.ofBrand("Roll Speed"))
        assertEquals(R.drawable.logo_takara_tomy, Logos.ofBrand("TAKARA TOMY"))
        // Lines without a logo get their initials where a tile is needed.
        assertNull(Logos.ofBrand("Moto Cross"))
        assertNull(Logos.ofBrand(" "))
        assertNull(Logos.ofBrand(null))
    }

    @Test
    fun `a car maker is found however it is written, whatever follows its name`() {
        assertEquals(R.drawable.make_mercedes_benz, Logos.ofMake("Mercedes-Benz"))
        assertEquals(R.drawable.make_mercedes_benz, Logos.ofMake("mercedes"))
        assertEquals(R.drawable.make_mercedes_amg, Logos.ofMake("Mercedes-AMG"))
        assertEquals(R.drawable.make_citroen, Logos.ofMake("Citroën"))
        assertEquals(R.drawable.make_skoda, Logos.ofMake("Škoda"))
        assertEquals(R.drawable.make_volkswagen, Logos.ofMake("VW"))
        assertEquals(R.drawable.make_chevrolet, Logos.ofMake("Chevy"))
        assertEquals(R.drawable.make_red_bull, Logos.ofMake("Red Bull Racing"))
        assertEquals(R.drawable.make_land_rover, Logos.ofMake("Land Rover Defender"))
        assertEquals(R.drawable.make_nissan, Logos.ofMake("Nissan Skyline GT-R"))
        assertEquals(R.drawable.make_ram, Logos.ofMake("RAM"))
        assertEquals(R.drawable.make_indycar, Logos.ofMake("IndyCar"))
        assertEquals(R.drawable.make_hot_wheels, Logos.ofMake("Hot Wheels Original"))
        // A word is never cut short, and made-up cars have none.
        assertEquals(R.drawable.make_man, Logos.ofMake("MAN"))
        assertNull(Logos.ofMake("Mansory"))
        assertNull(Logos.ofMake("Batmobile"))
        assertNull(Logos.ofMake("unknown"))
        assertNull(Logos.ofMake(" "))
        assertNull(Logos.ofMake(null))
    }

    @Test
    fun `every car maker's logo can be found by its own name`() {
        val tiles = R.drawable::class.java.fields.filter { it.name.startsWith("make_") }
        assertTrue(tiles.size > 100)
        tiles.forEach { field -> assertEquals(field.name, field.getInt(null), Logos.ofMake(field.name.removePrefix("make_").replace('_', ' '))) }
    }

    @Test
    fun `only the Treasure Hunts have a symbol`() {
        assertEquals(R.drawable.logo_treasure_hunt, Logos.of(Rarity.TreasureHunt))
        assertEquals(R.drawable.logo_super_treasure_hunt, Logos.of(Rarity.SuperTreasureHunt))
        listOf(Rarity.Regular, Rarity.Chase, Rarity.Limited, Rarity.Exclusive, null).forEach { assertNull(Logos.of(it)) }
    }

    @Test
    fun `every AI but the own server has a logo, found by label or title`() {
        AiProvider.entries.forEach { provider ->
            if (provider == AiProvider.Custom) assertNull(Logos.of(provider)) else assertNotNull(provider.name, Logos.of(provider))
        }
        assertEquals(R.drawable.logo_groq, Logos.ofProvider("Groq"))
        assertEquals(R.drawable.logo_cloudflare, Logos.ofProvider("Cloudflare Workers AI"))
        assertEquals(R.drawable.logo_nvidia, Logos.ofProvider("NVIDIA"))
        // A custom server's own name, or the server label itself, has none.
        assertNull(Logos.ofProvider("My server"))
        assertNull(Logos.ofProvider("Server"))
        assertNull(Logos.ofProvider(null))
    }

    @Test
    fun `every marketplace the AI can name has a logo`() {
        PriceChecker.MARKETPLACE_KEYS.filter { it != "other" }.forEach { key -> assertNotNull(key, Logos.ofMarket(key)) }
        assertEquals(R.drawable.logo_google_shopping, Logos.ofMarket("google"))
        assertEquals(R.drawable.logo_shopee, Logos.ofMarket("Shopee"))
        // Before 0.13.0: written out instead.
        assertNull(Logos.ofMarket("bukalapak"))
        assertNull(Logos.ofMarket("other"))
    }

    @Test
    fun `initials stand in for a brand without a logo`() {
        assertEquals("MC", initials("Moto Cross"))
        assertEquals("RS", initials("Roll Speed"))
        assertEquals("SI", initials("Siku"))
        assertEquals("GJ", initials("Greenlight / Johnny Lightning"))
        assertEquals("?", initials("—"))
    }
}
