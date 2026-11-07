package io.github.caropeboka.diecasttracker.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.ChatAnswer
import io.github.caropeboka.diecasttracker.data.ai.ChatContext
import io.github.caropeboka.diecasttracker.data.ai.ChatTurn
import io.github.caropeboka.diecasttracker.data.ai.DiecastChat
import io.github.caropeboka.diecasttracker.data.ai.FieldSuggestion
import io.github.caropeboka.diecasttracker.data.ai.MAX_SCAN_PHOTOS
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressLog
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One question in "Tanya AI" and what came back; neither [answer] nor [error] while it is being asked. */
@Serializable
data class ChatExchange(
    val question: String,
    val answer: ChatAnswer? = null,
    val error: String? = null,
    /** The suggestions put into the form, by field. */
    val applied: Set<String> = emptySet(),
)

/** "Tanya AI"'s conversation, kept in the saved state so it survives the process being killed. */
class AskAiViewModel(
    private val saved: SavedStateHandle,
    private val chat: DiecastChat,
    private val texts: Texts,
) : ViewModel() {
    var exchanges by mutableStateOf(
        saved.get<String>(EXCHANGES)?.let { runCatching { Json.decodeFromString<List<ChatExchange>>(it) }.getOrNull() }.orEmpty()
            // Asked when the process went: no answer is coming.
            .map { if (it.answer == null && it.error == null) it.copy(error = texts(R.string.ask_interrupted)) else it },
    )
        private set
    var draft by mutableStateOf(saved.get<String>(DRAFT).orEmpty())
        private set
    var asking by mutableStateOf(false)
        private set

    private val log = AiProgressLog(texts, R.string.ai_hint_quick)
    val progress: StateFlow<AiProgress?> = log.state

    fun changeDraft(value: String) {
        draft = value
        saved[DRAFT] = value
    }

    fun ask(question: String, context: ChatContext) {
        val text = question.trim()
        if (text.isEmpty() || asking) return
        // Earlier answers as they were given, so the AI knows what it already suggested.
        val history = exchanges.flatMap { exchange ->
            exchange.answer?.let { listOf(ChatTurn(fromUser = true, exchange.question), ChatTurn(fromUser = false, it.raw)) }.orEmpty()
        }
        update(exchanges + ChatExchange(text))
        if (text == draft.trim()) changeDraft("")
        asking = true
        val photos = minOf(context.photos.size, MAX_SCAN_PHOTOS)
        log.start(if (photos > 0) texts.plural(R.plurals.ai_preparing_photos, photos, photos) else texts(R.string.ask_preparing))
        viewModelScope.launch {
            val index = exchanges.lastIndex
            val done = try {
                exchanges[index].copy(answer = chat.ask(context, history, text, log::onEvent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                exchanges[index].copy(error = e.message)
            } catch (e: Exception) {
                exchanges[index].copy(error = texts(R.string.failed_because, e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                asking = false
                log.stop()
            }
            update(exchanges.mapIndexed { i, it -> if (i == index) done else it })
        }
    }

    /** Asks a question that got no answer again. */
    fun retry(index: Int, context: ChatContext) {
        val exchange = exchanges.getOrNull(index)?.takeIf { it.error != null } ?: return
        if (asking) return
        update(exchanges.filterIndexed { i, _ -> i != index })
        ask(exchange.question, context)
    }

    fun applied(index: Int, field: String) =
        update(exchanges.mapIndexed { i, it -> if (i == index) it.copy(applied = it.applied + field) else it })

    fun clear() {
        if (!asking) update(emptyList())
    }

    private fun update(value: List<ChatExchange>) {
        exchanges = value
        saved[EXCHANGES] = Json.encodeToString(value)
    }

    companion object {
        private const val EXCHANGES = "exchanges"
        private const val DRAFT = "draft"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { AskAiViewModel(createSavedStateHandle(), chat, texts) }
            }
        }
    }
}

private val QUICK_QUESTIONS = listOf(
    R.string.ask_quick_year,
    R.string.ask_quick_rarity,
    R.string.ask_quick_series,
    R.string.ask_quick_versions,
    R.string.ask_quick_fill,
)

private val FIELD_LABELS = mapOf(
    "name" to R.string.field_name,
    "brand" to R.string.field_brand,
    "make" to R.string.field_make,
    "series" to R.string.field_series,
    "collection_number" to R.string.field_collection_number,
    "year" to R.string.field_year,
    "scale" to R.string.field_scale,
    "color" to R.string.field_color,
    "rarity" to R.string.field_rarity,
    "packaging" to R.string.field_packaging,
    "condition" to R.string.field_condition,
    "notes" to R.string.field_notes,
)

/**
 * "Tanya AI": a chat about the diecast in the form that sees its photos and fields. Answers may end with changes to
 * the form, each put in with "Pakai" (marked ✦ like a scan's, nothing saved until the form is). [key] tells one
 * diecast's conversation from another's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskAiSheet(
    key: String,
    form: DiecastForm,
    context: () -> ChatContext,
    onApply: (FieldSuggestion) -> Boolean,
    onDismiss: () -> Unit,
) {
    val viewModel: AskAiViewModel = viewModel(key = "ask-ai-$key", factory = AskAiViewModel.Factory)
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val exchanges = viewModel.exchanges
    val list = rememberLazyListState()
    val photos = minOf(form.photos.size, MAX_SCAN_PHOTOS)

    // The newest message in view, and again when its answer comes.
    LaunchedEffect(exchanges.size, viewModel.asking) {
        if (exchanges.isNotEmpty()) list.animateScrollToItem(exchanges.size)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ask_ai), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (photos > 0) pluralStringResource(R.plurals.ask_sees, photos, photos) else stringResource(R.string.ask_sees_form),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
                if (exchanges.isNotEmpty()) {
                    TextButton(onClick = viewModel::clear, enabled = !viewModel.asking) { Text(stringResource(R.string.ask_new_chat)) }
                }
            }
            HorizontalDivider(color = Garage.Border)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = list,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Text(stringResource(R.string.ask_intro), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
                }
                itemsIndexed(exchanges) { index, exchange ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Question(exchange.question)
                        when {
                            exchange.answer != null -> Answer(exchange.answer, exchange.applied, form) { suggestion ->
                                if (onApply(suggestion)) viewModel.applied(index, suggestion.field)
                            }
                            exchange.error != null -> Failed(exchange.error, enabled = !viewModel.asking) { viewModel.retry(index, context()) }
                            else -> progress?.let { AiProgressView(it) }
                        }
                    }
                }
            }

            HorizontalDivider(color = Garage.Border)
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QUICK_QUESTIONS.forEach { question ->
                    val text = stringResource(question)
                    SuggestionChip(onClick = { viewModel.ask(text, context()) }, enabled = !viewModel.asking, label = { Text(text) })
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = viewModel.draft,
                    onValueChange = viewModel::changeDraft,
                    placeholder = { Text(stringResource(R.string.ask_hint)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = fieldColors(),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { viewModel.ask(viewModel.draft, context()) },
                    enabled = !viewModel.asking && viewModel.draft.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.ask_send))
                }
            }
        }
    }
}

@Composable
private fun Question(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.End)
                .background(Garage.Brand.copy(alpha = 0.18f), MaterialTheme.shapes.large)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Answer(answer: ChatAnswer, applied: Set<String>, form: DiecastForm, onApply: (FieldSuggestion) -> Unit) {
    val uris = LocalUriHandler.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (answer.text.isNotEmpty()) Text(chatText(answer.text), style = MaterialTheme.typography.bodyMedium)

        // Ones the form already says (typed meanwhile, or not a value it takes) are left out, unless put in from here.
        val shown = answer.suggestions.filter { it.field in applied || form.withSuggestion(it.field, it.value, LocalDate.now().year) != null }
        if (shown.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().background(Garage.Card, MaterialTheme.shapes.medium).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.ask_suggests), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
                shown.forEach { suggestion -> Suggestion(suggestion, suggestion.field in applied, form, onApply) }
            }
        }

        if (answer.sources.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ask_sources), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
                answer.sources.take(MAX_SOURCES).forEach { source ->
                    Text(
                        "↗ ${source.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.Brand,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { runCatching { uris.openUri(source.url) } }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        Text(
            if (answer.searched) stringResource(R.string.ask_by_search, answer.by) else answer.by,
            style = MaterialTheme.typography.labelSmall,
            color = Garage.MutedForeground,
        )
    }
}

@Composable
private fun Suggestion(suggestion: FieldSuggestion, applied: Boolean, form: DiecastForm, onApply: (FieldSuggestion) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                FIELD_LABELS[suggestion.field]?.let { stringResource(it) } ?: suggestion.field,
                style = MaterialTheme.typography.labelMedium,
                color = Garage.MutedForeground,
            )
            val now = form.chatFields()[suggestion.field].orEmpty()
            val before = if (applied) "" else (shown(suggestion.field, now).ifEmpty { stringResource(R.string.ask_empty_value) } + " → ")
            Text(before + shown(suggestion.field, suggestion.value), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            suggestion.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
        }
        if (applied) {
            IconLabel(R.drawable.ic_check, stringResource(R.string.ask_used))
        } else {
            Button(onClick = { onApply(suggestion) }, shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(stringResource(R.string.ask_use))
            }
        }
    }
}

/** A value as the form shows it: the label of a rarity, packaging or condition. */
@Composable
private fun shown(field: String, value: String): String {
    val label = when (field) {
        "rarity" -> Rarity.entries.find { it.key == value }?.label
        "packaging" -> Packaging.entries.find { it.key == value }?.label
        "condition" -> Condition.entries.find { it.key == value }?.label
        else -> null
    }
    return label?.let { stringResource(it) } ?: value
}

@Composable
private fun Failed(error: String, enabled: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Loss.copy(alpha = 0.1f), MaterialTheme.shapes.large)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(error, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onRetry, enabled = enabled, shape = MaterialTheme.shapes.medium) {
            IconLabel(R.drawable.ic_sync, stringResource(R.string.retry))
        }
    }
}

/** The little Markdown models answer in: **bold**, headings and "*"/"-" lists. */
internal fun chatText(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val heading = HEADING.find(line)
        val body = when {
            heading != null -> line.substring(heading.range.last + 1)
            else -> BULLET.find(line)?.let { line.replaceRange(it.range, it.groupValues[1] + "• ") } ?: line
        }
        val parts = body.split("**")
        // Odd parts sat between a pair of **; a lone one at the end stays as it was.
        val paired = parts.size % 2 == 1
        parts.forEachIndexed { i, part ->
            val between = i % 2 == 1 && (paired || i < parts.lastIndex)
            if (i % 2 == 1 && !between) append("**")
            if (heading != null || between) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part)
        }
    }
}

private const val MAX_SOURCES = 5
private val HEADING = Regex("^\\s*#{1,6}\\s+")
private val BULLET = Regex("^(\\s*)[*-]\\s+")
