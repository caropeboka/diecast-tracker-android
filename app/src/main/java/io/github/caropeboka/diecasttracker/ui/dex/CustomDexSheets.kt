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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
            IconLabel(R.drawable.ic_add, "Tambah entri")
        }
        OutlinedButton(onClick = { viewModel.openImport(true) }, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
            IconLabel(R.drawable.ic_view_list, "Isi banyak")
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
        title = if (isNew) "Buat dex sendiri" else "Ubah dex",
        subtitle = if (isNew) "Untuk merek yang tidak punya daftar di wiki (Mini GT, Tarmac, Inno64, Roll Speed…)." else null,
        onDismiss = onDismiss,
    ) {
        SuggestInput(
            draft.brand, { v -> onChange(CatalogField.Brand) { it.copy(brand = v) } }, brands, "Merek *",
            placeholder = "Mini GT", error = errors[CatalogField.Brand],
        )
        TextInput(
            draft.name, { v -> onChange(CatalogField.Name) { it.copy(name = v) } }, "Nama dex *",
            placeholder = "Mini GT 2026, Roll Speed Polisi…", error = errors[CatalogField.Name], capitalization = KeyboardCapitalization.Words,
        )
        TextInput(
            draft.year, { v -> onChange(CatalogField.Year) { it.copy(year = v.filter(Char::isDigit).take(4)) } }, "Tahun (opsional)",
            error = errors[CatalogField.Year], keyboardType = KeyboardType.Number,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Batal") }
            Button(onClick = onSave, shape = MaterialTheme.shapes.medium) {
                if (isNew) IconLabel(R.drawable.ic_add, "Buat dex") else Text("Simpan")
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
        title = if (draft.isNew) "Tambah entri" else "Ubah entri",
        subtitle = if (draft.isNew) "Satu rilisan. Nomor yang sama dengan varian berbeda jadi varian dari entri itu." else null,
        onDismiss = viewModel::closeEntry,
    ) {
        viewModel.lastAdded?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
                Text("$it Lanjut entri berikutnya, atau Selesai.", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextInput(
            row.number.orEmpty(), { v -> set(RowField.Number) { it.copy(number = v) } }, "Nomor / kode",
            placeholder = if (draft.isNew) "MGT00512 (kosongkan = urut otomatis)" else null,
            error = errors[RowField.Number], capitalization = KeyboardCapitalization.Characters,
        )
        TextInput(
            row.name, { v -> set(RowField.Name) { it.copy(name = v) } }, "Nama model *",
            placeholder = "Nissan Skyline GT-R (R34)", error = errors[RowField.Name], capitalization = KeyboardCapitalization.Words,
        )
        SuggestInput(
            row.series.orEmpty(), { v -> set(RowField.Series) { it.copy(series = v) } }, seriesSuggestions, "Seri",
            placeholder = "Opsional", error = errors[RowField.Series],
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(
                row.seriesNumber.orEmpty(), { v -> set(RowField.SeriesNumber) { it.copy(seriesNumber = v) } }, "Nomor seri",
                placeholder = "mis. 3/5", error = errors[RowField.SeriesNumber], modifier = Modifier.weight(1f),
            )
            TextInput(
                row.variantLabel.orEmpty(), { v -> set(RowField.Variant) { it.copy(variantLabel = v) } }, "Varian / warna",
                placeholder = "Bayside Blue, LHD…", error = errors[RowField.Variant], modifier = Modifier.weight(1.4f),
                capitalization = KeyboardCapitalization.Words,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = MaterialTheme.shapes.medium
            val photoModifier = Modifier.size(88.dp).clip(shape).border(1.dp, Garage.Border, shape)
            val current = draft.currentPhoto?.takeIf { !draft.removePhoto }
            Box(contentAlignment = Alignment.Center) {
                when {
                    draft.photo != null -> DiecastPhoto(draft.photo, "Foto entri", photoModifier, placeholderSize = 32.dp)
                    else -> DexImage(current, photoModifier, placeholderSize = 32.dp)
                }
                if (viewModel.processingPhoto) CircularProgressIndicator(Modifier.size(28.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Foto (opsional)", style = MaterialTheme.typography.bodyMedium)
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
                    ) { IconLabel(R.drawable.ic_photo_camera, "Kamera") }
                    TextButton(
                        onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_library, "Galeri") }
                }
                if (draft.photo != null || current != null) {
                    TextButton(onClick = viewModel::removeEntryPhoto, shape = MaterialTheme.shapes.medium) {
                        Text("Hapus foto", color = Garage.Loss)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = viewModel::closeEntry, shape = MaterialTheme.shapes.medium) { Text(if (draft.isNew) "Selesai" else "Batal") }
            Button(
                onClick = viewModel::saveEntry,
                enabled = !viewModel.savingEntry && !viewModel.processingPhoto,
                shape = MaterialTheme.shapes.medium,
            ) {
                if (draft.isNew) IconLabel(R.drawable.ic_add, "Tambah") else Text("Simpan")
            }
        }
    }
}

/** "Isi banyak sekaligus": a checklist read by the AI, a spreadsheet, or the collection (web: `ImportDialog`). */
@Composable
fun ImportSheet(viewModel: CustomDexViewModel, brand: String, onAiSettings: () -> Unit) {
    if (!viewModel.importing) return
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val busy = viewModel.busy

    val screenshots = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_CHECKLIST_IMAGES), viewModel::readChecklist)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onChecklistCamera)
    val sheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importSpreadsheet) }

    DexSheet(
        title = "Isi banyak sekaligus",
        subtitle = "Entri yang sudah ada tidak digandakan.",
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
            title = "Dari screenshot checklist (AI)",
            text = if (aiEnabled) {
                "Screenshot daftar rilisan (post checklist, katalog, pre-order toko), maks. $MAX_CHECKLIST_IMAGES, atau foto lembar " +
                    "checklist. AI yang mengetik daftarnya; kamu periksa dulu sebelum ditambahkan."
            } else {
                "AI belum aktif. Isi API key Gemini di Pengaturan AI."
            },
            busy = busy == ImportBusy.Checklist,
        ) {
            if (aiEnabled) {
                Row {
                    TextButton(
                        onClick = { screenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = busy == null,
                        shape = MaterialTheme.shapes.medium,
                    ) { IconLabel(R.drawable.ic_photo_library, "Pilih screenshot") }
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
                    ) { IconLabel(R.drawable.ic_photo_camera, "Foto") }
                }
            } else {
                TextButton(onClick = onAiSettings, shape = MaterialTheme.shapes.medium) { Text("Buka Pengaturan AI") }
            }
        }
        if (busy == ImportBusy.Checklist) {
            Text("AI sedang membaca checklist… bisa sampai satu menit.", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
        }

        ImportOption(
            icon = R.drawable.ic_grid_view,
            title = "Dari Excel / CSV",
            text = "Baris pertama berisi judul kolom: Nomor, Nama, Seri, Nomor seri, Varian. Minimal kolom Nama. Hanya sheet pertama yang dibaca.",
            busy = busy == ImportBusy.Spreadsheet,
            onClick = { if (busy == null) sheet.launch(SPREADSHEET_TYPES) },
        )

        ImportOption(
            icon = R.drawable.ic_car,
            title = "Dari koleksiku",
            text = "Semua diecast $brand di koleksimu jadi entri, langsung tertaut (dengan fotonya).",
            busy = busy == ImportBusy.Collection,
            onClick = { if (busy == null) viewModel.fromCollection() },
        )
    }
}

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
            SubHeading("Periksa hasil AI")
            Text(
                "AI membaca ${review.rows.size} rilisan. Hapus centang yang salah baca; yang keliru sedikit bisa diubah setelah ditambahkan.",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
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
                            val details = listOfNotNull(row.series, row.seriesNumber, row.variantLabel, "sudah ada di dex".takeIf { known })
                            if (details.isNotEmpty()) {
                                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                TextButton(onClick = viewModel::closeReview, shape = MaterialTheme.shapes.medium) { Text("Batal") }
                Button(onClick = viewModel::confirmReview, enabled = count > 0 && viewModel.busy == null, shape = MaterialTheme.shapes.medium) {
                    IconLabel(R.drawable.ic_add, "Tambahkan $count entri")
                }
            }
        }
    }
}
