package io.github.caropeboka.diecasttracker.ui.components

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.testTexts
import io.github.caropeboka.diecasttracker.util.Formats
import io.github.caropeboka.diecasttracker.util.formatBytes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "in")
class AiProgressLogTest {
    private var now = 0L
    private val texts = testTexts()
    private val log = AiProgressLog(texts, R.string.ai_hint_quick, clock = { now }, zone = ZoneOffset.UTC)

    @Before
    fun indonesian() {
        Formats.locale = Locale.forLanguageTag("id")
    }

    @After
    fun reset() {
        Formats.locale = Locale.ENGLISH
    }

    private val progress get() = log.state.value!!
    private val lines get() = progress.steps.map { step -> "${step.state} ${step.text}" + step.detail?.let { " [$it]" }.orEmpty() }

    @Test
    fun `a request becomes a log of steps, a busy model included`() {
        log.start("Menyiapkan 2 foto")
        now = 400
        log.onEvent(AiEvent.Sending("gemini-3.8-flash", 1_258_291, fallback = 0))
        log.onEvent(AiEvent.Uploading(629_145, 1_258_291))
        assertEquals(0.5f, progress.running!!.fraction!!, 0.01f)

        now = 2_000
        log.onEvent(AiEvent.Waiting("gemini-3.8-flash"))
        assertEquals(2_000L, progress.running!!.startedAt)
        assertNull(progress.running!!.fraction)

        now = 42_000
        log.onEvent(AiEvent.Missed("gemini-3.8-flash", "tidak menjawab", retrying = false))
        log.onEvent(AiEvent.Sending("gemini-flash-lite-latest", 1_258_291, fallback = 1))
        log.onEvent(AiEvent.Waiting("gemini-flash-lite-latest"))
        log.onEvent(AiEvent.Answered)
        log.step("Mencocokkan dengan koleksi")

        assertEquals(
            listOf(
                "Done Menyiapkan 2 foto",
                "Done Mengirim ke Gemini (1,2 MB) [gemini-3.8-flash]",
                "Missed Gemini tidak menjawab [gemini-3.8-flash]",
                "Done Mengirim ke Gemini (1,2 MB) [gemini-flash-lite-latest · model cadangan 1]",
                "Done Menunggu jawaban [gemini-flash-lite-latest · model cadangan 1]",
                "Done Mengolah jawaban",
                "Running Mencocokkan dengan koleksi",
            ),
            lines,
        )
        assertEquals(R.string.ai_hint_quick, progress.hint)
    }

    @Test
    fun `a quick retry of the same model says so`() {
        log.start("Menyiapkan foto")
        log.onEvent(AiEvent.Sending("gemini-3.8-flash", 300_000, fallback = 0))
        log.onEvent(AiEvent.Waiting("gemini-3.8-flash"))
        log.onEvent(AiEvent.Missed("gemini-3.8-flash", "sedang sibuk", retrying = true))

        assertEquals("Missed Gemini sedang sibuk, mencoba lagi [gemini-3.8-flash]", lines.last())
        assertEquals("Done Mengirim ke Gemini (293 KB) [gemini-3.8-flash]", lines[1])
    }

    @Test
    fun `the bar moves in visible steps only`() {
        log.start("Menyiapkan foto")
        log.onEvent(AiEvent.Sending("gemini-3.8-flash", 1_000_000, fallback = 0))
        val before = log.state.value
        log.onEvent(AiEvent.Uploading(10_000, 1_000_000))

        assertEquals(before, log.state.value)
    }

    @Test
    fun `a download gets its bar once its size is known`() {
        log.start("Menyiapkan foto")
        log.step("Mengunduh pemisah latar (sekali saja)")
        assertNull(progress.running!!.fraction)

        log.progress(0.3f)

        assertEquals(0.3f, progress.running!!.fraction!!, 0.001f)
    }

    @Test
    fun `nothing is shown before the start or after the stop`() {
        log.onEvent(AiEvent.Answered)
        assertNull(log.state.value)

        log.start("Menyiapkan foto")
        log.stop()
        // A late upload report from OkHttp's thread.
        log.onEvent(AiEvent.Uploading(500, 1_000))

        assertNull(log.state.value)
    }

    @Test
    fun `another provider taking over is named in every step`() {
        val geminiBack = System.currentTimeMillis() + 2 * 86_400_000L
        log.start("Menyiapkan 4 foto")
        log.onEvent(AiEvent.Resting("Gemini", geminiBack))
        log.onEvent(AiEvent.Provider("Mistral"))
        log.onEvent(AiEvent.Sending("mistral-small-latest", 300_000, fallback = 0))
        log.onEvent(AiEvent.Waiting("mistral-small-latest"))
        log.onEvent(AiEvent.Missed("mistral-small-latest", "kuotanya habis", retrying = false))
        log.onEvent(AiEvent.Switching("Groq"))
        log.onEvent(AiEvent.Provider("Groq"))
        log.onEvent(AiEvent.Trimmed(3, 4))
        log.onEvent(AiEvent.Sending("qwen/qwen3.8-27b", 300_000, fallback = 0))

        assertEquals(
            listOf(
                "Done Menyiapkan 4 foto",
                "Missed Gemini dilewati dulu: kuotanya habis sampai ${restEnd(texts, geminiBack, ZoneOffset.UTC)}",
                "Done Mengirim ke Mistral (293 KB) [mistral-small-latest]",
                "Missed Mistral kuotanya habis [mistral-small-latest]",
                "Done Pindah ke Groq",
                "Missed Groq hanya menerima 3 dari 4 foto",
                "Running Mengirim ke Groq (293 KB) [qwen/qwen3.8-27b]",
            ),
            lines,
        )

        // The next request starts with Gemini's name again until told otherwise.
        log.start("Menyiapkan foto")
        log.onEvent(AiEvent.Sending("gemini-3.8-flash", 300_000, fallback = 0))
        assertEquals("Running Mengirim ke Gemini (293 KB) [gemini-3.8-flash]", lines.last())
    }

    @Test
    fun `a rest ends today or tomorrow`() {
        val now = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli()

        assertEquals("10.01", restEnd(texts, now + 60_000, ZoneOffset.UTC, now))
        assertEquals("besok 07.00", restEnd(texts, Instant.parse("2026-10-21T07:00:00Z").toEpochMilli(), ZoneOffset.UTC, now))
    }

    @Test
    fun `sizes read like the phone's file manager`() {
        assertEquals("850 KB", formatBytes(870_000))
        assertEquals("1 B", formatBytes(1))
        assertEquals("2,5 MB", formatBytes(2_621_440))
    }
}
