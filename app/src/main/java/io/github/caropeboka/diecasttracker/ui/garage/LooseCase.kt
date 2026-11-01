package io.github.caropeboka.diecasttracker.ui.garage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// The user's white display case for loose cars and small boxes, from their photo: six columns of ten compartments
// between thin dividers, leaning back against the wall. Its own axes: x across from the centre line, y up from the
// bottom edge, z out from the back panel; sizes in metres.

internal const val CASE_COLUMNS = ShelfLayout.CASE_COLUMNS
internal const val CASE_ROWS = ShelfLayout.CASE_ROWS
private const val CELL_W = 0.085f
private const val CELL_H = 0.045f
private const val DIVIDER = 0.003f
private const val FRAME = 0.016f
internal const val CASE_DEPTH = 0.055f
private const val BACK = 0.004f
private const val CASE_W = CASE_COLUMNS * CELL_W + (CASE_COLUMNS - 1) * DIVIDER + 2 * FRAME
internal const val CASE_H = CASE_ROWS * CELL_H + (CASE_ROWS - 1) * DIVIDER + 2 * FRAME

/** The most a car may take up in its compartment. */
internal const val CELL_FIT_W = CELL_W - 0.008f
internal const val CELL_FIT_H = CELL_H - 0.005f

/** Leaning back, its top edge against the wall, a hand's width right of the boards. */
internal const val CASE_LEAN = 10f
internal const val CASE_X = BOARDS_RIGHT + 0.07f + CASE_W / 2
private val CASE_ORIGIN = Position(CASE_X, 0f, WALL_Z + CASE_H * sin(Math.toRadians(CASE_LEAN.toDouble())).toFloat())

private val FRONT = Color(0xFFFFFFFF)

/** Inside the compartments, a touch greyer than the frame, as their own shade would leave them. */
private val INSIDE = Color(0xFFF7F6F3)

/** A point in the case in the room. */
internal fun inCase(x: Float, y: Float, z: Float): Position {
    val angle = Math.toRadians(-CASE_LEAN.toDouble())
    val c = cos(angle).toFloat()
    val s = sin(angle).toFloat()
    return Position(CASE_ORIGIN.x + x, CASE_ORIGIN.y + y * c - z * s, CASE_ORIGIN.z + y * s + z * c)
}

/** The x of a column's centre line. */
internal fun cellX(column: Int) = -CASE_W / 2 + FRAME + column * (CELL_W + DIVIDER) + CELL_W / 2

/** The floor of a compartment, rows counted from the top. */
internal fun cellFloor(row: Int) = CASE_H - FRAME - (row + 1) * CELL_H - row * DIVIDER

/** The compartment under a tap, row by row from the top left: where [ray] meets the front of the case. */
internal fun cellAt(ray: Ray): Int? {
    val (x, y) = hitTipped(ray, CASE_ORIGIN, CASE_LEAN, CASE_DEPTH) ?: return null
    val column = floor((x + CASE_W / 2 - FRAME) / (CELL_W + DIVIDER)).toInt()
    val row = floor((CASE_H - FRAME - y) / (CELL_H + DIVIDER)).toInt()
    return if (column in 0 until CASE_COLUMNS && row in 0 until CASE_ROWS) row * CASE_COLUMNS + column else null
}

/** Where a car stands in its compartment: halfway in. */
internal const val CELL_Z = BACK + (CASE_DEPTH - BACK) * 0.45f

/** A small box in a compartment: nearly as deep, its front just behind the frame's. */
internal const val CELL_BOX_D = CASE_DEPTH - BACK - 0.008f
internal const val CELL_BOX_Z = CASE_DEPTH - 0.002f - CELL_BOX_D / 2

/** The case on the floor by the wall; tapping it brings the camera to face it. */
@Composable
fun SceneScope.LooseCase() {
    val front = remember(materialLoader) { materialLoader.createColorInstance(FRONT, roughness = 0.42f) }
    val inside = remember(materialLoader) { materialLoader.createColorInstance(INSIDE, roughness = 0.5f) }
    val name = FOCUS + Focus.Case.name
    val inner = CASE_DEPTH - BACK

    Node(position = CASE_ORIGIN, rotation = Rotation(x = -CASE_LEAN, y = 0f, z = 0f)) {
        CubeNode(size = Size(CASE_W, CASE_H, BACK), center = Position(0f, CASE_H / 2, BACK / 2), materialInstance = inside, apply = { this.name = name })
        for (side in listOf(-1f, 1f)) {
            CubeNode(
                size = Size(FRAME, CASE_H, CASE_DEPTH),
                center = Position(side * (CASE_W - FRAME) / 2, CASE_H / 2, CASE_DEPTH / 2),
                materialInstance = front,
                apply = { this.name = name },
            )
        }
        for (y in listOf(FRAME / 2, CASE_H - FRAME / 2)) {
            CubeNode(
                size = Size(CASE_W - 2 * FRAME, FRAME, CASE_DEPTH),
                center = Position(0f, y, CASE_DEPTH / 2),
                materialInstance = front,
                apply = { this.name = name },
            )
        }
        for (column in 1 until CASE_COLUMNS) {
            CubeNode(
                size = Size(DIVIDER, CASE_H - 2 * FRAME, inner),
                center = Position(cellX(column) - (CELL_W + DIVIDER) / 2, CASE_H / 2, BACK + inner / 2),
                materialInstance = inside,
                apply = { this.name = name },
            )
        }
        for (row in 0 until CASE_ROWS - 1) {
            CubeNode(
                size = Size(CASE_W - 2 * FRAME, DIVIDER, inner),
                center = Position(0f, cellFloor(row) - DIVIDER / 2, BACK + inner / 2),
                materialInstance = inside,
                apply = { this.name = name },
            )
        }
    }
}
