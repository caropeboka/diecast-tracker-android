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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
import io.github.caropeboka.diecasttracker.data.ai.MAX_SCAN_PHOTOS
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
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import java.time.LocalDate

private val SECTION_TITLES = mapOf(
    DiecastStatus.Owned to "Kepemilikan & harga",
    DiecastStatus.Wishlist to "Buruan & target harga",
    DiecastStatus.Spotted to "Temuan",
    DiecastStatus.Sold to "Riwayat & harga",
)

private val STATUS_HINTS = mapOf(
    DiecastStatus.Owned to "Sudah di garasi",
    DiecastStatus.Wishlist to "Masih diburu",
    DiecastStatus.Spotted to "Pernah lihat",
    DiecastStatus.Sold to "Pernah punya",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiecastEditScreen(
    onClose: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCatalog: (Long) -> Unit,
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
        topBar = {
            TopAppBar(
                title = { Heading(if (viewModel.isNew) "Tambah diecast" else "Edit diecast") },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (!viewModel.isNew && viewModel.existing != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Hapus diecast", tint = Garage.Loss)
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
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
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
            if (viewModel.existing != null) {
                SightingsCard(viewModel.sightings, viewModel::openSighting, viewModel::deleteSighting)
            }
        }
    }

    SightingSheet(viewModel, suggestions.places)

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Buang perubahan?") },
            text = { Text("Isian dan foto yang baru ditambahkan tidak akan disimpan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                }) { Text("Buang", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Lanjut mengisi") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus ${viewModel.existing?.name}?") },
            text = { Text("Foto, tag, dan catatan temuannya ikut terhapus. Ini tidak bisa dibatalkan.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Ya, hapus", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
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
                    status.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
                )
                Text(
                    STATUS_HINTS.getValue(status),
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
    val busy = viewModel.processingPhotos > 0 || viewModel.scanning
    val full = photos.size >= MAX_PHOTOS

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) {
        viewModel.addFromGallery(it)
    }

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
                DiecastPhoto(current, "Foto diecast", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add_photo), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(40.dp))
                    Text("Foto diecast kamu", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (scanEnabled) {
                            "Foto depan kartunya agar AI bisa membaca nama, seri, dan nomornya. Bisa tambah foto belakang kartu juga — foto pertama jadi cover."
                        } else {
                            "Foto depan kartunya. Bisa tambah foto belakang kartu atau mobilnya juga — foto pertama jadi cover."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (busy) {
                Column(
                    Modifier.fillMaxSize().background(Garage.Background.copy(alpha = 0.8f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    CircularProgressIndicator()
                    Text(
                        if (viewModel.processingPhotos > 0) "Memproses ${viewModel.processingPhotos} foto…" else "AI sedang membaca foto…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                            .clickable(onClickLabel = "Lihat foto ${index + 1}") { viewModel.selectPhoto(index) },
                    ) {
                        DiecastPhoto(name, null, Modifier.fillMaxSize(), placeholderSize = 24.dp)
                        if (index == 0) {
                            Text(
                                "Cover",
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
                    "${photos.size}/$MAX_PHOTOS foto",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    modifier = Modifier.weight(1f),
                )
                if (selected > 0) {
                    TextButton(onClick = { viewModel.makeCover(selected) }, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_star, "Jadikan cover")
                    }
                }
                TextButton(
                    onClick = { viewModel.removePhoto(selected) },
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                ) {
                    IconLabel(R.drawable.ic_delete, "Hapus foto")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCamera,
                enabled = !busy && !full,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                IconLabel(R.drawable.ic_photo_camera, if (photos.isEmpty()) "Ambil foto" else "Foto lagi")
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy && !full,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                IconLabel(R.drawable.ic_photo_library, "Galeri")
            }
        }

        if (scanEnabled && photos.isNotEmpty()) {
            OutlinedButton(
                onClick = viewModel::runScan,
                enabled = !busy,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val label = if (viewModel.scan == ScanState.Idle) "Isi otomatis dengan AI" else "Baca ulang dengan AI"
                IconLabel(R.drawable.ic_sparkles, if (photos.size > 1) "$label (${minOf(photos.size, MAX_SCAN_PHOTOS)} foto)" else label)
            }
        }

        viewModel.photoError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
        }

        ScanFeedback(viewModel.scan, onOpen)
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

    SectionCard("Identitas") {
        TextInput(
            form.name, { v -> set(Field.Name) { it.copy(name = v) } }, "Nama model *",
            placeholder = "'71 Datsun 510", error = errors[Field.Name], capitalization = KeyboardCapitalization.Words,
            highlighted = Field.Name in ai,
        )
        SuggestInput(
            form.brand, { v -> set(Field.Brand) { it.copy(brand = v) } }, suggestions.brands, "Merek",
            placeholder = "Hot Wheels", error = errors[Field.Brand], highlighted = Field.Brand in ai,
        )
        TextInput(
            form.make, { v -> set(Field.Make) { it.copy(make = v) } }, "Pabrikan mobil",
            placeholder = "Nissan", error = errors[Field.Make], capitalization = KeyboardCapitalization.Words,
            highlighted = Field.Make in ai,
        )
        SuggestInput(
            form.series, { v -> set(Field.Series) { it.copy(series = v) } }, suggestions.series, "Seri",
            placeholder = "HW J-Imports", error = errors[Field.Series], highlighted = Field.Series in ai,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                form.collectionNumber, { v -> set(Field.CollectionNumber) { it.copy(collectionNumber = v) } }, "Nomor koleksi",
                placeholder = "123/250", error = errors[Field.CollectionNumber], modifier = Modifier.weight(1f),
                highlighted = Field.CollectionNumber in ai,
            )
            TextInput(
                form.color, { v -> set(Field.Color) { it.copy(color = v) } }, "Warna",
                placeholder = "Metallic Blue", error = errors[Field.Color], modifier = Modifier.weight(1f),
                capitalization = KeyboardCapitalization.Words, highlighted = Field.Color in ai,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(
                form.year, { v -> set(Field.Year) { it.copy(year = v.filter(Char::isDigit).take(4)) } }, "Tahun rilis",
                placeholder = "${LocalDate.now().year}", error = errors[Field.Year], keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f), highlighted = Field.Year in ai,
            )
            SuggestInput(
                form.scale, { v -> set(Field.Scale) { it.copy(scale = v) } }, SCALES, "Skala",
                error = errors[Field.Scale], modifier = Modifier.weight(1f), highlighted = Field.Scale in ai,
            )
        }
        ChoiceInput(
            form.rarity, Rarity.entries, Rarity::label, { v -> set(Field.Rarity) { it.copy(rarity = v) } }, "Kelangkaan",
            highlighted = Field.Rarity in ai,
        )
        ChoiceInput(
            form.packaging, Packaging.entries, Packaging::label, { v -> set(Field.Packaging) { it.copy(packaging = v) } }, "Kemasan",
            highlighted = Field.Packaging in ai,
        )
        ChoiceInput(
            form.condition, listOf<Condition?>(null) + Condition.entries, { it?.label ?: "Belum dinilai" },
            { v -> set(Field.Condition) { it.copy(condition = v) } }, "Kondisi", highlighted = Field.Condition in ai,
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

    SectionCard(SECTION_TITLES.getValue(form.status)) {
        if (form.status != DiecastStatus.Spotted) {
            TextInput(
                form.quantity, { v -> set(Field.Quantity) { it.copy(quantity = v.filter(Char::isDigit).take(4)) } }, "Jumlah",
                error = errors[Field.Quantity], keyboardType = KeyboardType.Number,
            )
        }

        when {
            form.status == DiecastStatus.Spotted && !viewModel.isNew -> Text(
                "Tempat, tanggal, dan harga setiap kali ditemui ada di Catatan temuan di bawah.",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
            form.status == DiecastStatus.Spotted -> {
                LocationField(
                    form.sightingLocation, { place -> set(Field.SightingPlace) { it.withSightingLocation(place) } }, suggestions.places,
                    "Ditemui di", placeholder = "Toys Kingdom GI, teman, event…", error = errors[Field.SightingPlace],
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateInput(
                        form.sightingDate.toLocalDateOrNull(),
                        { date -> set(Field.SightingDate) { it.copy(sightingDate = (date ?: today).toString()) } },
                        "Tanggal",
                        clearable = false,
                        latest = today,
                        error = errors[Field.SightingDate],
                        modifier = Modifier.weight(1f),
                    )
                    MoneyInput(
                        form.sightingPrice, { v -> set(Field.SightingPrice) { it.copy(sightingPrice = v) } }, "Harga di sana",
                        placeholder = "Opsional", error = errors[Field.SightingPrice], modifier = Modifier.weight(1f),
                    )
                }
            }
            form.status == DiecastStatus.Wishlist -> MoneyInput(
                form.targetPrice, { v -> set(Field.TargetPrice) { it.copy(targetPrice = v) } }, "Target harga maksimal",
                placeholder = "500.000", error = errors[Field.TargetPrice],
            )
            else -> {
                MoneyInput(
                    form.purchasePrice, { v -> set(Field.PurchasePrice) { it.copy(purchasePrice = v) } }, "Harga beli (per pcs)",
                    placeholder = "35.000", error = errors[Field.PurchasePrice],
                )
                MoneyInput(
                    form.marketValue, { v -> set(Field.MarketValue) { it.copy(marketValue = v) } }, "Harga pasaran sekarang (per pcs)",
                    placeholder = "Opsional", error = errors[Field.MarketValue],
                )
                DateInput(
                    form.purchaseDate?.toLocalDateOrNull(),
                    { date -> viewModel.update { it.copy(purchaseDate = date?.toString()) } },
                    "Tanggal beli",
                )
                LocationField(
                    form.purchaseLocation, { place -> set(Field.PurchasePlace) { it.withPurchaseLocation(place) } }, suggestions.places,
                    "Beli di", placeholder = "Toko / marketplace / teman", error = errors[Field.PurchasePlace],
                )
                SuggestInput(
                    form.storageLocation, { v -> set(Field.StorageLocation) { it.copy(storageLocation = v) } },
                    suggestions.storageLocations, "Lokasi simpan", placeholder = "Rak A / Box 3", error = errors[Field.StorageLocation],
                )
            }
        }

        TagInput(
            form.tags, { tags -> set(Field.Tags) { it.copy(tags = tags) } }, suggestions.tags, error = errors[Field.Tags],
        )
        TextInput(
            form.notes, { v -> set(Field.Notes) { it.copy(notes = v) } }, "Catatan",
            placeholder = "Varian, kondisi kartu, cerita dapetinnya…", error = errors[Field.Notes],
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
            if (viewModel.isNew) {
                OutlinedButton(
                    onClick = { viewModel.save(addAnother = true) },
                    enabled = !disabled,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    Text("Simpan & tambah lagi", maxLines = 1)
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
                    Text(if (viewModel.isNew) "Simpan" else "Simpan perubahan")
                }
            }
        }
    }
}
