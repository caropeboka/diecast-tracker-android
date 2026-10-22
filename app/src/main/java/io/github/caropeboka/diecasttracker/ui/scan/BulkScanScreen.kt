package io.github.caropeboka.diecasttracker.ui.scan

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.ai.Confidence
import io.github.caropeboka.diecasttracker.data.scan.ScanItemStatus
import io.github.caropeboka.diecasttracker.data.scan.ScanWorkState
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
import io.github.caropeboka.diecasttracker.ui.edit.Matches
import io.github.caropeboka.diecasttracker.ui.edit.Suggestions
import io.github.caropeboka.diecasttracker.ui.edit.toLocalDateOrNull
import io.github.caropeboka.diecasttracker.ui.places.LocationField
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.delay
import java.time.LocalDate

/** The statuses a queue can be saved as (web: Dimiliki · Wishlist · Ditemui). */
private val SAVE_AS = listOf(DiecastStatus.Owned, DiecastStatus.Wishlist, DiecastStatus.Spotted)

/**
 * "Scan massal" (web: `diecasts/bulk.tsx`): many photos at once, one diecast each. The AI reads them in the background;
 * the collector checks each card, fixes what's needed and saves, or saves everything the AI was sure of at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkScanScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onComplete: (Long) -> Unit,
    onAiSettings: () -> Unit,
    viewModel: BulkScanViewModel = viewModel(factory = BulkScanViewModel.Factory),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val work by viewModel.work.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onCameraResult)
    val openCamera = {
        try {
            camera.launch(viewModel.cameraTarget())
        } catch (_: ActivityNotFoundException) {
            viewModel.cameraUnavailable()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK), viewModel::addFromGallery)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                BulkScanEvent.OpenCamera -> openCamera()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Scan massal") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val all = rows
        if (all == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!enabled) {
                item(key = "ai") {
                    Notice(Garage.Brand2) {
                        Text("Scan AI belum aktif. Foto tetap masuk antrean dan dibaca begitu API key diisi.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onAiSettings, contentPadding = PaddingValues(0.dp)) { Text("Buka Pengaturan AI →") }
                    }
                }
            }

            item(key = "add") {
                AddPhotos(
                    adding = viewModel.adding,
                    onCamera = openCamera,
                    onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }

            if (all.isNotEmpty()) {
                item(key = "progress") {
                    Progress(
                        rows = all,
                        work = work,
                        paused = paused,
                        enabled = enabled,
                        savingAll = viewModel.savingAll,
                        onPause = viewModel::setPaused,
                        onSaveAll = viewModel::saveAll,
                    )
                }
                item(key = "defaults") { DefaultsCard(viewModel, suggestions) }
                items(all, key = { it.item.id }) { row ->
                    QueueCard(
                        row = row,
                        reading = (work as? ScanWorkState.Reading)?.itemId == row.item.id,
                        edits = viewModel.edits[row.item.id] ?: QuickEdits(),
                        status = viewModel.defaults.status,
                        busy = row.item.id in viewModel.busy,
                        onEdit = { change -> viewModel.edit(row.item.id, change) },
                        onSave = { viewModel.save(row) },
                        onComplete = { onComplete(row.item.id) },
                        onRetry = { viewModel.retry(row.item.id) },
                        onDiscard = { viewModel.discard(row.item.id) },
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/** At most this many photos per pick; more can be added again. */
private const val MAX_PICK = 50

@Composable
private fun Notice(color: Color, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) { content() }
    }
}

