package io.github.caropeboka.diecasttracker.ui.dex

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.dex.CatalogField
import io.github.caropeboka.diecasttracker.data.dex.DexRow
import io.github.caropeboka.diecasttracker.data.dex.MAX_CHECKLIST_IMAGES
import io.github.caropeboka.diecasttracker.data.dex.RowField
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.SuggestInput
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** "Tambah entri" and "Isi banyak sekaligus" on an own dex. */
@Composable
fun CustomDexTools(viewModel: CustomDexViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::addEntry, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
            IconLabel(R.drawable.ic_add, stringResource(R.string.dex_add_entry))
        }
        OutlinedButton(onClick = { viewModel.openImport(true) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
            IconLabel(R.drawable.ic_view_list, stringResource(R.string.dex_fill_many))
        }
    }
}

/** A sheet with a title, scrolling content, above the keyboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                SubHeading(title)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
            }
            content()
        }
    }
}

/** Creating or renaming an own dex (web: `CreateDexPanel`). */
@Composable
fun CatalogSheet(
    draft: CatalogDraft,
    errors: Map<CatalogField, String>,
    brands: List<String>,
    onChange: (CatalogField, (CatalogDraft) -> CatalogDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = draft.id == 0L
    DexSheet(
        title = stringResource(if (isNew) R.string.dex_own_create else R.string.dex_edit),
        subtitle = if (isNew) stringResource(R.string.dex_create_own_hint) else null,
        onDismiss = onDismiss,
    ) {
        SuggestInput(
            draft.brand, { v -> onChange(CatalogField.Brand) { it.copy(brand = v) } }, brands, stringResource(R.string.field_brand) + " *",
            placeholder = "Mini GT", error = errors[CatalogField.Brand],
        )
        TextInput(
            draft.name, { v -> onChange(CatalogField.Name) { it.copy(name = v) } }, stringResource(R.string.dex_name_label),
            placeholder = stringResource(R.string.dex_name_placeholder), error = errors[CatalogField.Name], capitalization = KeyboardCapitalization.Words,
        )
        TextInput(
            draft.year, { v -> onChange(CatalogField.Year) { it.copy(year = v.filter(Char::isDigit).take(4)) } }, stringResource(R.string.dex_year_optional),
            error = errors[CatalogField.Year], keyboardType = KeyboardType.Number,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onSave, shape = MaterialTheme.shapes.medium) {
                if (isNew) IconLabel(R.drawable.ic_add, stringResource(R.string.dex_create)) else Text(stringResource(R.string.save))
            }
        }
    }
}

