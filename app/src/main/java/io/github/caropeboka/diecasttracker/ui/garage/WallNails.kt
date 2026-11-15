package io.github.caropeboka.diecasttracker.ui.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import kotlin.math.roundToInt

// The nails in the wall over the desk, from the user's photo: two rows of four, a card hanging on each by its hang
// hole, flat against the wall. The boards hang above them. Sizes in metres, in the room's axes.

internal const val NAIL_COLUMNS = ShelfLayout.NAIL_COLUMNS
internal const val NAIL_ROWS = ShelfLayout.NAIL_ROWS

/** Across, a little more than a card is wide; down, a little more than one is tall. */
private const val NAIL_PITCH = 0.125f
private const val NAIL_ROW = 0.20f

/** The hang hole: this far below a card's top. */
private const val HOLE = 0.012f

/** The bottom row's cards end a hand above the desk, so the lifts and what stands on it stay clear. */
private const val LOWEST_CARD = DESK_TOP + 0.17f

/** Over the middle of the desk. */
internal const val NAILS_X = DESK_X

/** The top row's cards' top edge: the boards hang above it. */
internal const val NAILS_TOP = LOWEST_CARD + CARD_H + (NAIL_ROWS - 1) * NAIL_ROW

/** Halfway up the cards on the nails. */
internal const val NAILS_MIDDLE = (LOWEST_CARD + NAILS_TOP) / 2

/** A hanging card lies against the wall, on the nail's shank. */
private const val CARD_Z = WALL_Z + 0.003f

/** The nail: its shank, how far it stands out of the wall, and its head. */
private const val SHANK_R = 0.0012f
private const val NAIL_OUT = 0.014f
private const val HEAD_R = 0.0034f
private const val HEAD_T = 0.0012f

private val STEEL = Color(0xFFA4A6AA)

/** The x of a column of nails. */
internal fun nailX(column: Int) = NAILS_X + (column - (NAIL_COLUMNS - 1) / 2f) * NAIL_PITCH

/** The height of a row of nails, rows counted from the top. */
internal fun nailY(row: Int) = LOWEST_CARD + CARD_H - HOLE + (NAIL_ROWS - 1 - row) * NAIL_ROW

/** The middle of the card hanging on [nail] (row by row from the top left), [up] higher and [out] from the wall. */
internal fun onNail(nail: Int, up: Float = 0f, out: Float = 0f) =
    Position(nailX(nail % NAIL_COLUMNS), nailY(nail / NAIL_COLUMNS) + HOLE - CARD_H / 2 + up, CARD_Z + out)

/** The nail under a tap: where [ray] meets the wall, over the card that hangs or would hang on it, or the nail itself. */
internal fun nailAt(ray: Ray): Int? {
    val (x, y) = hitTipped(ray, Position(0f, 0f, 0f), 0f, CARD_Z) ?: return null
    val column = ((x - NAILS_X) / NAIL_PITCH + (NAIL_COLUMNS - 1) / 2f).roundToInt()
    if (column !in 0 until NAIL_COLUMNS) return null
    val row = (0 until NAIL_ROWS).firstOrNull { y in nailY(it) + HOLE - CARD_H - 0.015f..nailY(it) + 0.03f } ?: return null
    return row * NAIL_COLUMNS + column
}

/** The nail's side, out from the wall along the lathe's axis: the head, then the shank back into the wall. */
private fun nailProfile() = listOf(
    Float2(0f, NAIL_OUT),
    Float2(HEAD_R, NAIL_OUT),
    Float2(HEAD_R, NAIL_OUT - HEAD_T),
    Float2(SHANK_R, NAIL_OUT - HEAD_T),
    Float2(SHANK_R, -0.004f),
    Float2(0f, -0.004f),
)

/** The eight nails; tapping one brings the camera to face them. */
@Composable
fun SceneScope.WallNails() {
    val steel = remember(materialLoader) { materialLoader.createColorInstance(STEEL, roughness = 0.35f) }
    // Freed with the engine when the screen goes.
    val nail = remember(engine) { lathe(nailProfile(), segments = 12, crease = 30f).build(engine) }
    val name = FOCUS + Focus.Nails.name
    for (row in 0 until NAIL_ROWS) for (column in 0 until NAIL_COLUMNS) {
        // Turned so the lathe's axis points out of the wall.
        Mesh(nail, steel, position = Position(nailX(column), nailY(row), WALL_Z), rotation = Rotation(x = 90f, y = 0f, z = 0f), name = name)
    }
}
