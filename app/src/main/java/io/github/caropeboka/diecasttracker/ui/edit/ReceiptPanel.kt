package io.github.caropeboka.diecasttracker.ui.edit

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.PhotoException
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.db.ReceiptEntity
import io.github.caropeboka.diecasttracker.data.db.ReceiptUse
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.receipt.ReceiptException
import io.github.caropeboka.diecasttracker.data.receipt.ReceiptLine
import io.github.caropeboka.diecasttracker.data.receipt.ReceiptReader
import io.github.caropeboka.diecasttracker.data.receipt.ReceiptRepository
import io.github.caropeboka.diecasttracker.data.receipt.ReceiptStore
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressLog
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.formatMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** A diecast for the AI to find on a receipt: [key] tells it apart (a queued photo's id; 0 on the form), [text] describes it. */
data class ReceiptItem(val key: Long, val text: String)

/**
 * A receipt's photo read by the AI, for the form or the bulk scan: the camera or the gallery, the reading with its
 * log, and the lines on show with the one each diecast was matched with. Receipts read in the last two weeks can be
 * used again without the AI, for the next diecast of the same purchase.
 */
class ReceiptViewModel(
    private val saved: SavedStateHandle,
    private val receipts: ReceiptRepository,
    private val store: ReceiptStore,
    private val reader: ReceiptReader,
    private val photos: PhotoStore,
    private val texts: Texts,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = reader.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val recent: StateFlow<List<ReceiptEntity>> = receipts.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var reading by mutableStateOf(false)
        private set
    private val log = AiProgressLog(texts, R.string.ai_hint_quick)
    val progress: StateFlow<AiProgress?> = log.state

    /** Why the last photo couldn't be read; it can be tried again while it waits. */
    var error by mutableStateOf<String?>(null)
        private set
    val canRetry get() = saved.get<String>(WAITING) != null

    /** The receipt whose lines are on show; null while the sheet is closed. */
    val shown: StateFlow<Long?> = saved.getStateFlow(SHOWN, null)

    /** Which line each diecast takes (by [ReceiptItem.key]), as matched by the AI and changed by the collector. */
    var matches by mutableStateOf(saved.get<String>(MATCHES)?.let { runCatching { Json.decodeFromString<Map<Long, Int>>(it) }.getOrNull() }.orEmpty())
        private set

    fun observe(id: Long?): Flow<ReceiptEntity?> = id?.let(receipts::observe) ?: flowOf(null)

    fun uses(id: Long?): Flow<List<ReceiptUse>> = id?.let(receipts::observeUses) ?: flowOf(emptyList())

    fun file(receipt: ReceiptEntity): File = receipts.file(receipt)

    /** Where the camera app writes the receipt; remembered in case this process is killed meanwhile. */
    fun cameraTarget(): Uri {
        val (file, uri) = photos.newCameraTarget()
        saved[CAMERA] = file.path
        return uri
    }

    fun onCamera(success: Boolean, items: List<ReceiptItem>) {
        val file = saved.get<String>(CAMERA)?.let(::File) ?: return
        saved[CAMERA] = null
        if (success && file.length() > 0) prepare(items) { photos.screenshot(file) } else file.delete()
    }

    fun cameraUnavailable() {
        saved.get<String>(CAMERA)?.let { File(it).delete() }
        saved[CAMERA] = null
        error = texts(R.string.edit_no_camera)
    }

    fun fromGallery(uri: Uri?, items: List<ReceiptItem>) {
        if (uri != null) prepare(items) { photos.screenshot(uri) }
    }

    /** The photo sharp enough for small print, kept in the receipts' folder, then read. */
    private fun prepare(items: List<ReceiptItem>, load: () -> ByteArray) {
        if (reading) return
        reading = true
        error = null
        log.start(texts(R.string.receipt_preparing))
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { store.save(load()) }
                forgetWaiting()
                saved[WAITING] = name
                read(name, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = (e as? PhotoException)?.message ?: texts(R.string.edit_photo_failed, e.message ?: e::class.simpleName.orEmpty())
                reading = false
                log.stop()
            }
        }
    }

    /** Another go at the photo that couldn't be read (no signal, a busy AI). */
    fun retry(items: List<ReceiptItem>) {
        val name = saved.get<String>(WAITING) ?: return
        if (reading) return
        reading = true
        error = null
        log.start(texts(R.string.receipt_preparing))
        viewModelScope.launch { read(name, items) }
    }

    /** Gives up on the photo that couldn't be read. */
    fun dismissError() {
        forgetWaiting()
        error = null
    }

    private fun forgetWaiting() {
        store.delete(saved.get<String>(WAITING))
        saved[WAITING] = null
    }

    private suspend fun read(name: String, items: List<ReceiptItem>) {
        try {
            log.step(texts(R.string.receipt_reading))
            val image = withContext(Dispatchers.IO) { store.file(name).readBytes() }
            var reading = reader.read(image, items.map { it.text }, log::onEvent)
            if (reading.mapQueries.isNotEmpty()) {
                log.step(texts(R.string.receipt_locating))
                reading = reader.locate(reading)
            }
            val receipt = receipts.add(name, reading)
            saved[WAITING] = null
            show(receipt.id, reading.matches.mapNotNull { (index, line) -> items.getOrNull(index)?.let { it.key to line } }.toMap())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReceiptException) {
            // Not a receipt, or nothing on it: another photo is needed, not another go.
            forgetWaiting()
            error = e.message
        } catch (e: AiException) {
            error = e.message
        } catch (e: Exception) {
            error = texts(R.string.receipt_failed, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            reading = false
            log.stop()
        }
    }

    /**
     * Reads [receipt] again for its store's address and looks that up on the map: for one read before addresses were,
     * or whose store wasn't found then. [onFound] gets it back with its point; not found, the receipt says so.
     */
    fun findOnMap(receipt: ReceiptEntity, onFound: (ReceiptEntity) -> Unit) {
        if (reading) return
        reading = true
        error = null
        log.start(texts(R.string.receipt_reading))
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { receipts.file(receipt).readBytes() }
                var again = reader.read(image, emptyList(), log::onEvent)
                if (again.address == null && receipt.address == null) {
                    error = texts(R.string.receipt_no_address)
                    return@launch
                }
                log.step(texts(R.string.receipt_locating))
                again = reader.locate(again)
                val placed = receipts.setPlace(receipt, again)
                if (placed.latitude != null) onFound(placed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                error = e.message
            } catch (e: Exception) {
                error = texts(R.string.receipt_failed, e.message ?: e::class.simpleName.orEmpty())
            } finally {
                reading = false
                log.stop()
            }
        }
    }

    /** The collector (or the AI) chose the lines on show; until then the screen may suggest one. */
    var picked by mutableStateOf(saved.get<Boolean>(PICKED) ?: false)
        private set

    /** Shows [id]'s lines, [matched] chosen. */
    fun show(id: Long, matched: Map<Long, Int> = emptyMap()) {
        storeMatches(matched)
        storePicked(matched.isNotEmpty())
        saved[SHOWN] = id
    }

    fun choose(key: Long, line: Int?) {
        storeMatches(if (line == null) matches - key else matches + (key to line))
        storePicked(true)
    }

    /** A first guess the collector hasn't changed: replaced when a better one comes along. */
    fun suggest(key: Long, line: Int?) {
        if (!picked) storeMatches(if (line == null) matches - key else matches + (key to line))
    }

    private fun storePicked(value: Boolean) {
        picked = value
        saved[PICKED] = value
    }

    fun close() {
        saved[SHOWN] = null
    }

    private fun storeMatches(next: Map<Long, Int>) {
        matches = next
        saved[MATCHES] = Json.encodeToString(next)
    }

    companion object {
        private const val CAMERA = "receipt_camera_file"
        private const val WAITING = "receipt_waiting"
        private const val SHOWN = "receipt_shown"
        private const val MATCHES = "receipt_matches"
        private const val PICKED = "receipt_picked"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { ReceiptViewModel(createSavedStateHandle(), receipts, receiptStore, receiptReader, photos, texts) }
            }
        }
    }
}

