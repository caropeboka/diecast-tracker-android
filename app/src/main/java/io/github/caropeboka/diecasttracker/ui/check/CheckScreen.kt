package io.github.caropeboka.diecasttracker.ui.check

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.scan.Verdict
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.Badge
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.MoneyInput
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.components.icon
import io.github.caropeboka.diecasttracker.ui.places.LocationField
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatMoney

/**
 * "Cek di toko" (web: `diecasts/check.tsx`): at the shelf, snap a diecast (or type its name) and see straight away
 * whether it is already in the collection; then +1, "Dapat", or log it as seen here with the shelf price.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    /** The form: from a photo check ([scanItem]) or a typed [name]; wishlist or the default status. */
    onAdd: (scanItem: Long, name: String?, status: DiecastStatus?) -> Unit,
    onAiSettings: () -> Unit,
    viewModel: CheckViewModel = viewModel(factory = CheckViewModel.Factory),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val diecasts by viewModel.diecasts.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onCameraResult)
    val openCamera = {
        try {
            camera.launch(viewModel.cameraTarget())
        } catch (_: ActivityNotFoundException) {
            viewModel.cameraUnavailable()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CheckEvent.OpenCamera -> openCamera()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.menu_store_check)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
        // Checking one after another along the shelf: the camera stays under the thumb.
        bottomBar = {
            if (enabled == true && results.isNotEmpty()) CameraBar(openCamera)
        },
    ) { padding ->
        // Waiting for the AI in the order they were shot; the rest as read, the latest on top.
        val queued = results.filter { it.pending }.asReversed()
        val shown = results.filterNot { it.pending }
        val failed = shown.count { it.retryable }
        // Older checks fold into a line each; the newest is always open.
        var unfolded by rememberSaveable { mutableStateOf(emptyList<Long>()) }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "place") {
                StoreBar(viewModel.place, viewModel::changePlace, places)
            }
            item(key = "camera") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        enabled == false -> AiOff(onAiSettings)
                        results.isEmpty() -> CameraHero(enabled == true, openCamera)
                    }
                    if (queued.isNotEmpty()) QueuePanel(queued, viewModel.reading, progress)
                    if (failed > 1) {
                        OutlinedButton(onClick = viewModel::retryFailed, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            IconLabel(R.drawable.ic_sync, pluralStringResource(R.plurals.check_retry_failed, failed, failed))
                        }
                    }
                }
            }
            item(key = "search") {
                SearchBox(viewModel.query, viewModel::changeQuery, viewModel::search)
            }
            itemsIndexed(shown, key = { _, result -> result.key }) { index, result ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (index == 1) SubHeading(stringResource(R.string.check_earlier), Modifier.padding(top = 8.dp))
                    if (index == 0 || result.key in unfolded) {
                        ResultCard(
                            result = result,
                            diecasts = diecasts,
                            busy = result.key in viewModel.busy,
                            viewModel = viewModel,
                            onOpen = onOpen,
                            onAdd = { status -> onAdd(result.checkId, result.query, status) },
                            onFold = if (index == 0) null else ({ unfolded = unfolded - result.key }),
                        )
                    } else {
                        FoldedResult(result, diecasts) { unfolded = unfolded + result.key }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiOff(onAiSettings: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Brand2.copy(alpha = 0.08f), shape)
            .border(1.dp, Garage.Brand2.copy(alpha = 0.45f), shape)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            stringResource(R.string.check_needs_ai),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onAiSettings, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.open_ai_settings)) }
    }
}

/** Before the first check: the one big thing to do at the shelf. */
@Composable
private fun CameraHero(enabled: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Garage.Brand.copy(alpha = 0.16f), Garage.Card)))
            .border(1.dp, Garage.Brand.copy(alpha = 0.4f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Garage.Brand), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, tint = Garage.OnBrand, modifier = Modifier.size(34.dp))
        }
        Text(stringResource(R.string.check_hero), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.check_hero_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
            textAlign = TextAlign.Center,
        )
    }
}

