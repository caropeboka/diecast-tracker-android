package io.github.caropeboka.diecasttracker.data.locale

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.data.testTexts
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TextsTest {
    @Test
    fun `english is the default`() {
        val texts = testTexts()

        assertEquals("Save", texts(R.string.save))
        assertEquals("1 photo", texts.plural(R.plurals.data_photos, 1, 1))
        assertEquals("3 photos", texts.plural(R.plurals.data_photos, 3, 3))
    }

    @Test
    @Config(qualifiers = "in")
    fun `an indonesian phone gets indonesian`() {
        assertEquals("Simpan", testTexts()(R.string.save))
        assertEquals("3 foto", testTexts().plural(R.plurals.data_photos, 3, 3))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `a chinese phone gets simplified chinese`() {
        assertEquals("保存", testTexts()(R.string.save))
    }

    @Test
    fun `every language can be read at once, as for storage locations written in another one`() {
        val labels = testTexts().everyLanguage().map { ShelfLayout.label(it, Shelf.Case, 13) }

        assertEquals(listOf("White case · row 3 column 2", "Rak putih · baris 3 kolom 2", "白色收纳柜 · 第 3 行第 2 列"), labels)
    }
}
