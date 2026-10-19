package io.github.caropeboka.diecasttracker.ui.edit

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.SightingEntity
import io.github.caropeboka.diecasttracker.ui.components.Badge
import io.github.caropeboka.diecasttracker.ui.components.DateInput
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.MoneyInput
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.places.LocationField
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.formatRupiah
import java.time.LocalDate

/**
 * "Catatan temuan": every place this diecast was seen, with the asking price — handy for comparing stores
 * before buying a wishlist item (web: `sightings-panel.tsx`).
 */
@Composable
fun SightingsCard(sightings: List<SightingEntity>, onAdd: () -> Unit, onDelete: (Long) -> Unit) {
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val prices = sightings.mapNotNull { it.price }
    val cheapest = prices.minOrNull()?.takeIf { prices.size > 1 }

    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
            SubHeading("Catatan temuan", Modifier.padding(start = 8.dp))
            if (sightings.isNotEmpty()) {
                Text(" (${sightings.size}×)", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
            }
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
                IconLabel(R.drawable.ic_add, "Catat temuan")
            }
        }

        if (sightings.isEmpty()) {
            Text(
                "Pernah lihat diecast ini di toko, di koleksi teman, atau di event? Catat tempat dan harganya untuk " +
                    "membandingkan sebelum membeli.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )
        }

        sightings.forEachIndexed { index, sighting ->
            if (index > 0) HorizontalDivider(color = Garage.Border)
            SightingRow(sighting, isCheapest = cheapest != null && sighting.price == cheapest, onDelete = { deleting = sighting.id })
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus temuan ini?") },
            text = { Text(sightings.find { it.id == id }?.place ?: "Tempat tidak dicatat") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(id)
                }) { Text("Hapus", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun SightingRow(sighting: SightingEntity, isCheapest: Boolean, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(6.dp)
        if (sighting.photoFile != null) {
            DiecastPhoto(sighting.photoFile, null, Modifier.size(44.dp).clip(shape), placeholderSize = 20.dp)
        } else {
            Box(Modifier.size(44.dp).background(Garage.Muted, shape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                sighting.place ?: "Tempat tidak dicatat",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(formatDate(sighting.seenOn), sighting.notes).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                sighting.price?.let(::formatRupiah) ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (sighting.price != null) Garage.Brand else Garage.MutedForeground,
            )
            if (isCheapest) Badge("Termurah", background = Garage.Gain, content = Garage.Foreground)
        }
        IconButton(onClick = onDelete) {
            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Hapus temuan ini", tint = Garage.MutedForeground, modifier = Modifier.size(20.dp))
        }
    }
}

/** The "Catat temuan" form, in a bottom sheet over the diecast. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingSheet(viewModel: DiecastEditViewModel, places: List<String>) {
    val draft = viewModel.sightingDraft ?: return
    val errors = viewModel.sightingErrors
    val today = LocalDate.now()
    val busy = viewModel.savingSighting || viewModel.processingSightingPhoto

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onSightingCamera)
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::addSightingPhoto)
    }

    ModalBottomSheet(
        onDismissRequest = viewModel::closeSighting,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Garage.Card,
    ) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Catat temuan")
            LocationField(
                draft.place,
                { place -> viewModel.updateSighting(SightingField.Place) { it.copy(place = place) } },
                places,
                "Ditemui di",
                placeholder = "Toys Kingdom GI, teman, event…",
                error = errors[SightingField.Place],
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateInput(
                    draft.seenOn.toLocalDateOrNull(),
                    { date -> viewModel.updateSighting(SightingField.Date) { it.copy(seenOn = (date ?: today).toString()) } },
                    "Tanggal",
                    clearable = false,
                    latest = today,
                    error = errors[SightingField.Date],
                    modifier = Modifier.weight(1f),
                )
                MoneyInput(
                    draft.price,
                    { price -> viewModel.updateSighting(SightingField.Price) { it.copy(price = price) } },
                    "Harga di sana",
                    placeholder = "Opsional",
                    error = errors[SightingField.Price],
                    modifier = Modifier.weight(1f),
                )
            }
            TextInput(
                draft.notes,
                { notes -> viewModel.updateSighting(SightingField.Notes) { it.copy(notes = notes) } },
                "Catatan",
                placeholder = "Kartu agak penyok, stok tinggal 1…",
                error = errors[SightingField.Notes],
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    viewModel.processingSightingPhoto -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    draft.photo != null -> Box {
                        DiecastPhoto(draft.photo, "Foto temuan", Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                        IconButton(onClick = viewModel::removeSightingPhoto, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Hapus foto", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                TextButton(
                    onClick = {
                        try {
                            camera.launch(viewModel.sightingCameraTarget())
                        } catch (_: ActivityNotFoundException) {
                            viewModel.onSightingCamera(false)
                        }
                    },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                ) { IconLabel(R.drawable.ic_photo_camera, if (draft.photo == null) "Foto" else "Ganti") }
                TextButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                ) { IconLabel(R.drawable.ic_photo_library, "Galeri") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = viewModel::closeSighting, enabled = !viewModel.savingSighting, shape = MaterialTheme.shapes.medium) {
                    Text("Batal")
                }
                Button(onClick = viewModel::saveSighting, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                    if (viewModel.savingSighting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Simpan temuan")
                    }
                }
            }
        }
    }
}