/** After the first check: the camera at the bottom, for the next one along the shelf (also while the AI reads). */
@Composable
private fun CameraBar(onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Garage.Background)) {
        HorizontalDivider(color = Garage.Border)
        Button(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, Modifier.size(24.dp))
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.check_another), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Photos shot along the shelf, waiting for the AI: read one at a time, the one being read first. */
@Composable
private fun QueuePanel(queued: List<CheckResult>, reading: Long?, progress: AiProgress?) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Card)
            .border(1.dp, Garage.Brand.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.check_queue_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    pluralStringResource(R.plurals.check_queue_left, queued.size, queued.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            queued.forEach { item ->
                val now = item.key == reading
                val thumb = RoundedCornerShape(6.dp)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(thumb)
                        .then(if (now) Modifier.border(2.dp, Garage.Brand, thumb) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    CheckPhoto(item, Modifier.matchParentSize().alpha(if (now) 1f else 0.5f), 22.dp)
                    if (now) CircularProgressIndicator(Modifier.size(24.dp), color = Garage.Brand, strokeWidth = 3.dp)
                }
            }
        }
        progress?.let { AiProgressView(it, framed = false) }
        Text(stringResource(R.string.check_queue_keep_shooting), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
    }
}

/** The store, one line; tap to change it (name, map). Asked once per store, so it stays out of the way. */
@Composable
private fun StoreBar(place: PlaceValue, onChange: (PlaceValue) -> Unit, places: List<String>) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Card)
            .border(1.dp, Garage.Border, shape)
            .animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = stringResource(if (editing) R.string.done else R.string.check_change_store)) { editing = !editing }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.check_store), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
                val name = place.name.trim()
                Text(
                    name.ifEmpty { stringResource(R.string.check_store_empty) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (name.isEmpty()) Garage.MutedForeground else Garage.Foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editing) {
                Text(stringResource(R.string.done), style = MaterialTheme.typography.labelLarge, color = Garage.Brand)
            } else {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(20.dp))
            }
        }
        if (editing) {
            LocationField(
                place,
                onChange,
                places,
                stringResource(R.string.check_store_place),
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                placeholder = stringResource(R.string.check_store_name),
            )
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        keyboard?.hide()
        onSearch()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.check_type_name)) },
        placeholder = { Text(stringResource(R.string.check_type_hint)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (value.isNotBlank()) {
            { TextButton(onClick = search) { Text(stringResource(R.string.search)) } }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class VerdictStyle(@param:StringRes val title: Int, @param:DrawableRes val icon: Int, val color: Color)

/** Status is never colour-only: each verdict has its icon and words. Colours from the theme's chart set. */
private fun Verdict.style() = when (this) {
    Verdict.Owned -> VerdictStyle(R.string.verdict_owned, R.drawable.ic_check_circle, Color(0xFF199E70))
    Verdict.Wishlist -> VerdictStyle(R.string.verdict_wishlist, R.drawable.ic_favorite, Color(0xFFD55181))
    Verdict.Spotted -> VerdictStyle(R.string.verdict_spotted, R.drawable.ic_visibility, Color(0xFF9085E9))
    Verdict.New -> VerdictStyle(R.string.verdict_new, R.drawable.ic_add, Garage.Brand)
    Verdict.Failed -> VerdictStyle(R.string.verdict_failed, R.drawable.ic_warning, Color(0xFFE66767))
}

/** An older check, folded to a line: photo, verdict, name. Tap to open it again. */
@Composable
private fun FoldedResult(result: CheckResult, diecasts: Map<Long, DiecastListItem>, onUnfold: () -> Unit) {
    val style = result.verdict(diecasts).style()
    val shape = MaterialTheme.shapes.large
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Card)
            .border(1.dp, Garage.Border, shape)
            .clickable(onClickLabel = stringResource(R.string.check_unfold), onClick = onUnfold)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CheckPhoto(result, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                result.name ?: stringResource(R.string.verdict_failed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(style.icon), contentDescription = null, tint = style.color, modifier = Modifier.size(14.dp))
                Text(stringResource(style.title), style = MaterialTheme.typography.labelMedium, color = style.color, maxLines = 1)
                when {
                    result.loggedAt != null -> Text(stringResource(R.string.check_logged_short), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
                    result.saved -> Text(stringResource(R.string.check_saved_short), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
                }
            }
        }
        Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = Garage.MutedForeground)
    }
}

@Composable
private fun CheckPhoto(result: CheckResult, modifier: Modifier, iconSize: Dp) {
    if (result.photo != null) {
        DiecastPhoto(result.photo, null, modifier, placeholderSize = iconSize)
    } else {
        Box(modifier.background(Garage.Muted), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(iconSize))
        }
    }
}

