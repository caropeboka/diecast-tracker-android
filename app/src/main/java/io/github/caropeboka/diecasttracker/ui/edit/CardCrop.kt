package io.github.caropeboka.diecasttracker.ui.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.studio.CardCutout
import io.github.caropeboka.diecasttracker.data.studio.StudioComposer
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Foto studio" for a card or box, in two steps. First the photo ([image] once loaded) and the card's corners on it, in
 * the photo's pixels, top left first and clockwise as they started (moving them may change that; [quad] puts them in
 * order). Then the card squared up ([cutout]), its corners [rounded], to tap the background still showing away.
 */
data class CardCrop(
    val photo: String,
    val rounded: Boolean,
    val image: Bitmap? = null,
    val corners: List<Offset> = emptyList(),
    /** The background remover is looking for the card; its corners replace the first ones unless one was moved. */
    val guessing: Boolean = true,
    val guessed: Boolean = false,
    val moved: Boolean = false,
    /** Squaring up or a tap is being worked out. */
    val working: Boolean = false,
    val cutout: CardCutout? = null,
    /** [cutout]'s look after the last change, and what the last tap did. */
    val preview: Bitmap? = null,
    val lastTap: CardCutout.Tap? = null,
) {
    /** The corners in order (see [StudioComposer.cardQuad]); null while they don't make a card's shape. */
    val quad: FloatArray?
        get() = image?.takeIf { corners.size == 4 }?.let { image ->
            StudioComposer.cardQuad(FloatArray(8) { corners[it / 2].let { p -> if (it % 2 == 0) p.x else p.y } }, image.width, image.height)
        }

    companion object {
        /** Before any guess: a little inside the photo's edges. */
        fun startCorners(image: Bitmap): List<Offset> {
            val w = image.width.toFloat()
            val h = image.height.toFloat()
            return listOf(Offset(w * 0.1f, h * 0.1f), Offset(w * 0.9f, h * 0.1f), Offset(w * 0.9f, h * 0.9f), Offset(w * 0.1f, h * 0.9f))
        }

        fun corners(points: FloatArray): List<Offset> = List(4) { Offset(points[it * 2], points[it * 2 + 1]) }
    }
}

/**
 * The card's studio photo, full screen: drag each point onto a corner of the card (with a loupe under the finger), then
 * tap away the background that still shows.
 */
@Composable
fun CardCropDialog(crop: CardCrop, viewModel: DiecastEditViewModel) {
    val cutout = crop.cutout
    val quad = crop.quad
    val back = if (cutout != null) viewModel::backToCorners else viewModel::closeCardCrop
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Garage.Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::closeCardCrop) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                    }
                    Heading(stringResource(R.string.studio_photo), Modifier.weight(1f))
                    Text(
                        stringResource(R.string.crop_step, if (cutout == null) 1 else 2, 2),
                        style = MaterialTheme.typography.labelMedium,
                        color = Garage.MutedForeground,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }

                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val searching = cutout == null && crop.guessing && !crop.moved
                    val problem = if (cutout == null) crop.image != null && quad == null else crop.lastTap == CardCutout.Tap.TooBig
                    if (searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        when {
                            cutout != null -> when (crop.lastTap) {
                                CardCutout.Tap.Erased -> stringResource(R.string.crop_erased)
                                CardCutout.Tap.Restored -> stringResource(R.string.crop_restored)
                                CardCutout.Tap.TooBig -> stringResource(R.string.crop_too_big)
                                null -> if (cutout.holeFound) {
                                    stringResource(R.string.crop_hole_removed)
                                } else {
                                    stringResource(R.string.crop_tap_background)
                                }
                            }
                            problem -> stringResource(R.string.crop_problem)
                            searching -> stringResource(R.string.crop_searching)
                            crop.guessed && !crop.moved -> stringResource(R.string.crop_guessed)
                            else -> stringResource(R.string.crop_drag)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (problem) Garage.Loss else Garage.MutedForeground,
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    val image = crop.image
                    val preview = crop.preview
                    when {
                        cutout != null && preview != null -> CardPreview(preview, viewModel::tapCard)
                        image == null || crop.corners.size != 4 -> CircularProgressIndicator()
                        else -> CornerEditor(image, crop.corners, viewModel::moveCorner)
                    }
                }

                HorizontalDivider(color = Garage.Border)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cutout == null) {
                        TextButton(onClick = viewModel::cutOutInstead, enabled = !crop.working, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.crop_loose))
                        }
                        Button(onClick = viewModel::continueCardCrop, enabled = quad != null && !crop.working, shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(if (crop.working) R.string.crop_straightening else R.string.next))
                        }
                    } else {
                        TextButton(onClick = viewModel::backToCorners, enabled = !crop.working, shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.crop_corners))
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = viewModel::undoCard, enabled = cutout.canUndo && !crop.working, shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.undo))
                        }
                        Button(onClick = viewModel::finishCardCrop, enabled = !crop.working, shape = MaterialTheme.shapes.medium) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

