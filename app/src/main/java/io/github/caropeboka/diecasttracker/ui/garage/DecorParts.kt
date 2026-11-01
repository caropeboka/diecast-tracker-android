package io.github.caropeboka.diecasttracker.ui.garage

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as Area
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.ui.components.AiProgress
import io.github.caropeboka.diecasttracker.ui.components.AiProgressView
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.texture.ImageTexture
import kotlin.math.atan2
import kotlin.math.roundToInt

// Decorations in the 3D garage (figures, ornaments; see DecorItem): how they stand on the black shelf, and the sheets
// to add, change and remove them.

internal const val DECOR = "decor-"

/** How high a decoration rises when it is picked, to show which. */
private const val DECOR_LIFT = 0.02f

/** A new one's height until the collector says otherwise: a typical figure. */
private const val DEFAULT_HEIGHT_CM = 12

/**
 * A decoration standing on its tier, its picture always turned to the camera ([eye]): a figure seen from any side
 * still looks like itself. [lifted] while it is picked. Without reflectance, like a loose car's cut-out.
 */
@Composable
internal fun SceneScope.Decoration(placed: PlacedDecor, eye: State<Position>, lifted: Boolean) {
    val item = placed.item
    val size = item.size
    val lift by animateFloatAsState(if (lifted) DECOR_LIFT else 0f, tween(250), label = "lift")
    val geometry = remember(engine, size.x, size.y) { plane(size.x, size.y).build(engine) }
    val material = remember(item.image) {
        materialLoader.createTextureInstance(
            ImageTexture.Builder().bitmap(item.image).build(engine),
            isOpaque = false,
            roughness = 1f,
            reflectance = 0f,
        )
    }
    val centre = placed.centre
    val at = eye.value
    val yaw = Math.toDegrees(atan2(at.x - centre.x, at.z - centre.z).toDouble()).toFloat()
    Node(position = Position(centre.x, centre.y + lift, centre.z), rotation = Rotation(x = 0f, y = yaw, z = 0f)) {
        Mesh(geometry, material, name = DECOR + item.id)
    }
}

/** The first choice when an empty spot on the black shelf is tapped: a decoration from a photo. */
@Composable
internal fun NewDecorRow(onCamera: () -> Unit, onGallery: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Brand.copy(alpha = 0.08f))
            .border(1.dp, Garage.Brand.copy(alpha = 0.35f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_auto_fix_high), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Hiasan baru di sini", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Figur atau pajangan dari foto: latarnya dipotong dan debunya dibersihkan di HP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCamera, shape = MaterialTheme.shapes.medium) {
                Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Kamera")
            }
            OutlinedButton(onClick = onGallery, shape = MaterialTheme.shapes.medium) {
                Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Galeri")
            }
        }
    }
}

/** A new decoration: its photo being cut out, then the result (as it was, or cleaned), its name and height. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DecorDraftSheet(
    draft: GarageViewModel.DecorDraft,
    progress: AiProgress?,
    onSave: (name: String, heightMm: Int, cleaned: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var height by rememberSaveable { mutableStateOf(DEFAULT_HEIGHT_CM.toString()) }
    var cleaned by rememberSaveable { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SubHeading("Hiasan baru")
            Text(ShelfLayout.label(Shelf.Corner, draft.spot.spot), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            val cutout = draft.cutout
            when {
                draft.error != null -> {
                    Text(draft.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
                cutout == null -> progress?.let { AiProgressView(it) }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Choice(cutout.original, "Seperti difoto", !cleaned, Modifier.weight(1f)) { cleaned = false }
                        Choice(cutout.cleaned, "Dibersihkan", cleaned, Modifier.weight(1f)) { cleaned = true }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nama") },
                        placeholder = { Text("mis. Figur Risky Ridho") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HeightField(height) { height = it }
                    val heightMm = heightMm(height)
                    Button(
                        onClick = { onSave(name.trim().ifEmpty { "Hiasan" }, heightMm!!, cleaned) },
                        enabled = heightMm != null,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Taruh di laci") }
                }
            }
        }
    }
}

/** One version of the cut-out to choose, on a checkerboard so its see-through parts show as such. */
@Composable
private fun Choice(image: Bitmap, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Garage.Brand else Garage.Border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(MaterialTheme.shapes.small)
                .drawBehind {
                    val cell = 8.dp.toPx()
                    drawRect(Color(0xFF2A2A30))
                    var y = 0f
                    var row = 0
                    while (y < size.height) {
                        var x = if (row % 2 == 0) 0f else cell
                        while (x < size.width) {
                            drawRect(Color(0xFF34343B), Offset(x, y), Area(cell, cell))
                            x += 2 * cell
                        }
                        y += cell
                        row++
                    }
                },
        ) {
            Image(image.asImageBitmap(), contentDescription = label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).padding(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Garage.Brand else Garage.Foreground)
    }
}

@Composable
private fun HeightField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() || it == ',' || it == '.' }.take(5)) },
        label = { Text("Tinggi asli (cm)") },
        supportingText = { Text("Supaya ukurannya pas di samping diecast.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Centimetres as typed (a comma or a point) in millimetres; null outside 1–60 cm. */
internal fun heightMm(text: String): Int? =
    text.replace(',', '.').toFloatOrNull()?.takeIf { it in 1f..60f }?.let { (it * 10).roundToInt() }

private fun centimetres(heightMm: Int): String =
    if (heightMm % 10 == 0) (heightMm / 10).toString() else "%d,%d".format(heightMm / 10, heightMm % 10)

/** The decoration picked, where it stands, and what can be done with it. */
@Composable
internal fun DecorBar(item: DecorItem, text: String, actions: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Card.copy(alpha = 0.94f))
            .border(1.dp, Garage.Border, shape)
            .padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                item.image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(Garage.Muted),
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
        Row(Modifier.align(Alignment.End)) { actions() }
    }
}

/** A decoration's name and height. */
@Composable
internal fun DecorEditDialog(item: DecorItem, onSave: (name: String, heightMm: Int) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(item.name) }
    var height by rememberSaveable { mutableStateOf(centimetres(item.heightMm)) }
    val heightMm = heightMm(height)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah hiasan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                HeightField(height) { height = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim().ifEmpty { item.name }, heightMm!!) }, enabled = heightMm != null) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
        containerColor = Garage.Popover,
    )
}

@Composable
internal fun DecorDeleteDialog(item: DecorItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hapus ${item.name}?") },
        text = { Text("Hiasan ini hilang dari garasi. Koleksi diecast-mu tidak berubah.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Hapus", color = Color(0xFFE66767)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
        containerColor = Garage.Popover,
    )
}
