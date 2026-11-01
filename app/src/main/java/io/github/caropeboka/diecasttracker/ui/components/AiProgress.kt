package io.github.caropeboka.diecasttracker.ui.components

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.AiEvent
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.Formats
import io.github.caropeboka.diecasttracker.util.formatBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** One line of [AiProgress]. [startedAt]: elapsed-realtime millis; [fraction]: how much of the upload is sent. */
@Immutable
data class AiStep(
    val text: String,
    val detail: String? = null,
    val state: State,
    val startedAt: Long,
    val fraction: Float? = null,
) {
    enum class State { Done, Running, Missed }
}

@Immutable
data class AiProgress(val steps: List<AiStep>, @StringRes val hint: Int) {
    val running: AiStep? get() = steps.lastOrNull()?.takeIf { it.state == AiStep.State.Running }
}

/**
 * Turns an AI request into a short log of steps (✓ done, ⚠ missed, the current one with its seconds), so a slow
 * answer doesn't look like a hang. Safe from any thread: the upload is reported from OkHttp's.
 */
class AiProgressLog(
    private val texts: Texts,
    @StringRes private val hint: Int,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val _state = MutableStateFlow<AiProgress?>(null)

    // Whose steps these are: the provider taking the request.
    @Volatile
    private var provider = DEFAULT_PROVIDER

    /** Null while nothing runs. */
    val state: StateFlow<AiProgress?> = _state.asStateFlow()

    fun start(text: String) {
        provider = DEFAULT_PROVIDER
        _state.value = AiProgress(listOf(running(text)), hint)
    }

    /** Ends the running step and starts [text]. */
    fun step(text: String, detail: String? = null, fraction: Float? = null) =
        edit { steps -> steps.ended() + running(text, detail, fraction) }

    fun stop() {
        _state.value = null
    }

    fun onEvent(event: AiEvent) {
        when (event) {
            is AiEvent.Provider -> provider = event.name
            is AiEvent.Resting -> note(texts(R.string.ai_resting, event.name, restEnd(texts, event.until, zone)))
            is AiEvent.Switching -> step(texts(R.string.ai_switching, event.name))
            is AiEvent.Trimmed -> note(texts(R.string.ai_trimmed, provider, event.sent, event.total))
            AiEvent.ListingModels -> step(texts(R.string.ai_listing_models, provider))
            is AiEvent.Sending -> step(
                texts(R.string.ai_sending, provider, formatBytes(event.bytes)),
                if (event.fallback == 0) event.model else texts(R.string.ai_fallback_model, event.model, event.fallback),
                fraction = 0f,
            )
            is AiEvent.Uploading -> progress(event.sent.toFloat() / event.total)
            is AiEvent.Waiting -> edit { steps -> steps.ended() + running(texts(R.string.ai_waiting), steps.lastOrNull()?.detail) }
            is AiEvent.Missed -> edit { steps ->
                val text = texts(if (event.retrying) R.string.ai_missed_retrying else R.string.ai_missed, provider, event.reason)
                steps.filterNot { it.state == AiStep.State.Running } + AiStep(text, steps.lastOrNull()?.detail, AiStep.State.Missed, clock())
            }
            AiEvent.Answered -> step(texts(R.string.ai_answered))
        }
    }

    /** How far the running step is (an upload, a download). Moves the bar only in visible steps: the upload reports every 32 KB. */
    fun progress(fraction: Float) = edit { steps ->
        val last = steps.lastOrNull()
        val shown = last?.fraction
        if (last?.state != AiStep.State.Running || (shown != null && abs(fraction - shown) < 0.02f)) steps else steps.dropLast(1) + last.copy(fraction = fraction)
    }

    private fun edit(change: (List<AiStep>) -> List<AiStep>) = _state.update { it?.copy(steps = change(it.steps)) }

    /** A ⚠ line that isn't a step of its own: something left out or set aside. */
    private fun note(text: String) = edit { steps -> steps.ended() + AiStep(text, null, AiStep.State.Missed, clock()) }

    private fun running(text: String, detail: String? = null, fraction: Float? = null) =
        AiStep(text, detail, AiStep.State.Running, clock(), fraction)

    private fun List<AiStep>.ended() = map { if (it.state == AiStep.State.Running) it.copy(state = AiStep.State.Done, fraction = null) else it }
}

private const val DEFAULT_PROVIDER = "Gemini"
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val CLOCK_ID = DateTimeFormatter.ofPattern("HH.mm")

/** When a provider's quota is back: "15:00" ("15.00" in Indonesian), or "tomorrow 15:00" when it isn't today. */
internal fun restEnd(texts: Texts, until: Long, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String {
    val time = Instant.ofEpochMilli(until).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time.format(if (Formats.language == "id") CLOCK_ID else CLOCK)
    return if (time.toLocalDate().isAfter(today)) texts(R.string.ai_tomorrow_at, clock) else clock
}

/** Steps shown at once; a run of fallbacks would push the list off a small card. */
private const val VISIBLE_STEPS = 5

/** [framed]: on its own card (off for the photo's overlay, which has its own backdrop). */
@Composable
fun AiProgressView(progress: AiProgress, modifier: Modifier = Modifier, framed: Boolean = true) {
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            delay(1_000)
            value = SystemClock.elapsedRealtime()
        }
    }
    val shape = MaterialTheme.shapes.medium
    val frame = if (framed) {
        Modifier.background(Garage.Muted, shape).border(1.dp, Garage.Border, shape).padding(12.dp)
    } else {
        Modifier
    }

    Column(modifier.fillMaxWidth().then(frame), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        progress.steps.takeLast(VISIBLE_STEPS).forEach { StepRow(it, now) }

        val fraction = progress.running?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Garage.MeterBar, trackColor = Garage.MeterTrack)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Garage.MeterBar, trackColor = Garage.MeterTrack)
        }
        Text(stringResource(progress.hint), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
    }
}

@Composable
private fun StepRow(step: AiStep, now: Long) {
    val running = step.state == AiStep.State.Running

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) {
            when (step.state) {
                AiStep.State.Done -> Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.ai_step_done), tint = Garage.Gain, modifier = Modifier.size(16.dp))
                AiStep.State.Missed -> Icon(painterResource(R.drawable.ic_warning), contentDescription = stringResource(R.string.ai_step_missed), tint = Garage.Brand2, modifier = Modifier.size(16.dp))
                AiStep.State.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = Garage.Brand, strokeWidth = 2.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            val seconds = ((now - step.startedAt) / 1_000).coerceAtLeast(0)
            Text(
                if (running && seconds > 0) stringResource(R.string.ai_step_seconds, step.text, seconds) else step.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (step.state == AiStep.State.Done) Garage.MutedForeground else Garage.Foreground,
            )
            step.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