/** The squared-up card on white, as the studio photo will show it; a tap reports the card's pixel under it. */
@Composable
private fun CardPreview(preview: Bitmap, onTap: (Int, Int) -> Unit) {
    val bitmap = remember(preview) { preview.asImageBitmap() }
    BoxWithConstraints(Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(Color.White)) {
        val margin = with(LocalDensity.current) { 16.dp.toPx() }
        val scale = min((constraints.maxWidth - 2 * margin) / preview.width, (constraints.maxHeight - 2 * margin) / preview.height)
        val shown = Size(preview.width * scale, preview.height * scale)
        val origin = Offset((constraints.maxWidth - shown.width) / 2, (constraints.maxHeight - shown.height) / 2)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scale, origin) {
                    detectTapGestures { at ->
                        val p = (at - origin) / scale
                        if (p.x >= 0 && p.y >= 0 && p.x < preview.width && p.y < preview.height) onTap(p.x.toInt(), p.y.toInt())
                    }
                },
        ) {
            drawPhoto(bitmap, origin, shown)
        }
    }
}

@Composable
private fun CornerEditor(image: Bitmap, corners: List<Offset>, onMove: (Int, Offset) -> Unit) {
    val bitmap = remember(image) { image.asImageBitmap() }
    val current by rememberUpdatedState(corners)
    var dragging by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The photo fitted into the space, and the way between its pixels and the screen.
        val scale = min(constraints.maxWidth / image.width.toFloat(), constraints.maxHeight / image.height.toFloat())
        val shown = Size(image.width * scale, image.height * scale)
        val origin = Offset((constraints.maxWidth - shown.width) / 2, (constraints.maxHeight - shown.height) / 2)
        fun onScreen(p: Offset) = origin + p * scale

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(scale, origin) {
                    val reach = 40.dp.toPx()
                    // Where the finger has taken the point, on screen: the point itself may stop at the photo's edge.
                    var at = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start ->
                            val nearest = current.indices.minBy { (onScreen(current[it]) - start).getDistance() }
                            dragging = nearest.takeIf { (onScreen(current[it]) - start).getDistance() <= reach }
                            at = onScreen(current[nearest])
                        },
                        onDragEnd = { dragging = null },
                        onDragCancel = { dragging = null },
                        onDrag = { change, amount ->
                            dragging?.let { i ->
                                change.consume()
                                at += amount
                                onMove(i, (at - origin) / scale)
                            }
                        },
                    )
                },
        ) {
            val outline = Path().apply {
                corners.forEachIndexed { i, p -> onScreen(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
                close()
            }
            drawPhoto(bitmap, origin, shown)
            // Outside the card dimmed, so what will be kept stands out.
            clipPath(outline, ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.55f), topLeft = origin, size = shown)
            }
            drawPath(outline, Garage.Brand, style = Stroke(2.dp.toPx()))
            corners.forEachIndexed { i, p ->
                drawCircle(Color.White, radius = (if (i == dragging) 13 else 11).dp.toPx(), center = onScreen(p))
                drawCircle(Garage.Brand, radius = (if (i == dragging) 10 else 8).dp.toPx(), center = onScreen(p))
            }
            dragging?.let { i -> drawLoupe(bitmap, origin, shown, outline, onScreen(corners[i])) }
        }
    }
}

private fun DrawScope.drawPhoto(bitmap: ImageBitmap, origin: Offset, shown: Size) {
    drawImage(
        bitmap,
        dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
        dstSize = IntSize(shown.width.roundToInt(), shown.height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/** A magnified circle of the photo around [focus], away from the finger: in the top corner across from it, or below. */
private fun DrawScope.drawLoupe(bitmap: ImageBitmap, origin: Offset, shown: Size, outline: Path, focus: Offset) {
    val radius = 56.dp.toPx()
    val inset = radius + 8.dp.toPx()
    val x = if (focus.x > size.width / 2) inset else size.width - inset
    val y = if (focus.y > size.height / 2) inset else size.height - inset
    val centre = Offset(x, y)
    val zoom = 2.5f

    clipPath(Path().apply { addOval(Rect(centre, radius)) }) {
        drawRect(Garage.Muted, topLeft = centre - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        withTransform({
            translate(centre.x - focus.x, centre.y - focus.y)
            scale(zoom, zoom, pivot = focus)
        }) {
            drawPhoto(bitmap, origin, shown)
            drawPath(outline, Garage.Brand, style = Stroke(1.5.dp.toPx() / zoom))
        }
    }
    val arm = 8.dp.toPx()
    drawLine(Garage.Brand, centre - Offset(arm, 0f), centre + Offset(arm, 0f), strokeWidth = 1.5.dp.toPx())
    drawLine(Garage.Brand, centre - Offset(0f, arm), centre + Offset(0f, arm), strokeWidth = 1.5.dp.toPx())
    drawCircle(Color.White, radius = radius, center = centre, style = Stroke(2.dp.toPx()))
}