/** "Indomaret Cibinong · 12 Nov 2026", or just "Receipt". */
@Composable
fun receiptTitle(receipt: ReceiptEntity): String =
    listOfNotNull(receipt.store ?: stringResource(R.string.receipt), receipt.boughtOn?.let(::formatDate)).joinToString(" · ")

/** One of a line: "Rp 33.900", "¥ 550 (Rp 58.300)", or "no price". */
@Composable
fun linePrice(line: ReceiptLine, currency: String?): String {
    val original = line.original?.let { String.format(Locale.ROOT, if (it % 1.0 == 0.0) "%.0f" else "%.2f", it) }
    return when {
        original != null && line.price != null -> "$currency $original (${formatMoney(line.price)})"
        original != null -> "$currency $original"
        line.price != null -> formatMoney(line.price)
        else -> stringResource(R.string.receipt_no_price)
    }
}

/**
 * Where the camera, the gallery and the recent receipts are offered, then the reading and its problems. The AI is
 * needed for a new receipt only.
 */
@Composable
fun ReceiptSource(
    viewModel: ReceiptViewModel,
    label: String,
    items: () -> List<ReceiptItem>,
    onRecent: (ReceiptEntity) -> Unit,
    onAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    available: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReceiptMenu(viewModel, label, items, onRecent, onAiSettings, available = available, modifier = Modifier.fillMaxWidth())
        ReceiptReading(viewModel, items)
    }
}

