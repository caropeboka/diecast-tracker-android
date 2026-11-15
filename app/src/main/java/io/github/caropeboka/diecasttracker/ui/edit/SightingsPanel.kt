package io.github.caropeboka.diecasttracker.ui.edit

import android.content.ActivityNotFoundException
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
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
import io.github.caropeboka.diecasttracker.util.formatMoney
import java.time.LocalDate

/**
 * "Catatan temuan": every place this diecast was seen, with the asking price — handy for comparing stores
 * before buying a wishlist item (web: `sightings-panel.tsx`).
 */
@Composable
fun SightingsCard(
    sightings: List<SightingEntity>,
    onAdd: () -> Unit,
    onDelete: (Long) -> Unit,
    /** Diecasts a sighting may move to: look-alikes of this one. */
    moveTargets: suspend () -> List<DiecastListItem>,
    onMove: (sightingId: Long, DiecastListItem) -> Unit,
    onSplit: (sightingId: Long) -> Unit,
) {
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var moving by rememberSaveable { mutableStateOf<Long?>(null) }
    val prices = sightings.mapNotNull { it.price }
    val cheapest = prices.minOrNull()?.takeIf { prices.size > 1 }

    SectionCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
            SubHeading(stringResource(R.string.sightings_title), Modifier.padding(start = 8.dp))
            if (sightings.isNotEmpty()) {
                Text(" (${sightings.size}×)", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
            }
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
                IconLabel(R.drawable.ic_add, stringResource(R.string.sighting_log))
            }
        }

        if (sightings.isEmpty()) {
            Text(
                stringResource(R.string.sightings_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )
        }

        sightings.forEachIndexed { index, sighting ->
            if (index > 0) HorizontalDivider(color = Garage.Border)
            SightingRow(
                sighting,
                isCheapest = cheapest != null && sighting.price == cheapest,
                onMove = { moving = sighting.id },
                onDelete = { deleting = sighting.id },
            )
        }
    }

    moving?.let { id ->
        val sighting = sightings.find { it.id == id }
        if (sighting == null) {
            moving = null
        } else {
            MoveSightingSheet(
                sighting = sighting,
                targets = moveTargets,
                onMove = { target ->
                    moving = null
                    onMove(id, target)
                },
                onSplit = {
                    moving = null
                    onSplit(id)
                },
                onDismiss = { moving = null },
            )
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sighting_delete_title)) },
            text = { Text(sightings.find { it.id == id }?.place ?: stringResource(R.string.sighting_no_place)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(id)
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SightingRow(sighting: SightingEntity, isCheapest: Boolean, onMove: () -> Unit, onDelete: () -> Unit) {
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
                sighting.place ?: stringResource(R.string.sighting_no_place),
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
                sighting.price?.let(::formatMoney) ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (sighting.price != null) Garage.Brand else Garage.MutedForeground,
            )
            if (isCheapest) Badge(stringResource(R.string.cheapest), background = Garage.Gain, content = Garage.Foreground)
        }
        Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.sighting_more), tint = Garage.MutedForeground, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Garage.Popover) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sighting_move)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_drive_file_move), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        menu = false
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sighting_delete), color = Garage.Loss) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = Garage.Loss, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** "Whose sighting was this?": a look-alike of this diecast, or a new "Ditemui" one made from the sighting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSightingSheet(
    sighting: SightingEntity,
    targets: suspend () -> List<DiecastListItem>,
    onMove: (DiecastListItem) -> Unit,
    onSplit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val found by produceState<List<DiecastListItem>?>(null) { value = targets() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading(stringResource(R.string.sighting_move_title))
            Text(
                listOfNotNull(sighting.place, formatDate(sighting.seenOn), sighting.price?.let(::formatMoney)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.sighting_move_hint), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)

            MoveChoice(
                photo = { DiecastPhoto(sighting.photoFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 22.dp) },
                title = stringResource(R.string.sighting_new_item),
                detail = stringResource(R.string.sighting_new_item_hint),
                icon = R.drawable.ic_add,
                onClick = onSplit,
            )

            when (val list = found) {
                null -> CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp)
                else -> {
                    if (list.isEmpty()) {
                        Text(stringResource(R.string.sighting_no_targets), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                    }
                    list.forEach { target ->
                        MoveChoice(
                            photo = { DiecastPhoto(target.coverFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 22.dp) },
                            title = target.name,
                            detail = listOfNotNull(
                                target.brand,
                                target.series,
                                target.collectionNumber,
                                target.color,
                                stringResource(target.status.label),
                            ).joinToString(" · "),
                            icon = R.drawable.ic_drive_file_move,
                            onClick = { onMove(target) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveChoice(photo: @Composable () -> Unit, title: String, detail: String, @DrawableRes icon: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Garage.Muted)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        photo()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(20.dp))
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
            Heading(stringResource(R.string.sighting_log))
            LocationField(
                draft.place,
                { place -> viewModel.updateSighting(SightingField.Place) { it.copy(place = place) } },
                places,
                stringResource(R.string.edit_spotted_at),
                placeholder = stringResource(R.string.edit_spotted_at_hint),
                error = errors[SightingField.Place],
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateInput(
                    draft.seenOn.toLocalDateOrNull(),
                    { date -> viewModel.updateSighting(SightingField.Date) { it.copy(seenOn = (date ?: today).toString()) } },
                    stringResource(R.string.field_date),
                    clearable = false,
                    latest = today,
                    error = errors[SightingField.Date],
                    modifier = Modifier.weight(1f),
                )
                MoneyInput(
                    draft.price,
                    { price -> viewModel.updateSighting(SightingField.Price) { it.copy(price = price) } },
                    stringResource(R.string.edit_price_there),
                    placeholder = stringResource(R.string.optional),
                    error = errors[SightingField.Price],
                    modifier = Modifier.weight(1f),
                )
            }
            TextInput(
                draft.notes,
                { notes -> viewModel.updateSighting(SightingField.Notes) { it.copy(notes = notes) } },
                stringResource(R.string.field_notes),
                placeholder = stringResource(R.string.sighting_notes_hint),
                error = errors[SightingField.Notes],
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    viewModel.processingSightingPhoto -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    draft.photo != null -> Box {
                        DiecastPhoto(draft.photo, stringResource(R.string.sighting_photo), Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                        IconButton(onClick = viewModel::removeSightingPhoto, modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.edit_delete_photo), modifier = Modifier.size(16.dp))
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
                ) { IconLabel(R.drawable.ic_photo_camera, stringResource(if (draft.photo == null) R.string.photo else R.string.replace)) }
                TextButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.medium,
                ) { IconLabel(R.drawable.ic_photo_library, stringResource(R.string.gallery)) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = viewModel::closeSighting, enabled = !viewModel.savingSighting, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = viewModel::saveSighting, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                    if (viewModel.savingSighting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.sighting_save))
                    }
                }
            }
        }
    }
}