/** One release by hand, or corrected (web: `AddItemDialog`, plus editing). */
@Composable
fun EntryEditorSheet(viewModel: CustomDexViewModel, seriesSuggestions: List<String>) {
    val draft = viewModel.draft ?: return
    val row = draft.row
    val errors = viewModel.draftErrors

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onEntryCamera)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::pickEntryPhoto) }

    fun set(field: RowField, change: (DexRow) -> DexRow) = viewModel.updateDraft(field, change)

    DexSheet(
        title = stringResource(if (draft.isNew) R.string.dex_add_entry else R.string.dex_edit_entry),
        subtitle = if (draft.isNew) stringResource(R.string.dex_add_entry_hint) else null,
        onDismiss = viewModel::closeEntry,
    ) {
        viewModel.lastAdded?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.dex_added_next, it), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextInput(
            row.number.orEmpty(), { v -> set(RowField.Number) { it.copy(number = v) } }, stringResource(R.string.dex_number_code),
            placeholder = if (draft.isNew) stringResource(R.string.dex_number_placeholder) else null,
            error = errors[RowField.Number], capitalization = KeyboardCapitalization.Characters,
        )
        TextInput(
            row.name, { v -> set(RowField.Name) { it.copy(name = v) } }, stringResource(R.string.edit_model_name),
            placeholder = "Nissan Skyline GT-R (R34)", error = errors[RowField.Name], capitalization = KeyboardCapitalization.Words,
        )
        SuggestInput(
            row.series.orEmpty(), { v -> set(RowField.Series) { it.copy(series = v) } }, seriesSuggestions, stringResource(R.string.field_series),
            placeholder = stringResource(R.string.optional), error = errors[RowField.Series],
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(
                row.seriesNumber.orEmpty(), { v -> set(RowField.SeriesNumber) { it.copy(seriesNumber = v) } }, stringResource(R.string.dex_series_number),
                placeholder = stringResource(R.string.dex_series_number_placeholder), error = errors[RowField.SeriesNumber], modifier = Modifier.weight(1f),
            )
            TextInput(
                row.variantLabel.orEmpty(), { v -> set(RowField.Variant) { it.copy(variantLabel = v) } }, stringResource(R.string.dex_variant_colour),
                placeholder = stringResource(R.string.dex_variant_placeholder), error = errors[RowField.Variant], modifier = Modifier.weight(1.4f),
                capitalization = KeyboardCapitalization.Words,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = MaterialTheme.shapes.medium
            val photoModifier = Modifier.size(88.dp).clip(shape).border(1.dp, Garage.Border, shape)
            val current = draft.currentPhoto?.takeIf { !draft.removePhoto }
            Box(contentAlignment = Alignment.Center) {
                when {
                    draft.photo != null -> DiecastPhoto(draft.photo, stringResource(R.string.dex_entry_photo), photoModifier, placeholderSize = 32.dp)
                    else -> DexImage(current, photoModifier, placeholderSize = 32.dp)
                }
                if (viewModel.processingPhoto) CircularProgressIndicator(Modifier.size(28.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.dex_photo_optional), style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(
                        onClick = {
                            try {
                                camera.launch(viewModel.cameraTarget(CustomDexViewModel.ENTRY_CAMERA))
                            } catch (_: ActivityNotFoundException) {
                                viewModel.cameraUnavailable(CustomDexViewModel.ENTRY_CAMERA)
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_camera, stringResource(R.string.camera)) }
                    TextButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_library, stringResource(R.string.gallery)) }
                }
                if (draft.photo != null || current != null) {
                    TextButton(onClick = viewModel::removeEntryPhoto, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.edit_delete_photo), color = Garage.Loss)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = viewModel::closeEntry, shape = MaterialTheme.shapes.medium) { Text(stringResource(if (draft.isNew) R.string.done else R.string.cancel)) }
            Button(
                onClick = viewModel::saveEntry,
                enabled = !viewModel.savingEntry && !viewModel.processingPhoto,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (draft.isNew) IconLabel(R.drawable.ic_add, stringResource(R.string.add)) else Text(stringResource(R.string.save))
            }
        }
    }
}