/** What a verdict is mostly for: one filled button, the rest stay quieter. */
private enum class MainAction { Spot, Retry }

/**
 * A check, open: the verdict as a band across the top, the photo and what was read, the look-alikes with their
 * one-tap actions, then the main action and the rest under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    result: CheckResult,
    diecasts: Map<Long, DiecastListItem>,
    busy: Boolean,
    viewModel: CheckViewModel,
    onOpen: (Long) -> Unit,
    onAdd: (DiecastStatus?) -> Unit,
    /** Folds an older check back to its line. */
    onFold: (() -> Unit)?,
) {
    val verdict = result.verdict(diecasts)
    val style = verdict.style()
    val matches = result.matchIds.mapNotNull(diecasts::get)
    val canSpot = !result.done && verdict != Verdict.Failed
    // Something to log as seen here: a new one, or one already wanted or seen (owned ones only get +1).
    val spottable = verdict == Verdict.New || matches.any { it.status == DiecastStatus.Wishlist || it.status == DiecastStatus.Spotted }
    val shape = MaterialTheme.shapes.large

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Card)
            .border(1.dp, style.color.copy(alpha = 0.45f), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(style.color.copy(alpha = 0.16f))
                .then(if (onFold != null) Modifier.clickable(onClickLabel = stringResource(R.string.check_fold), onClick = onFold) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(style.icon), contentDescription = null, tint = style.color, modifier = Modifier.size(22.dp))
            Text(
                stringResource(style.title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = style.color,
                modifier = Modifier.weight(1f),
            )
            if (onFold != null) {
                Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.rotate(180f))
            }
        }

        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CheckPhoto(result, Modifier.size(104.dp).clip(RoundedCornerShape(8.dp)), 36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val name = result.name
                    if (name != null) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        details(result)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
                    } else {
                        Text(result.error ?: stringResource(R.string.check_unrecognised), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (matches.isNotEmpty()) {
                MatchPanel(
                    matches = matches,
                    canSpot = canSpot,
                    busy = busy,
                    onOpen = onOpen,
                    onAddOne = { viewModel.addOne(result, it) },
                    onAcquire = { viewModel.acquire(result, it.id) },
                    onSpot = { viewModel.spot(result, it) },
                )
            }

            when {
                result.loggedAt != null -> {
                    Note(R.drawable.ic_visibility, result.loggedAt.takeUnless { it.isNullOrBlank() }?.let { stringResource(R.string.check_logged_at, it) } ?: stringResource(R.string.check_logged_here))
                    // Logged as a new "Ditemui" one and then bought after all.
                    val created = result.createdId?.let(diecasts::get)
                    if (created?.status == DiecastStatus.Spotted) {
                        Button(onClick = { viewModel.acquire(result, created.id) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                            IconLabel(R.drawable.ic_check_circle, stringResource(R.string.check_bought_after_all))
                        }
                    }
                }
                result.saved -> Note(R.drawable.ic_check_circle, stringResource(R.string.check_saved_by_form))
                canSpot && spottable -> MoneyInput(result.price, { viewModel.setPrice(result.key, it) }, stringResource(R.string.check_shelf_price), placeholder = stringResource(R.string.optional))
            }

            if (!result.done) {
                val main = when {
                    verdict == Verdict.New && canSpot -> MainAction.Spot
                    verdict == Verdict.Failed -> MainAction.Retry
                    else -> null
                }
                val wide = Modifier.fillMaxWidth().height(48.dp)
                when (main) {
                    MainAction.Spot -> Button(onClick = { viewModel.spot(result) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = wide) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.check_log_here))
                        } else {
                            IconLabel(R.drawable.ic_visibility, stringResource(R.string.check_log_here))
                        }
                    }
                    MainAction.Retry -> Button(onClick = { viewModel.retry(result) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = wide) {
                        IconLabel(R.drawable.ic_sync, stringResource(R.string.retry))
                    }
                    null -> Unit
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAdd(null) }, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_add, stringResource(if (verdict == Verdict.Owned) R.string.check_separate_item else R.string.add_to_collection))
                    }
                    if (verdict != Verdict.Wishlist) {
                        OutlinedButton(onClick = { onAdd(DiecastStatus.Wishlist) }, shape = MaterialTheme.shapes.medium) {
                            IconLabel(R.drawable.ic_favorite, stringResource(R.string.status_wishlist))
                        }
                    }
                }
            }
        }
    }
}