@Composable
private fun AddPhotos(adding: Int, onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Satu foto = satu diecast. Foto depan kartunya dengan cahaya terang. Kamera terbuka lagi setelah tiap foto " +
                "sampai kamu menekan Kembali.",
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCamera, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                IconLabel(R.drawable.ic_photo_camera, "Kamera")
            }
            OutlinedButton(onClick = onGallery, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                IconLabel(R.drawable.ic_photo_library, "Pilih banyak foto")
            }
        }
        if (adding > 0) {
            Text("Menyiapkan $adding foto…", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Progress(
    rows: List<QueueRow>,
    work: ScanWorkState,
    paused: Boolean,
    enabled: Boolean,
    savingAll: Boolean,
    onPause: (Boolean) -> Unit,
    onSaveAll: () -> Unit,
) {
    val pending = rows.count { it.item.status == ScanItemStatus.Pending }
    val done = rows.count { it.item.status == ScanItemStatus.Done }
    val failed = rows.count { it.item.status == ScanItemStatus.Failed }
    val safe = rows.count { it.isSafe }

    SectionCard(null) {
        Text("$pending menunggu AI · $done siap dicek · $failed gagal", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (pending > 0 && enabled) {
            Text(
                when {
                    paused -> "Dijeda. Foto menunggu sampai dilanjutkan."
                    work is ScanWorkState.Reading -> "AI sedang membaca… (tetap jalan walau aplikasi ditutup)"
                    work is ScanWorkState.Waiting -> "AI sibuk, kuota habis, atau tidak ada koneksi. ${countdown(work.at)}"
                    else -> "Menunggu giliran… (butuh koneksi internet)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (work is ScanWorkState.Waiting && !paused) Garage.Brand2 else Garage.MutedForeground,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending > 0 && enabled) {
                OutlinedButton(onClick = { onPause(!paused) }, shape = MaterialTheme.shapes.medium) {
                    Text(if (paused) "Lanjutkan" else "Jeda")
                }
            }
            Button(onClick = onSaveAll, enabled = safe > 0 && !savingAll, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                if (savingAll) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    IconLabel(R.drawable.ic_check_circle, "Simpan semua yang yakin ($safe)")
                }
            }
        }
        Text(
            "Yang AI kurang yakin atau mirip dengan koleksimu dilewati — cek satu per satu.",
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
    }
}

/** "Lanjut otomatis dalam 42 detik", ticking. */
@Composable
private fun countdown(at: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val seconds = ((at - now) / 1_000).coerceAtLeast(0)
    return if (seconds > 0) "Lanjut otomatis dalam $seconds detik." else "Lanjut sebentar lagi."
}

@Composable
private fun DefaultsCard(viewModel: BulkScanViewModel, suggestions: Suggestions) {
    val defaults = viewModel.defaults
    val spotted = defaults.status == DiecastStatus.Spotted

    SectionCard("Isian yang sama untuk semua") {
        Text("Simpan sebagai", style = MaterialTheme.typography.labelLarge, color = Garage.MutedForeground)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Garage.Muted, RoundedCornerShape(8.dp))
                .padding(4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SAVE_AS.forEach { status ->
                val active = status == defaults.status
                Text(
                    status.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Garage.Foreground else Garage.MutedForeground,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (active) Modifier.background(Garage.Card).border(1.dp, Garage.Brand.copy(alpha = 0.5f), RoundedCornerShape(6.dp)) else Modifier)
                        .selectable(active, role = Role.RadioButton) { viewModel.setStatus(status) }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (defaults.status != DiecastStatus.Wishlist) {
            DateInput(
                defaults.date.toLocalDateOrNull(),
                { date -> viewModel.updateDefaults(defaults.copy(date = (date ?: LocalDate.now()).toString())) },
                if (spotted) "Tanggal ditemui" else "Tanggal beli",
                clearable = false,
                latest = LocalDate.now(),
            )
            LocationField(
                defaults.place,
                viewModel::setPlace,
                suggestions.places,
                if (spotted) "Ditemui di" else "Beli di",
                placeholder = "Toko / marketplace / teman",
            )
        }
        if (defaults.status == DiecastStatus.Owned) {
            SuggestInput(
                defaults.storageLocation,
                { viewModel.updateDefaults(defaults.copy(storageLocation = it)) },
                suggestions.storageLocations,
                "Lokasi simpan",
                placeholder = "Rak A / Box 3",
            )
        }
        TagInput(defaults.tags, { viewModel.updateDefaults(defaults.copy(tags = it)) }, suggestions.tags)
    }
}

private fun Confidence.color() = when (this) {
    Confidence.High -> Garage.Gain
    Confidence.Medium -> Color(0xFF3987E5)
    Confidence.Low -> Garage.Brand2
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun QueueCard(
    row: QueueRow,
    reading: Boolean,
    edits: QuickEdits,
    status: DiecastStatus,
    busy: Boolean,
    onEdit: ((QuickEdits) -> QuickEdits) -> Unit,
    onSave: () -> Unit,
    onComplete: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val item = row.item
    val fields = item.fields

    Surface(
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
        modifier = Modifier.alpha(if (busy) 0.6f else 1f),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiecastPhoto(item.photoFile, "Foto antrean", Modifier.size(width = 88.dp, height = 110.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 28.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (item.status) {
                        ScanItemStatus.Pending -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (reading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                if (reading) "AI sedang membaca…" else "Menunggu giliran…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Garage.MutedForeground,
                            )
                        }
                        ScanItemStatus.Failed ->
                            Text(item.error ?: "AI tidak bisa membaca foto ini.", style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
                        ScanItemStatus.Done -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                fields?.let { Pill(it.confidence.label.substringBefore(" —"), it.confidence.color()) }
                                if (row.matches.isNotEmpty()) Pill("Mirip koleksimu", Garage.MutedForeground)
                            }
                            TextInput(edits.name ?: fields?.name.orEmpty(), { v -> onEdit { it.copy(name = v) } }, "Nama model")
                        }
                    }
                }
                IconButton(onClick = onDiscard, enabled = !busy && !reading) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Buang foto ini", tint = Garage.MutedForeground)
                }
            }

            if (item.status == ScanItemStatus.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                        IconLabel(R.drawable.ic_sync, "Coba lagi")
                    }
                    OutlinedButton(onClick = onComplete, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                        IconLabel(R.drawable.ic_edit, "Isi manual")
                    }
                }
            }

            if (item.status == ScanItemStatus.Done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestInput(edits.brand ?: fields?.brand.orEmpty(), { v -> onEdit { it.copy(brand = v) } }, emptyList(), "Merek", modifier = Modifier.weight(1f))
                    TextInput(
                        edits.collectionNumber ?: fields?.collectionNumber.orEmpty(),
                        { v -> onEdit { it.copy(collectionNumber = v) } },
                        "No. koleksi",
                        modifier = Modifier.weight(1f),
                    )
                }
                TextInput(edits.series ?: fields?.series.orEmpty(), { v -> onEdit { it.copy(series = v) } }, "Seri")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceInput(
                        edits.rarity ?: fields?.rarity ?: Rarity.Regular,
                        Rarity.entries,
                        Rarity::label,
                        { v -> onEdit { it.copy(rarity = v) } },
                        "Kelangkaan",
                        modifier = Modifier.weight(1f),
                    )
                    MoneyInput(edits.price.orEmpty(), { v -> onEdit { it.copy(price = v) } }, priceLabel(status), modifier = Modifier.weight(1f))
                }
                if (row.matches.isNotEmpty()) Matches(row.matches, onOpen)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSave,
                        enabled = !busy && (edits.name ?: fields?.name).orEmpty().isNotBlank(),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    ) { IconLabel(R.drawable.ic_check, "Simpan") }
                    OutlinedButton(onClick = onComplete, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_edit, "Lengkap")
                    }
                }
            }
        }
    }
}

/** "N foto di antrean scan massal" on Koleksi and Garasi, while the queue holds something. */
@Composable
fun QueueBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = Garage.Brand.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, Garage.Brand.copy(alpha = 0.45f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
            Text(
                "$count foto di antrean scan massal belum disimpan.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text("Lanjutkan →", style = MaterialTheme.typography.bodyMedium, color = Garage.Brand, fontWeight = FontWeight.Medium)
        }
    }
}