/**
 * The button that offers the camera, the gallery and the recent receipts: a full-width one with [label], or with
 * [icon] a small square beside a price field, like the map button beside a place ([label] is then its description).
 */
@Composable
fun ReceiptMenu(
    viewModel: ReceiptViewModel,
    label: String,
    items: () -> List<ReceiptItem>,
    onRecent: (ReceiptEntity) -> Unit,
    onAiSettings: () -> Unit,
    modifier: Modifier = Modifier,
    available: Boolean = true,
    icon: Boolean = false,
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { viewModel.onCamera(it, items()) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { viewModel.fromGallery(it, items()) }

    Box(modifier) {
        if (icon) {
            FilledTonalIconButton(
                onClick = { menu = true },
                enabled = available && !viewModel.reading,
                shape = MaterialTheme.shapes.medium,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Garage.Brand.copy(alpha = 0.12f),
                    contentColor = Garage.Brand,
                ),
                modifier = Modifier.size(48.dp),
            ) { Icon(painterResource(R.drawable.ic_receipt_long), contentDescription = label) }
        } else {
            OutlinedButton(
                onClick = { menu = true },
                enabled = available && !viewModel.reading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) { IconLabel(R.drawable.ic_receipt_long, label) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.receipt_take_photo)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null) },
                onClick = {
                    menu = false
                    if (!enabled) {
                        onAiSettings()
                    } else {
                        try {
                            camera.launch(viewModel.cameraTarget())
                        } catch (_: ActivityNotFoundException) {
                            viewModel.cameraUnavailable()
                        }
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.receipt_pick)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null) },
                onClick = {
                    menu = false
                    if (enabled) gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else onAiSettings()
                },
            )
            if (!enabled) {
                Text(
                    stringResource(R.string.receipt_needs_ai),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (recent.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Garage.Border)
                Text(
                    stringResource(R.string.receipt_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = Garage.MutedForeground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                recent.forEach { receipt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(receiptTitle(receipt), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pluralStringResource(R.plurals.receipt_lines, receipt.lines.size, receipt.lines.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Garage.MutedForeground,
                                )
                            }
                        },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                        onClick = {
                            menu = false
                            onRecent(receipt)
                        },
                    )
                }
            }
        }
    }
}

/** The reading's steps while the AI reads a receipt, then its problem if it failed (retry, close). */
@Composable
fun ReceiptReading(viewModel: ReceiptViewModel, items: () -> List<ReceiptItem>) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    progress?.let { AiProgressView(it) }
    viewModel.error?.let { message ->
        Text(message, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewModel.canRetry) {
                TextButton(onClick = { viewModel.retry(items()) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.retry)) }
            }
            TextButton(onClick = viewModel::dismissError, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.close)) }
        }
    }
}

/**
 * The form's purchase price with its receipt: [priceField] and a receipt button beside it (like the map button beside
 * a place) that offers the camera, the gallery and the recent receipts; under them the reading, then the receipt
 * itself (its photo opens it) with the line this diecast took, another line, or none. A receipt whose store has no
 * point yet can look for it on the map; [onPlace] gets it once found.
 */