/** Brand · series · number · rarity, as read; "Searched by typing" for a typed search. */
@Composable
private fun details(result: CheckResult): String? {
    val fields = result.fields ?: return stringResource(R.string.check_typed).takeIf { result.query != null }
    return listOfNotNull(
        fields.brand,
        fields.series,
        fields.collectionNumber,
        fields.rarity?.takeIf { it != Rarity.Regular }?.let { stringResource(it.label) },
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Note(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Look-alikes in the collection, each with its one-tap actions (web: `MatchList` with actions). */
@Composable
private fun MatchPanel(
    matches: List<DiecastListItem>,
    canSpot: Boolean,
    busy: Boolean,
    onOpen: (Long) -> Unit,
    onAddOne: (DiecastListItem) -> Unit,
    onAcquire: (DiecastListItem) -> Unit,
    onSpot: (DiecastListItem) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, shape)
            .padding(vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.check_in_collection),
            style = MaterialTheme.typography.labelMedium,
            color = Garage.MutedForeground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        matches.forEach { match ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.open_named, match.name)) { onOpen(match.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DiecastPhoto(match.coverFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(match.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val small = MaterialTheme.typography.bodySmall
                    listOfNotNull(match.brand, match.series, match.color).joinToString(" · ").takeIf(String::isNotEmpty)?.let {
                        Text(it, style = small, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // What the shelf price is up against.
                    if (match.status == DiecastStatus.Wishlist && match.targetPrice != null) {
                        Text(stringResource(R.string.scan_target_max, formatMoney(match.targetPrice)), style = small, color = Garage.MutedForeground)
                    }
                    if (match.sightingsCount > 0 && match.sightingsMinPrice != null) {
                        Text(stringResource(R.string.scan_seen_from, formatMoney(match.sightingsMinPrice)), style = small, color = Garage.MutedForeground)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(
                            text = when (match.status) {
                                DiecastStatus.Owned -> stringResource(R.string.owned_times, match.quantity)
                                DiecastStatus.Spotted -> stringResource(R.string.card_spotted_times, match.sightingsCount)
                                else -> stringResource(match.status.label)
                            },
                            background = Garage.Accent,
                            content = Garage.Foreground,
                            icon = match.status.icon(),
                        )
                        Spacer(Modifier.weight(1f))
                        MatchActions(match, canSpot, busy, onAddOne, onAcquire, onSpot)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchActions(
    match: DiecastListItem,
    canSpot: Boolean,
    busy: Boolean,
    onAddOne: (DiecastListItem) -> Unit,
    onAcquire: (DiecastListItem) -> Unit,
    onSpot: (DiecastListItem) -> Unit,
) {
    val small = PaddingValues(horizontal = 12.dp)
    val buyOneMore = stringResource(R.string.check_buy_one_more)
    when (match.status) {
        DiecastStatus.Owned -> OutlinedButton(
            onClick = { onAddOne(match) },
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            contentPadding = small,
            modifier = Modifier.height(36.dp).semantics { contentDescription = buyOneMore },
        ) { Text("+1") }
        DiecastStatus.Wishlist, DiecastStatus.Spotted -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canSpot) {
                OutlinedIconButton(onClick = { onSpot(match) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_visibility), contentDescription = stringResource(R.string.check_log_here), Modifier.size(18.dp))
                }
            }
            Button(onClick = { onAcquire(match) }, enabled = !busy, shape = MaterialTheme.shapes.medium, contentPadding = small, modifier = Modifier.height(36.dp)) {
                IconLabel(R.drawable.ic_check_circle, stringResource(R.string.check_got_it))
            }
        }
        DiecastStatus.Sold -> Unit
    }
}
