package io.github.caropeboka.diecasttracker.ui.edit

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Condition
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.MAX_PHOTOS
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.SCALES
import io.github.caropeboka.diecasttracker.data.ValueSource
import io.github.caropeboka.diecasttracker.data.ai.MAX_SCAN_PHOTOS
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.ChoiceInput
import io.github.caropeboka.diecasttracker.ui.components.DateInput
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.MoneyInput
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SuggestInput
import io.github.caropeboka.diecasttracker.ui.components.TagInput
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.components.icon
import io.github.caropeboka.diecasttracker.ui.places.LocationField
import io.github.caropeboka.diecasttracker.ui.sharedDiecastPage
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import java.time.LocalDate
import kotlinx.coroutines.delay

/** How long a saved diecast may take to load before the page shows a spinner. */
private const val SLOW_LOAD = 400L

private val SECTION_TITLES = mapOf(
    DiecastStatus.Owned to R.string.edit_section_owned,
    DiecastStatus.Wishlist to R.string.edit_section_wishlist,
    DiecastStatus.Spotted to R.string.edit_section_spotted,
    DiecastStatus.Sold to R.string.edit_section_sold,
)

private val STATUS_HINTS = mapOf(
    DiecastStatus.Owned to R.string.edit_hint_owned,
    DiecastStatus.Wishlist to R.string.edit_hint_wishlist,
    DiecastStatus.Spotted to R.string.edit_hint_spotted,
    DiecastStatus.Sold to R.string.edit_hint_sold,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiecastEditScreen(
    onClose: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCatalog: (Long) -> Unit,
    onAiSettings: () -> Unit,
    viewModel: DiecastEditViewModel = viewModel(factory = DiecastEditViewModel.Factory),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val scanEnabled by viewModel.scanEnabled.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

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
                EditEvent.Close -> onClose()
                EditEvent.ScrollToTop -> scroll.animateScrollTo(0)
                EditEvent.OpenCamera -> openCamera()
            }
        }
    }

    val leave = { if (viewModel.isDirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = viewModel.isDirty) { confirmDiscard = true }

    Scaffold(
        // Opened from the collection, the page grows out of the diecast's card.
        modifier = if (viewModel.isNew) Modifier else sharedDiecastPage(viewModel.diecastId),
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(if (viewModel.isNew) R.string.edit_title_new else R.string.edit_title)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!viewModel.isNew && viewModel.existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.edit_delete), tint = Garage.Loss)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { SaveBar(viewModel) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        if (viewModel.loading) {
            // Usually read within a few frames, while the page is still coming in: a spinner only if it takes longer,
            // so it doesn't flash on every opening.
            var slow by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(SLOW_LOAD)
                slow = true
            }
            if (slow) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }

        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusToggle(viewModel.form.status, viewModel::changeStatus)
            PhotoSection(viewModel, scanEnabled, openCamera, onOpen)
            DexSection(viewModel, onOpenCatalog)
            IdentitySection(viewModel, suggestions)
            OwnershipSection(viewModel, suggestions)
            viewModel.existing?.let { existing ->
                SightingsCard(viewModel.sightings, viewModel::openSighting, viewModel::deleteSighting)
                PriceCheckPanel(existing, viewModel::marketValueApplied, onAiSettings)
            }
        }
    }

    SightingSheet(viewModel, suggestions.places)

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.edit_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                }) { Text(stringResource(R.string.discard), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.edit_keep_editing)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named, viewModel.existing?.name.orEmpty())) },
            text = { Text(stringResource(R.string.edit_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.yes_delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Mode first: it decides which ownership fields appear below. */
@Composable
private fun StatusToggle(value: DiecastStatus, onChange: (DiecastStatus) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DiecastStatus.entries.forEach { status ->
            val active = status == value
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (active) {
                            Modifier
                                .background(Garage.Card)
                                .border(1.dp, Garage.Brand.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    )
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onChange(status) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painterResource(status.icon()),
                    contentDescription = null,
                    tint = if (active) Garage.Brand else Garage.MutedForeground,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(status.label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
                )
                Text(
                    stringResource(STATUS_HINTS.getValue(status)),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = Garage.MutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PhotoSection(viewModel: DiecastEditViewModel, scanEnabled: Boolean, onCamera: () -> Unit, onOpen: (Long) -> Unit) {
    val photos = viewModel.form.photos
    val selected = viewModel.selectedPhoto.coerceAtMost((photos.size - 1).coerceAtLeast(0))
    val busy = viewModel.processingPhotos > 0 || viewModel.scanning || viewModel.makingStudio
    val full = photos.size >= MAX_PHOTOS

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) {
        viewModel.addFromGallery(it)
    }

    viewModel.cardCrop?.let { CardCropDialog(it, viewModel) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .background(Garage.Muted)
                .border(1.dp, Garage.Border, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            val current = photos.getOrNull(selected)
            if (current != null) {
                DiecastPhoto(current, stringResource(R.string.edit_photo), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add_photo), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.edit_photos_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (scanEnabled) {
                            stringResource(R.string.edit_photos_hint_scan)
                        } else {
                            stringResource(R.string.edit_photos_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (current != null && !busy && current !in viewModel.studioPhotos) {
                // A pill on the photo itself: it works on the one in view.
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clip(CircleShape)
                        .background(Garage.Background.copy(alpha = 0.78f))
                        .border(1.dp, Garage.Border, CircleShape)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.edit_studio_from_photo)) { viewModel.makeStudioPhoto(selected) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_auto_fix_high), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.studio_photo), style = MaterialTheme.typography.labelLarge, color = Garage.Foreground)
                }
            }

            if (busy) {
                val scanSteps by viewModel.scanProgress.collectAsStateWithLifecycle()
                val studioSteps by viewModel.studioProgress.collectAsStateWithLifecycle()
                Column(
                    Modifier.fillMaxSize().background(Garage.Background.copy(alpha = 0.85f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    val steps = scanSteps ?: studioSteps
                    if (viewModel.processingPhotos == 0 && steps != null) {
                        AiProgressView(steps, framed = false)
                    } else {
                        CircularProgressIndicator()
                        Text(
                            if (viewModel.processingPhotos > 0) pluralStringResource(R.plurals.edit_processing_photos, viewModel.processingPhotos, viewModel.processingPhotos) else stringResource(R.string.edit_ai_reading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(photos, key = { _, name -> name }) { index, name ->
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                2.dp,
                                if (index == selected) Garage.Brand else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable(onClickLabel = stringResource(R.string.edit_view_photo, index + 1)) { viewModel.selectPhoto(index) },
                    ) {
                        DiecastPhoto(name, null, Modifier.fillMaxSize(), placeholderSize = 24.dp)
                        if (index == 0) {
                            Text(
                                stringResource(R.string.cover),
                                color = Color.White,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.edit_photo_count, photos.size, MAX_PHOTOS),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    modifier = Modifier.weight(1f),
                )
                if (selected > 0) {
                    TextButton(onClick = { viewModel.makeCover(selected) }, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_star, stringResource(R.string.edit_make_cover))
                    }
                }
                TextButton(
                    onClick = { viewModel.removePhoto(selected) },
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                ) {
                    IconLabel(R.drawable.ic_delete, stringResource(R.string.edit_delete_photo))
                }
            }
        }

        // A new diecast: every side first, then the AI reads them all at once.
        val beforeScan = scanEnabled && viewModel.isNew && viewModel.scan !is ScanState.Done
        if (beforeScan) {
            ShotGuide(viewModel.form.packaging, photos.count { it !in viewModel.studioPhotos }) { packaging ->
                viewModel.update { it.copy(packaging = packaging) }
                viewModel.fieldEdited(Field.Packaging)
            }
        }
        val readyToScan = beforeScan && photos.isNotEmpty()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cameraLabel = stringResource(if (photos.isEmpty()) R.string.edit_take_photo else R.string.edit_take_another)
            if (readyToScan) {
                OutlinedButton(onClick = onCamera, enabled = !busy && !full, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                    IconLabel(R.drawable.ic_photo_camera, cameraLabel)
                }
            } else {
                Button(onClick = onCamera, enabled = !busy && !full, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                    IconLabel(R.drawable.ic_photo_camera, cameraLabel)
                }
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy && !full,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                IconLabel(R.drawable.ic_photo_library, stringResource(R.string.gallery))
            }
        }

        if (scanEnabled && photos.isNotEmpty()) {
            val label = stringResource(if (viewModel.scan == ScanState.Idle) R.string.edit_scan else R.string.edit_rescan)
            val count = minOf(photos.size, MAX_SCAN_PHOTOS)
            val text = when {
                photos.size > MAX_SCAN_PHOTOS -> stringResource(R.string.edit_scan_first_photos, label, count)
                photos.size > 1 -> stringResource(R.string.edit_scan_photos, label, count)
                else -> label
            }
            if (readyToScan) {
                Button(onClick = viewModel::runScan, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    IconLabel(R.drawable.ic_sparkles, text)
                }
            } else {
                OutlinedButton(onClick = viewModel::runScan, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    IconLabel(R.drawable.ic_sparkles, text)
                }
            }
        }

        viewModel.photoError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
        }

        ScanFeedback(viewModel.scan, onOpen)
    }
}

/**
 * The sides worth a photo before the AI reads them (a blister's back has the series and number, a loose car's base
 * the name and year); ticked off as photos come in. Picking the packaging here sets the form's field.
 */
@Composable
private fun ShotGuide(packaging: Packaging, photoCount: Int, onPackaging: (Packaging) -> Unit) {
    val sides = if (packaging == Packaging.Loose) {
        listOf(R.string.side_front, R.string.side_side, R.string.side_top, R.string.side_bottom)
    } else {
        listOf(R.string.side_front, R.string.side_back)
    }.map { stringResource(it) }
    val shape = MaterialTheme.shapes.medium

    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Card, shape)
            .border(1.dp, Garage.Border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.edit_shot_guide),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Packaging.Carded to R.string.shot_blister, Packaging.Boxed to R.string.packaging_boxed, Packaging.Loose to R.string.packaging_loose).forEach { (option, label) ->
                FilterChip(
                    selected = option == packaging,
                    onClick = { onPackaging(option) },
                    label = { Text(stringResource(label)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Garage.Brand.copy(alpha = 0.18f)),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sides.forEachIndexed { index, side ->
                val taken = index < photoCount
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (taken) {
                        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = stringResource(R.string.edit_side_done), tint = Garage.Gain, modifier = Modifier.size(16.dp))
                    } else {
                        Box(Modifier.size(16.dp).padding(1.dp).border(1.5.dp, Garage.MutedForeground, CircleShape))
                    }
                    Text(side, style = MaterialTheme.typography.bodyMedium, color = if (taken) Garage.Foreground else Garage.MutedForeground)
                }
            }
        }
    }
}

@Composable
private fun DexSection(viewModel: DiecastEditViewModel, onOpenCatalog: (Long) -> Unit) {
    val year = viewModel.form.year
    if (viewModel.isNew) {
        DexCreatePanel(
            chosen = viewModel.dexChosen?.takeIf { it.row.item.id == viewModel.form.catalogItemId },
            suggestion = (viewModel.scan as? ScanState.Done)?.dex,
            year = year,
            onChoose = viewModel::chooseDex,
            onYear = viewModel::useDexYear,
            onOpenCatalog = onOpenCatalog,
        )
    } else {
        val state = viewModel.dexLink ?: return
        DexEditPanel(state, year, viewModel.dexBusy, viewModel::linkDex, viewModel::useDexYear, onOpenCatalog)
    }
}

@Composable
private fun IdentitySection(viewModel: DiecastEditViewModel, suggestions: Suggestions) {
    val form = viewModel.form
    val errors = viewModel.errors
    val ai = viewModel.aiFields

    fun set(field: Field, change: (DiecastForm) -> DiecastForm) {
        viewModel.update(change)
        viewModel.fieldEdited(field)
    }

    SectionCard(stringResource(R.string.edit_identity)) {
        TextInput(
            form.name, { v -> set(Field.Name) { it.copy(name = v) } }, stringResource(R.string.edit_model_name),
            placeholder = "'71 Datsun 510", error = errors[Field.Name], capitalization = KeyboardCapitalization.Words,
            highlighted = Field.Name in ai,
        )
        SuggestInput(
            form.brand, { v -> set(Field.Brand) { it.copy(brand = v) } }, suggestions.brands, stringResource(R.string.field_brand),
            placeholder = "Hot Wheels", error = errors[Field.Brand], highlighted = Field.Brand in ai,
        )
        TextInput(
            form.make, { v -> set(Field.Make) { it.copy(make = v) } }, stringResource(R.string.edit_car_make),
            placeholder = "Nissan", error = errors[Field.Make], capitalization = KeyboardCapitalization.Words,
            highlighted = Field.Make in ai,
        )
        SuggestInput(
            form.series, { v -> set(Field.Series) { it.copy(series = v) } }, suggestions.series, stringResource(R.string.field_series),
            placeholder = "HW J-Imports", error = errors[Field.Series], highlighted = Field.Series in ai,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                form.collectionNumber, { v -> set(Field.CollectionNumber) { it.copy(collectionNumber = v) } }, stringResource(R.string.field_collection_number),
                placeholder = "123/250", error = errors[Field.CollectionNumber], modifier = Modifier.weight(1f),
                highlighted = Field.CollectionNumber in ai,
            )
            TextInput(
                form.color, { v -> set(Field.Color) { it.copy(color = v) } }, stringResource(R.string.field_color),
                placeholder = "Metallic Blue", error = errors[Field.Color], modifier = Modifier.weight(1f),
                capitalization = KeyboardCapitalization.Words, highlighted = Field.Color in ai,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                form.year, { v -> set(Field.Year) { it.copy(year = v.filter(Char::isDigit).take(4)) } }, stringResource(R.string.sort_year),
                placeholder = "${LocalDate.now().year}", error = errors[Field.Year], keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), highlighted = Field.Year in ai,
            )
            SuggestInput(
                form.scale, { v -> set(Field.Scale) { it.copy(scale = v) } }, SCALES, stringResource(R.string.field_scale),
                error = errors[Field.Scale], modifier = Modifier.weight(1f), highlighted = Field.Scale in ai,
            )
        }
        ChoiceInput(
            form.rarity, Rarity.entries, { stringResource(it.label) }, { v -> set(Field.Rarity) { it.copy(rarity = v) } }, stringResource(R.string.field_rarity),
            highlighted = Field.Rarity in ai,
        )
        ChoiceInput(
            form.packaging, Packaging.entries, { stringResource(it.label) }, { v -> set(Field.Packaging) { it.copy(packaging = v) } }, stringResource(R.string.field_packaging),
            highlighted = Field.Packaging in ai,
        )
        ChoiceInput(
            form.condition, listOf<Condition?>(null) + Condition.entries, { it?.let { condition -> stringResource(condition.label) } ?: stringResource(R.string.edit_condition_none) },
            { v -> set(Field.Condition) { it.copy(condition = v) } }, stringResource(R.string.field_condition), highlighted = Field.Condition in ai,
        )
    }
}

@Composable
private fun OwnershipSection(viewModel: DiecastEditViewModel, suggestions: Suggestions) {
    val form = viewModel.form
    val errors = viewModel.errors
    val today = LocalDate.now()

    fun set(field: Field, change: (DiecastForm) -> DiecastForm) {
        viewModel.update(change)
        viewModel.fieldEdited(field)
    }

    SectionCard(stringResource(SECTION_TITLES.getValue(form.status))) {
        if (form.status != DiecastStatus.Spotted) {
            TextInput(
                form.quantity, { v -> set(Field.Quantity) { it.copy(quantity = v.filter(Char::isDigit).take(4)) } }, stringResource(R.string.field_quantity),
                error = errors[Field.Quantity], keyboardType = KeyboardType.Number,
            )
        }

        when {
            form.status == DiecastStatus.Spotted && !viewModel.isNew -> Text(
                stringResource(R.string.edit_sightings_below),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
            form.status == DiecastStatus.Spotted -> {
                LocationField(
                    form.sightingLocation, { place -> set(Field.SightingPlace) { it.withSightingLocation(place) } }, suggestions.places,
                    stringResource(R.string.edit_spotted_at), placeholder = stringResource(R.string.edit_spotted_at_hint), error = errors[Field.SightingPlace],
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateInput(
                        form.sightingDate.toLocalDateOrNull(),
                        { date -> set(Field.SightingDate) { it.copy(sightingDate = (date ?: today).toString()) } },
                        stringResource(R.string.field_date),
                        clearable = false,
                        latest = today,
                        error = errors[Field.SightingDate],
                        modifier = Modifier.weight(1f),
                    )
                    MoneyInput(
                        form.sightingPrice, { v -> set(Field.SightingPrice) { it.copy(sightingPrice = v) } }, stringResource(R.string.edit_price_there),
                        placeholder = stringResource(R.string.optional), error = errors[Field.SightingPrice], modifier = Modifier.weight(1f),
                    )
                }
            }
            form.status == DiecastStatus.Wishlist -> MoneyInput(
                form.targetPrice, { v -> set(Field.TargetPrice) { it.copy(targetPrice = v) } }, stringResource(R.string.edit_target_price),
                placeholder = "500.000", error = errors[Field.TargetPrice],
            )
            else -> {
                MoneyInput(
                    form.purchasePrice, { v -> set(Field.PurchasePrice) { it.copy(purchasePrice = v) } }, stringResource(R.string.edit_purchase_price),
                    placeholder = "35.000", error = errors[Field.PurchasePrice],
                )
                MoneyInput(
                    form.marketValue, { v -> set(Field.MarketValue) { it.copy(marketValue = v) } }, stringResource(R.string.edit_market_value),
                    placeholder = stringResource(R.string.optional), error = errors[Field.MarketValue],
                )
                val existing = viewModel.existing
                if (existing?.marketValueSource == ValueSource.PriceCheck && form.marketValue == existing.marketValue?.toString()) {
                    Text(stringResource(R.string.edit_value_from_check), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                }
                DateInput(
                    form.purchaseDate?.toLocalDateOrNull(),
                    { date -> viewModel.update { it.copy(purchaseDate = date?.toString()) } },
                    stringResource(R.string.field_purchase_date),
                )
                LocationField(
                    form.purchaseLocation, { place -> set(Field.PurchasePlace) { it.withPurchaseLocation(place) } }, suggestions.places,
                    stringResource(R.string.edit_bought_at), placeholder = stringResource(R.string.edit_bought_at_hint), error = errors[Field.PurchasePlace],
                )
                SuggestInput(
                    form.storageLocation, { v -> set(Field.StorageLocation) { it.copy(storageLocation = v) } },
                    suggestions.storageLocations, stringResource(R.string.field_storage_location), placeholder = stringResource(R.string.edit_storage_hint), error = errors[Field.StorageLocation],
                )
            }
        }

        TagInput(
            form.tags, { tags -> set(Field.Tags) { it.copy(tags = tags) } }, suggestions.tags, error = errors[Field.Tags],
        )
        TextInput(
            form.notes, { v -> set(Field.Notes) { it.copy(notes = v) } }, stringResource(R.string.field_notes),
            placeholder = stringResource(R.string.edit_notes_hint), error = errors[Field.Notes],
            singleLine = false, minLines = 3, highlighted = Field.Notes in viewModel.aiFields,
        )
    }
}

@Composable
private fun SaveBar(viewModel: DiecastEditViewModel) {
    val disabled = viewModel.saving || viewModel.processingPhotos > 0 || viewModel.loading || viewModel.scanning

    Surface(color = Garage.Card, border = BorderStroke(1.dp, Garage.Border)) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // From the bulk scan queue the next one is waiting there, not in a blank form.
            if (viewModel.isNew && !viewModel.fromQueue) {
                OutlinedButton(
                    onClick = { viewModel.save(addAnother = true) },
                    enabled = !disabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.edit_save_add_another), maxLines = 1)
                }
            }
            Button(
                onClick = { viewModel.save(addAnother = false) },
                enabled = !disabled,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                if (viewModel.saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(if (viewModel.isNew) R.string.save else R.string.save_changes))
                }
            }
        }
    }
}