@Composable
fun ReceiptPanel(
    key: String,
    receiptId: Long?,
    line: Int?,
    diecastId: Long,
    item: () -> String,
    onApply: (ReceiptEntity, Int?) -> Unit,
    onDetach: () -> Unit,
    onAiSettings: () -> Unit,
    onPlace: (ReceiptEntity) -> Unit = {},
    priceField: @Composable (Modifier) -> Unit,
) {
    val viewModel: ReceiptViewModel = viewModel(key = key, factory = ReceiptViewModel.Factory)
    val aiOn by viewModel.enabled.collectAsStateWithLifecycle()
    val attached by remember(receiptId) { viewModel.observe(receiptId) }.collectAsStateWithLifecycle(null)
    val shownId by viewModel.shown.collectAsStateWithLifecycle()
    val shown by remember(shownId) { viewModel.observe(shownId) }.collectAsStateWithLifecycle(null)
    val uses by remember(shownId) { viewModel.uses(shownId) }.collectAsStateWithLifecycle(emptyList())
    var viewing by rememberSaveable { mutableStateOf(false) }
    val items = { listOf(ReceiptItem(FORM_KEY, item())) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val receipt = attached
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            priceField(Modifier.weight(1f))
            ReceiptMenu(
                viewModel = viewModel,
                label = stringResource(if (receipt == null) R.string.receipt_from else R.string.receipt_other),
                items = items,
                onRecent = { viewModel.show(it.id) },
                onAiSettings = onAiSettings,
                icon = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        ReceiptReading(viewModel, items)
        if (receipt != null) {
            AttachedReceipt(
                receipt = receipt,
                line = line?.let(receipt.lines::getOrNull),
                photo = viewModel.file(receipt),
                onView = { viewing = true },
                onOtherLine = { viewModel.show(receipt.id, line?.let { mapOf(FORM_KEY to it) }.orEmpty()) },
                onDetach = onDetach,
                onFind = { viewModel.findOnMap(receipt, onPlace) }.takeIf { aiOn && receipt.latitude == null },
                busy = viewModel.reading,
            )
        }
    }

    val open = shown
    val others = uses.filter { it.id != diecastId }
    // Without the AI's match (a receipt used again): the first diecast line the others left.
    LaunchedEffect(open, others) { if (open != null) viewModel.suggest(FORM_KEY, firstFree(open, others)) }
    if (open != null) {
        ReceiptLinesSheet(
            receipt = open,
            chosen = viewModel.matches[FORM_KEY],
            uses = others,
            photo = viewModel.file(open),
            onChoose = { viewModel.choose(FORM_KEY, it) },
            onUse = { chosen ->
                onApply(open, chosen)
                viewModel.close()
            },
            onDismiss = viewModel::close,
        )
    }
    val receipt = attached
    if (viewing && receipt != null) ReceiptViewer(viewModel.file(receipt), receiptTitle(receipt)) { viewing = false }
}

private const val FORM_KEY = 0L

/** The first diecast line other diecasts haven't taken all of. */
private fun firstFree(receipt: ReceiptEntity, uses: List<ReceiptUse>): Int? =
    receipt.lines.indices.firstOrNull { index ->
        val line = receipt.lines[index]
        line.diecast && uses.count { it.line == index } < line.quantity
    }

@Composable
private fun AttachedReceipt(
    receipt: ReceiptEntity,
    line: ReceiptLine?,
    photo: File,
    onView: () -> Unit,
    onOtherLine: () -> Unit,
    onDetach: () -> Unit,
    onFind: (() -> Unit)?,
    busy: Boolean,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = Garage.Muted, border = BorderStroke(1.dp, Garage.Border)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val view = stringResource(R.string.receipt_view)
                AsyncImage(
                    model = photo,
                    contentDescription = view,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Garage.Card)
                        .clickable(onClickLabel = view, role = Role.Image, onClick = onView),
                )
                Column(Modifier.weight(1f)) {
                    Text(receiptTitle(receipt), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        line?.let { "${it.name} · ${linePrice(it, receipt.currency)}" } ?: stringResource(R.string.receipt_no_line_chosen),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ReceiptAddress(receipt)
            if (onFind != null) {
                OutlinedButton(onClick = onFind, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_place), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.receipt_find_on_map))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onView, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.receipt_view)) }
                TextButton(onClick = onOtherLine, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.receipt_other_line)) }
                TextButton(onClick = onDetach, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.receipt_detach)) }
            }
        }
    }
}