/** "Isi banyak sekaligus": a checklist read by the AI, a spreadsheet, or the collection (web: `ImportDialog`). */
@Composable
fun ImportSheet(viewModel: CustomDexViewModel, brand: String, onAiSettings: () -> Unit) {
    if (!viewModel.importing) return
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val checklistProgress by viewModel.checklistProgress.collectAsStateWithLifecycle()
    val busy = viewModel.busy

    val screenshots = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_CHECKLIST_IMAGES), viewModel::readChecklist)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onChecklistCamera)
    val sheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importSpreadsheet) }

    DexSheet(
        title = stringResource(R.string.dex_fill_many_title),
        subtitle = stringResource(R.string.dex_fill_many_hint),
        onDismiss = { if (busy == null) viewModel.openImport(false) },
    ) {
        viewModel.importNote?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Garage.Brand2, modifier = Modifier.size(18.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        ImportOption(
            icon = R.drawable.ic_sparkles,
            title = stringResource(R.string.dex_from_checklist),
            text = if (aiEnabled) {
                stringResource(R.string.dex_from_checklist_text, MAX_CHECKLIST_IMAGES)
            } else {
                stringResource(R.string.ai_err_not_set)
            },
            busy = busy == ImportBusy.Checklist,
        ) {
            if (aiEnabled) {
                Row {
                    TextButton(
                        onClick = { screenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = busy == null,
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_library, stringResource(R.string.dex_pick_screenshots)) }
                    TextButton(
                        onClick = {
                            try {
                                camera.launch(viewModel.cameraTarget(CustomDexViewModel.CHECKLIST_CAMERA))
                            } catch (_: ActivityNotFoundException) {
                                viewModel.cameraUnavailable(CustomDexViewModel.CHECKLIST_CAMERA)
                            }
                        },
                        enabled = busy == null,
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_camera, stringResource(R.string.photo)) }
                }
            } else {
                TextButton(onClick = onAiSettings, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.dex_open_ai_settings)) }
            }
        }

        ImportOption(
            icon = R.drawable.ic_search,
            title = stringResource(R.string.dex_from_web),
            text = if (aiEnabled) stringResource(R.string.dex_from_web_text) else stringResource(R.string.ai_err_not_set),
            busy = busy == ImportBusy.Web,
        ) {
            if (aiEnabled) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(
                        value = viewModel.webQuery,
                        onValueChange = viewModel::updateWebQuery,
                        placeholder = { Text(stringResource(R.string.dex_web_query_hint, brand), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        enabled = busy == null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.searchWeb() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::searchWeb, enabled = busy == null, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.dex_web_search))
                    }
                }
            } else {
                TextButton(onClick = onAiSettings, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.dex_open_ai_settings)) }
            }
        }
        checklistProgress?.let { AiProgressView(it) }

        ImportOption(
            icon = R.drawable.ic_grid_view,
            title = stringResource(R.string.dex_from_sheet),
            text = stringResource(R.string.dex_from_sheet_text),
            busy = busy == ImportBusy.Spreadsheet,
            onClick = { if (busy == null) sheet.launch(SPREADSHEET_TYPES) },
        )

        ImportOption(
            icon = R.drawable.ic_car,
            title = stringResource(R.string.dex_from_collection),
            text = stringResource(R.string.dex_from_collection_text, brand),
            busy = busy == ImportBusy.Collection,
            onClick = { if (busy == null) viewModel.fromCollection() },
        )
    }
}

/** What the AI said about the list it found, a warning to check it, and where it looked. */
@Composable
private fun WebFindNote(web: WebFind) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (web.note.isNotEmpty()) Text(web.note, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Garage.Brand2, modifier = Modifier.size(16.dp))
            Text(
                stringResource(if (web.searched) R.string.dex_web_by_search else R.string.dex_web_by_memory, web.by),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
        if (web.sources.isNotEmpty()) {
            Text(stringResource(R.string.dex_web_sources), style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground)
            web.sources.take(MAX_SOURCES).forEach { source ->
                Text(
                    "↗ " + source.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.Brand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { runCatching { uriHandler.openUri(source.url) } }
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

private const val MAX_SOURCES = 5

private val SPREADSHEET_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "text/csv",
    "text/comma-separated-values",
    "text/plain",
    "application/csv",
    "application/vnd.ms-excel",
)

@Composable
private fun ImportOption(
    @DrawableRes icon: Int,
    title: String,
    text: String,
    busy: Boolean,
    onClick: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Garage.Border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(Garage.Brand.copy(alpha = 0.12f), MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            actions?.invoke()
        }
    }
}

/** What the AI read, ticked before it goes into the dex. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistReviewSheet(viewModel: CustomDexViewModel) {
    val review = viewModel.review ?: return
    val count = review.selected.size

    ModalBottomSheet(onDismissRequest = viewModel::closeReview, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SubHeading(stringResource(R.string.dex_review_title))
            Text(
                pluralStringResource(R.plurals.dex_checklist_review, review.rows.size, review.rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
            review.web?.let { WebFindNote(it) }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(review.rows) { index, row ->
                    val known = review.known.getOrElse(index) { false }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = !known) { viewModel.toggleReview(index) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = index in review.selected, onCheckedChange = { viewModel.toggleReview(index) }, enabled = !known)
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(row.number?.let { "#$it" }, row.name).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val details = listOfNotNull(row.series, row.seriesNumber, row.variantLabel, stringResource(R.string.dex_already_there).takeIf { known })
                            if (details.isNotEmpty()) {
                                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = viewModel::closeReview, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.cancel)) }
                Button(onClick = viewModel::confirmReview, enabled = count > 0 && viewModel.busy == null, shape = MaterialTheme.shapes.medium) {
                    IconLabel(R.drawable.ic_add, pluralStringResource(R.plurals.dex_add_count, count, count))
                }
            }
        }
    }
}
