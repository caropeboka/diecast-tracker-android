package io.github.caropeboka.diecasttracker.data.tidy

import io.github.caropeboka.diecasttracker.data.Rarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TidyTest {
    private var nextId = 1L

    private fun row(
        name: String = "Car ${nextId}",
        brand: String? = null,
        make: String? = null,
        series: String? = null,
        scale: String? = null,
        number: String? = null,
        color: String? = null,
        year: Int? = null,
        rarity: Rarity = Rarity.Regular,
    ) = TidyRow(nextId++, name, brand, make, series, scale, number, color, year, rarity)

    private fun List<TidyRow>.groups(field: TidyField, ignored: Set<String> = emptySet()) =
        Tidy.report(this, ignored).spellings.filter { it.field == field }

    @Test
    fun `spellings that differ only in case, spaces or dashes are one, the most used first`() {
        val rows = List(3) { row(brand = "Hot Wheels") } + row(brand = "HOT WHEELS") + row(brand = "Hot-Wheels") + row(brand = "Matchbox")

        val group = rows.groups(TidyField.Brand).single()

        assertTrue(group.sure)
        // On a tie, written in both cases first.
        assertEquals(listOf("Hot Wheels", "Hot-Wheels", "HOT WHEELS"), group.spellings.map { it.text })
        assertEquals(listOf(3, 1, 1), group.spellings.map { it.count })
        assertEquals("Hot Wheels", group.suggested)
        assertEquals(5, group.diecasts)
    }

    @Test
    fun `a well-known brand is offered first, even when nobody writes it so`() {
        val rows = List(2) { row(brand = "MINI GT") } + row(brand = "Mini-GT")

        val group = rows.groups(TidyField.Brand).single()

        assertEquals(listOf("Mini GT", "MINI GT", "Mini-GT"), group.options)
        // Alone and written another way, it is offered too; written the usual way, it isn't.
        assertEquals(listOf("Hot Wheels", "hot wheels"), listOf(row(brand = "hot wheels")).groups(TidyField.Brand).single().options)
        assertEquals(emptyList<SpellingGroup>(), listOf(row(brand = "Tomica")).groups(TidyField.Brand))
    }

    @Test
    fun `accents don't make another maker, and on a tie the accented one is suggested`() {
        val rows = listOf(row(make = "Citroen"), row(make = "Citroën"))

        val group = rows.groups(TidyField.Make).single()

        assertTrue(group.sure)
        assertEquals("Citroën", group.suggested)
    }

    @Test
    fun `alike spellings are only maybe the same, and the longer wins a tie`() {
        val rows = listOf(row(make = "Lamborgini"), row(make = "Lamborghini"), row(make = "Porshe"), row(make = "Porsche"))

        val groups = rows.groups(TidyField.Make)

        assertEquals(2, groups.size)
        assertTrue(groups.none { it.sure })
        assertEquals(setOf("Lamborghini", "Porsche"), groups.map { it.suggested }.toSet())
    }

    @Test
    fun `other numbers, a longer line or short names are not alike`() {
        val rows = listOf(
            row(series = "Mainline 2024"), row(series = "Mainline 2025"),
            row(series = "Batman"), row(series = "Batman 2"),
            row(brand = "Tomica"), row(brand = "Tomica Premium"),
            row(make = "Mini"), row(make = "Mino"),
            row(make = "Mercedes"), row(make = "Mercedes-Benz"),
        )

        assertEquals(emptyList<SpellingGroup>(), Tidy.report(rows).spellings)
    }

    @Test
    fun `a word for another is not a slip, found on the phone`() {
        val rows = listOf(
            row(series = "Thrill Racers - Ice '12"),
            row(series = "THRILL RACERS - SPACE '12"),
            row(series = "THRILL RACERS - ICE 12"),
            row(series = "Thrill Racers - Fire '12"),
        )

        val group = rows.groups(TidyField.Series).single()

        assertTrue(group.sure)
        assertEquals(setOf("Thrill Racers - Ice '12", "THRILL RACERS - ICE 12"), group.spellings.map { it.text }.toSet())
    }

    @Test
    fun `not the same keeps the spellings that differ only in case together`() {
        val rows = listOf(row(make = "Lamborgini"), row(make = "Lamborghini"), row(make = "LAMBORGHINI"))
        val mixed = rows.groups(TidyField.Make).single()
        assertFalse(mixed.sure)

        val left = rows.groups(TidyField.Make, Tidy.ignoreKeys(mixed)).single()

        assertTrue(left.sure)
        assertEquals(setOf("Lamborghini", "LAMBORGHINI"), left.spellings.map { it.text }.toSet())
    }

    @Test
    fun `scales written with a slash or spaces are tidied, other scales left alone`() {
        val rows = List(5) { row(scale = "1:64") } + row(scale = "1/64") + row(scale = "1 : 64") +
            row(scale = "1/60") + row(scale = "1:65") + row(scale = "1:43")

        val groups = rows.groups(TidyField.Scale)

        assertEquals(2, groups.size)
        val usual = groups.first { "1:64" in it.options }
        assertTrue(usual.sure)
        assertEquals("1:64", usual.suggested)
        assertEquals(setOf("1:64", "1/64", "1 : 64"), usual.spellings.map { it.text }.toSet())
        assertEquals(listOf("1:60", "1/60"), groups.first { "1:60" in it.options }.options)
    }

    @Test
    fun `what was left as it is stays away until another spelling joins`() {
        val rows = listOf(row(brand = "Hot Wheels"), row(brand = "HOT WHEELS"))
        val ignored = Tidy.ignoreKeys(rows.groups(TidyField.Brand).single())

        assertEquals(emptyList<SpellingGroup>(), rows.groups(TidyField.Brand, ignored))

        val more = rows + row(brand = "Hotwheels")
        assertEquals(3, more.groups(TidyField.Brand, ignored).single().spellings.size)

        // One scale left as it was.
        val odd = listOf(row(scale = "1/60"))
        val skipped = Tidy.ignoreKeys(odd.groups(TidyField.Scale).single())
        assertEquals(emptyList<SpellingGroup>(), odd.groups(TidyField.Scale, skipped))
    }

    @Test
    fun `the same car entered twice is found, whatever its status`() {
        val first = row(name = "'69 Chevelle", brand = "Hot Wheels", number = "137/250", color = "Blue")
        val second = row(name = "69 CHEVELLE", brand = "HOT WHEELS", number = "137/250")
        val noNumber = row(name = "'69 Chevelle", brand = "Hot Wheels")
        val otherNumber = row(name = "'69 Chevelle", brand = "Hot Wheels", number = "12/250")
        val unrelated = row(name = "Datsun 510", brand = "Hot Wheels")

        val duplicates = Tidy.report(listOf(first, second, noNumber, otherNumber, unrelated)).duplicates

        // The one without a number could be either, but doesn't tie the two numbers together.
        assertEquals(listOf(DuplicateGroup(listOf(first.id, second.id, noNumber.id))), duplicates)
    }

    @Test
    fun `colour, year, scale, rarity and brand tell cars apart`() {
        val rows = listOf(
            row(name = "Nissan Skyline", brand = "Hot Wheels", color = "Red"),
            row(name = "Nissan Skyline", brand = "Hot Wheels", color = "White"),
            row(name = "Porsche 911", brand = "Hot Wheels", year = 2023),
            row(name = "Porsche 911", brand = "Hot Wheels", year = 2024),
            row(name = "Ford GT", brand = "Maisto", scale = "1:64"),
            row(name = "Ford GT", brand = "Maisto", scale = "1:24"),
            row(name = "Datsun 510", brand = "Hot Wheels"),
            row(name = "Datsun 510", brand = "Hot Wheels", rarity = Rarity.TreasureHunt),
            row(name = "Mazda RX-7", brand = "Hot Wheels"),
            row(name = "Mazda RX-7", brand = "Tomica"),
            // The same scale, written another way, is no difference.
            row(name = "Supra", brand = "Mini GT", scale = "1/64"),
            row(name = "Supra", brand = "Mini GT", scale = "1:64"),
        )

        val duplicates = Tidy.report(rows).duplicates

        assertEquals(1, duplicates.size)
        assertEquals(listOf(11L, 12L), duplicates.single().ids)
    }

    @Test
    fun `not a duplicate is remembered per pair`() {
        val a = row(name = "Batmobile", brand = "Hot Wheels")
        val b = row(name = "Batmobile", brand = "Hot Wheels")
        val ignored = Tidy.ignoreKeys(Tidy.report(listOf(a, b)).duplicates.single())

        assertTrue(Tidy.report(listOf(a, b), ignored).duplicates.isEmpty())

        val c = row(name = "Batmobile", brand = "Hot Wheels")
        val again = Tidy.report(listOf(a, b, c), ignored).duplicates
        assertEquals(listOf(DuplicateGroup(listOf(a.id, c.id))), again)
        assertFalse(b.id in again.single().ids)
    }

    @Test
    fun `keys drop case, accents and everything but letters and digits`() {
        assertEquals("citroen", Tidy.key("Citroën"))
        assertEquals("minigt", Tidy.key(" MINI-GT "))
        assertEquals("トミカ", Tidy.key("トミカ"))
        assertEquals("", Tidy.key(null))
    }
}