/** The receipt's lines: diecasts first, the rest folded under them; which one this diecast is, and its price used. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiptLinesSheet(
    receipt: ReceiptEntity,
    chosen: Int?,
    uses: List<ReceiptUse>,
    photo: File,
    onChoose: (Int?) -> Unit,
    onUse: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    var viewing by rememberSaveable { mutableStateOf(false) }
    val (diecasts, others) = receipt.lines.withIndex().partition { it.value.diecast }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubHeading(stringResource(R.string.receipt_which_line))
            ReceiptSummary(receipt, photo) { viewing = true }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(diecasts + others, key = { _, line -> line.index }) { position, (index, line) ->
                    if (position == diecasts.size && others.isNotEmpty()) {
                        Text(
                            stringResource(R.string.receipt_other_items),
                            style = MaterialTheme.typography.labelMedium,
                            color = Garage.MutedForeground,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    LineRow(
                        line = line,
                        currency = receipt.currency,
                        selected = chosen == index,
                        takenBy = uses.filter { it.line == index }.map { it.name },
                        onClick = { onChoose(if (chosen == index) null else index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.cancel)) }
                val price = chosen?.let(receipt.lines::getOrNull)?.price
                Button(onClick = { onUse(chosen) }, shape = MaterialTheme.shapes.medium) {
                    Text(
                        when {
                            price != null -> stringResource(R.string.receipt_use_price, formatMoney(price))
                            chosen != null -> stringResource(R.string.receipt_use_line)
                            else -> stringResource(R.string.receipt_keep_only)
                        },
                    )
                }
            }
        }
    }
    if (viewing) ReceiptViewer(photo, receiptTitle(receipt)) { viewing = false }
}

/** The receipt's photo (a tap opens it), store, date, total, and what the AI noted. */
@Composable
private fun ReceiptSummary(receipt: ReceiptEntity, photo: File, onView: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val view = stringResource(R.string.receipt_view)
        AsyncImage(
            model = photo,
            contentDescription = view,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Garage.Muted)
                .clickable(onClickLabel = view, role = Role.Image, onClick = onView),
        )
        Column(Modifier.weight(1f)) {
            Text(receiptTitle(receipt), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            receipt.total?.let {
                Text(stringResource(R.string.receipt_total, formatMoney(it)), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
            receipt.readBy?.let {
                Text(stringResource(R.string.receipt_read_by, it), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
    }
    ReceiptAddress(receipt)
    receipt.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Brand2) }
}

/** The store's address as printed, and whether the map found it (then "Bought at" got its point). */
@Composable
private fun ReceiptAddress(receipt: ReceiptEntity) {
    val address = receipt.address ?: return
    val found = receipt.latitude != null && receipt.longitude != null
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            painterResource(R.drawable.ic_place),
            contentDescription = null,
            tint = if (found) Garage.Brand else Garage.MutedForeground,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Column {
            Text(address, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (found) R.string.receipt_on_map else R.string.receipt_not_on_map),
                style = MaterialTheme.typography.labelSmall,
                color = Garage.MutedForeground,
            )
        }
    }
}

@Composable
private fun LineRow(line: ReceiptLine, currency: String?, selected: Boolean, takenBy: List<String>, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(line.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (line.quantity > 1) stringResource(R.string.receipt_each, line.quantity, linePrice(line, currency)) else linePrice(line, currency),
                    takenBy.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.receipt_used_by, it.joinToString(", ")) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The receipt full screen: pinch or double-tap to zoom in on the small print. */
@Composable
fun ReceiptViewer(photo: File, title: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = photo,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { tap ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // The tapped spot stays under the finger.
                                    val centre = Offset(size.width / 2f, size.height / 2f)
                                    scale = DOUBLE_TAP_ZOOM
                                    offset = (centre - tap) * (DOUBLE_TAP_ZOOM - 1f)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val before = scale
                            val after = (before * zoom).coerceIn(1f, MAX_ZOOM)
                            val fromCentre = centroid - Offset(size.width / 2f, size.height / 2f)
                            offset = if (after == 1f) Offset.Zero else (offset - fromCentre) * (after / before) + fromCentre + pan
                            scale = after
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close), tint = Color.White)
                }
            }
        }
    }
}

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * The bulk scan's receipt: every photo read so far and the line it takes (as the AI matched them, changed here), or
 * none when it isn't on this receipt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptMatchSheet(
    receipt: ReceiptEntity,
    photo: File,
    rows: List<ReceiptMatchRow>,
    matches: Map<Long, Int>,
    onChoose: (Long, Int?) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var viewing by rememberSaveable { mutableStateOf(false) }
    val count = rows.count { it.key in matches }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubHeading(stringResource(R.string.receipt_match_title))
            ReceiptSummary(receipt, photo) { viewing = true }
            Text(stringResource(R.string.receipt_match_hint), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                    MatchRow(row, receipt, matches[row.key], matches) { onChoose(row.key, it) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.cancel)) }
                Button(onClick = onApply, enabled = count > 0, shape = MaterialTheme.shapes.medium) {
                    Text(pluralStringResource(R.plurals.receipt_apply_many, count, count))
                }
            }
        }
    }
    if (viewing) ReceiptViewer(photo, receiptTitle(receipt)) { viewing = false }
}

/** A queued photo in [ReceiptMatchSheet], with the receipt and line it has already (from an earlier go). */
data class ReceiptMatchRow(val key: Long, val name: String, val photo: String, val item: String, val receiptId: Long? = null, val line: Int? = null)

/**
 * The bulk scan's "Prices from a receipt": one receipt for every photo read so far, each taking the line the AI
 * matched it with (or the collector chose); [onApply] gets each photo's line, null for the ones not on it.
 */
@Composable
fun BulkReceipt(rows: List<ReceiptMatchRow>, onApply: (ReceiptEntity, Map<Long, Int?>) -> Unit, onAiSettings: () -> Unit) {
    val viewModel: ReceiptViewModel = viewModel(key = "receipt-bulk", factory = ReceiptViewModel.Factory)
    val shownId by viewModel.shown.collectAsStateWithLifecycle()
    val shown by remember(shownId) { viewModel.observe(shownId) }.collectAsStateWithLifecycle(null)
    val current = rememberUpdatedState(rows)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReceiptSource(
            viewModel = viewModel,
            label = stringResource(R.string.receipt_bulk),
            items = { current.value.map { ReceiptItem(it.key, it.item) } },
            onRecent = { receipt ->
                viewModel.show(receipt.id, current.value.filter { it.receiptId == receipt.id && it.line != null }.associate { it.key to it.line!! })
            },
            onAiSettings = onAiSettings,
            available = rows.isNotEmpty(),
        )
        Text(
            stringResource(if (rows.isEmpty()) R.string.receipt_bulk_none else R.string.receipt_bulk_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
    }

    val receipt = shown
    if (receipt != null) {
        ReceiptMatchSheet(
            receipt = receipt,
            photo = viewModel.file(receipt),
            rows = rows,
            matches = viewModel.matches,
            onChoose = viewModel::choose,
            onApply = {
                onApply(receipt, rows.associate { it.key to viewModel.matches[it.key] })
                viewModel.close()
            },
            onDismiss = viewModel::close,
        )
    }
}

@Composable
private fun MatchRow(row: ReceiptMatchRow, receipt: ReceiptEntity, line: Int?, matches: Map<Long, Int>, onChoose: (Int?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        DiecastPhoto(
            row.photo,
            null,
            Modifier.size(width = 44.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)),
            placeholderSize = 20.dp,
        )
        Box(Modifier.weight(1f)) {
            Surface(
                onClick = { menu = true },
                shape = MaterialTheme.shapes.medium,
                color = Garage.Muted,
                border = BorderStroke(1.dp, if (line != null) Garage.Brand.copy(alpha = 0.5f) else Garage.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val chosen = line?.let(receipt.lines::getOrNull)
                    Text(
                        chosen?.let { "${it.name} · ${linePrice(it, receipt.currency)}" } ?: stringResource(R.string.receipt_not_on_it),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (chosen != null) Garage.Foreground else Garage.MutedForeground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.receipt_not_on_it)) },
                    onClick = {
                        menu = false
                        onChoose(null)
                    },
                )
                receipt.lines.forEachIndexed { index, option ->
                    val taken = matches.count { (key, taken) -> taken == index && key != row.key }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        linePrice(option, receipt.currency),
                                        stringResource(R.string.receipt_taken, taken, option.quantity).takeIf { taken > 0 || option.quantity > 1 },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Garage.MutedForeground,
                                )
                            }
                        },
                        onClick = {
                            menu = false
                            onChoose(index)
                        },
                    )
                }
            }
        }
    }
}
