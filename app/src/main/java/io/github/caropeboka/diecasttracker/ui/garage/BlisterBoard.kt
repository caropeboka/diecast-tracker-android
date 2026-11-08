package io.github.caropeboka.diecasttracker.ui.garage

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.sceneview.SceneScope
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.triangulation.Earcut
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The user's laser-cut MDF blister boards, traced from their photo: two alike, side by side on the wall above the white
// desk. A board's own axes: x across from its centre line, y up from the bottom edge, z out of the face; sizes in
// metres.

private const val BOARD_W = 0.46f
private const val THICKNESS = 0.006f
internal const val FACE_Z = THICKNESS / 2

/** The top of the main body, the tab with the logo and hook above it on the left, the step on the right. */
private const val MAIN_TOP = 0.703f
private const val TAB_TOP = 0.773f
private const val STEP_TOP = 0.729f

internal const val COLUMNS = ShelfLayout.LANES_PER_BOARD
private const val PITCH = 0.110f
internal const val RAIL_TOP = 0.675f

/** Between the board and the rails' lips: the groove the cards' edges slide down. */
private const val GROOVE = 0.011f
private const val LIP_W = 0.012f
private const val LIP_D = 0.004f
private const val LIP_FRONT = FACE_Z + GROOVE + LIP_D

/** The strip along the bottom the lowest cards stand on. */
internal const val LEDGE_TOP = 0.02f

/** The rails come in pieces about a card high. */
private const val JOINT = 0.17f

/** Hung flat on the wall (they leaned on it from the floor before the desk came), a little clear of it on the screws. */
internal const val LEAN = 0f
private const val BOARD_Z = WALL_Z + 0.004f + THICKNESS / 2

/** Their bottom edges this high: a hand above the desk, so the lifts and the boxes on it stay clear. */
internal const val BOARD_Y = DESK_TOP + 0.17f

/** The two boards, a finger's width apart, a little right of the middle so the black shelf has room; the desk under them. */
internal const val BOARDS = ShelfLayout.BOARDS
private const val BOARD_GAP = 0.012f
internal const val BOARDS_X = 0.03f

/** The right-hand board's right edge: the white case stands clear of it. */
internal const val BOARDS_RIGHT = BOARDS_X + BOARDS / 2f * (BOARD_W + BOARD_GAP) - BOARD_GAP / 2

/** The x of a board's centre line in the room, counted from the left. */
private fun boardX(board: Int) = BOARDS_X + (board - (BOARDS - 1) / 2f) * (BOARD_W + BOARD_GAP)

private val MDF = Color(0xFFC49C76)
private val SCORCHED = Color(0xFF3B2618)

/** Counter-clockwise from the bottom-left corner, in the photo's measurements (x from the left edge). */
private val OUTLINE: List<Float2> = buildList {
    add(Float2(0f, 0f))
    add(Float2(BOARD_W, 0f))
    add(Float2(BOARD_W, STEP_TOP))
    add(Float2(0.419f, STEP_TOP))
    add(Float2(0.399f, MAIN_TOP))
    add(Float2(0.222f, MAIN_TOP))
    // The hook it hangs from: a slot with a round end, running right under a tongue.
    arc(Float2(0.235f, 0.740f), 0.013f, 180f, 0f, steps = 8)
    add(Float2(0.248f, 0.726f))
    arc(Float2(0.315f, 0.734f), 0.008f, -90f, 0f, steps = 4)
    arc(Float2(0.315f, 0.765f), 0.008f, 0f, 90f, steps = 4)
    add(Float2(0.059f, TAB_TOP))
    add(Float2(0f, MAIN_TOP))
}

private fun MutableList<Float2>.arc(centre: Float2, radius: Float, from: Float, to: Float, steps: Int) {
    for (i in 0..steps) {
        val angle = Math.toRadians((from + (to - from) * i / steps).toDouble())
        add(Float2(centre.x + radius * cos(angle).toFloat(), centre.y + radius * sin(angle).toFloat()))
    }
}

/** A point on [board] in the room. */
internal fun onBoard(board: Int, x: Float, y: Float, z: Float): Position {
    val angle = Math.toRadians(-LEAN.toDouble())
    val c = cos(angle).toFloat()
    val s = sin(angle).toFloat()
    return Position(boardX(board) + x, BOARD_Y + y * c - z * s, BOARD_Z + y * s + z * c)
}

/** The x of a column's centre line. */
internal fun columnX(column: Int) = (column - (COLUMNS - 1) / 2f) * PITCH

/** The lane under a tap, counted across both boards from the left: where [ray] meets a board's face between the rails. */
internal fun laneAt(ray: Ray): Int? {
    val (x, y) = hitTipped(ray, Position(0f, BOARD_Y, BOARD_Z), LEAN, FACE_Z) ?: return null
    if (y !in LEDGE_TOP..RAIL_TOP) return null
    for (board in 0 until BOARDS) {
        val across = x - boardX(board)
        val column = (across / PITCH + (COLUMNS - 1) / 2f).roundToInt()
        if (column in 0 until COLUMNS && abs(across - columnX(column)) <= PITCH / 2) return board * COLUMNS + column
    }
    return null
}

/**
 * Mattel's logo (from Wikipedia), kept out of the public repo: without it the board gets a hand-drawn flame instead.
 */
private const val LOGO = "brand/hot_wheels_logo.png"

/** The printed face (MDF, scorched edges, keyholes, logo) and the plain MDF of the rails, drawn off the main thread. */
class BoardArt(val face: Bitmap, val mdf: Bitmap) {
    companion object {
        fun draw(assets: AssetManager): BoardArt {
            val logo = runCatching { assets.open(LOGO).use(BitmapFactory::decodeStream) }.getOrNull()
            return BoardArt(drawFace(logo), mdf(256, 256, Random(7)))
        }
    }
}

/**
 * The boards on the wall: each face cut to the photo's outline with laser-scorched edges, the rails that hold
 * the cards' edges (lip, groove, the slot at the top, the joints between pieces) and the strip they stand on.
 * Tapping one brings the camera to face both.
 */
@Composable
fun SceneScope.BlisterBoards(art: BoardArt) {
    val face = remember(art) {
        materialLoader.createTextureInstance(ImageTexture.Builder().bitmap(art.face).build(engine), roughness = 0.92f)
    }
    val mdf = remember(art) {
        materialLoader.createTextureInstance(ImageTexture.Builder().bitmap(art.mdf).build(engine), roughness = 0.92f)
    }
    val scorched = remember(materialLoader) { materialLoader.createColorInstance(SCORCHED, roughness = 0.95f) }
    // Freed with the engine when the screen goes.
    val faceMesh = remember(engine) { faceGeometry().build(engine) }
    val edgeMesh = remember(engine) { edgeGeometry().build(engine) }
    val name = FOCUS + Focus.Board.name

    // Both boards share the meshes: a MeshNode leaves its buffers alone when it goes.
    for (board in 0 until BOARDS) key(board) {
        Node(position = Position(boardX(board), BOARD_Y, BOARD_Z), rotation = Rotation(x = -LEAN, y = 0f, z = 0f)) {
            MeshNode(PrimitiveType.TRIANGLES, faceMesh.vertexBuffer, faceMesh.indexBuffer, faceMesh.boundingBox, face, apply = { this.name = name })
            MeshNode(PrimitiveType.TRIANGLES, edgeMesh.vertexBuffer, edgeMesh.indexBuffer, edgeMesh.boundingBox, scorched, apply = { this.name = name })
            for (i in 0..COLUMNS) Rail((i - COLUMNS / 2f) * PITCH, mdf, scorched)
            CubeNode(
                size = Size(BOARD_W - 0.008f, LEDGE_TOP - 0.004f, GROOVE + LIP_D),
                center = Position(0f, (LEDGE_TOP + 0.004f) / 2, FACE_Z + (GROOVE + LIP_D) / 2),
                materialInstance = mdf,
            )
        }
    }
}

@Composable
private fun SceneScope.Rail(x: Float, mdf: MaterialInstance, scorched: MaterialInstance) {
    val length = RAIL_TOP - LEDGE_TOP
    val middle = (RAIL_TOP + LEDGE_TOP) / 2
    CubeNode(
        size = Size(0.004f, length, GROOVE),
        center = Position(x, middle, FACE_Z + GROOVE / 2),
        materialInstance = scorched,
    )
    CubeNode(
        size = Size(LIP_W, length, LIP_D),
        center = Position(x, middle, FACE_Z + GROOVE + LIP_D / 2),
        materialInstance = mdf,
    )
    // Burnt into the lip's face: the slot near the top and the cuts between the pieces.
    CubeNode(
        size = Size(0.0016f, 0.022f, 0.0006f),
        center = Position(x, RAIL_TOP - 0.022f, LIP_FRONT),
        materialInstance = scorched,
    )
    var y = LEDGE_TOP + JOINT
    while (y < RAIL_TOP - 0.05f) {
        CubeNode(size = Size(LIP_W, 0.0012f, 0.0006f), center = Position(x, y, LIP_FRONT), materialInstance = scorched)
        y += JOINT
    }
}

/** The face, textured with [BoardArt.face]: v runs up from the bitmap's last row, the board's bottom edge. */
private fun faceGeometry(): Geometry.Builder {
    val normal = Direction(0f, 0f, 1f)
    val vertices = OUTLINE.map {
        Geometry.Vertex(Position(it.x - BOARD_W / 2, it.y, FACE_Z), normal, UvCoordinate(it.x / BOARD_W, it.y / TAB_TOP))
    }
    val indices = Earcut.triangulate(OUTLINE).chunked(3).flatMap { (a, b, c) -> facing(vertices, a, b, c, normal) }
    return Geometry.Builder().vertices(vertices).indices(indices)
}

/** The cut edges all round, each side facing out. */
private fun edgeGeometry(): Geometry.Builder {
    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()
    for (i in OUTLINE.indices) {
        val from = OUTLINE[i]
        val to = OUTLINE[(i + 1) % OUTLINE.size]
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0f) continue
        // Outwards, as the outline runs counter-clockwise.
        val normal = Direction(dy / length, -dx / length, 0f)
        val first = vertices.size
        for ((point, z) in listOf(from to FACE_Z, to to FACE_Z, to to -FACE_Z, from to -FACE_Z)) {
            // SceneView's materials all read UVs, so even an untextured mesh needs them.
            vertices += Geometry.Vertex(Position(point.x - BOARD_W / 2, point.y, z), normal, UvCoordinate(0f, 0f))
        }
        indices += facing(vertices, first, first + 1, first + 2, normal)
        indices += facing(vertices, first, first + 2, first + 3, normal)
    }
    return Geometry.Builder().vertices(vertices).indices(indices)
}

/** Raw MDF: a warm, even brown, finely speckled and only faintly mottled, with the odd darker fleck of fibre. */
private fun mdf(width: Int, height: Int, random: Random): Bitmap {
    val cell = 10
    val gridW = width / cell + 2
    val coarse = FloatArray(gridW * (height / cell + 2)) { random.nextFloat() * 2 - 1 }
    val pixels = IntArray(width * height)
    val red = MDF.red * 255
    val green = MDF.green * 255
    val blue = MDF.blue * 255
    for (y in 0 until height) {
        val gy = y / cell
        val fy = (y % cell) / cell.toFloat()
        for (x in 0 until width) {
            val gx = x / cell
            val fx = (x % cell) / cell.toFloat()
            val top = coarse[gy * gridW + gx] * (1 - fx) + coarse[gy * gridW + gx + 1] * fx
            val bottom = coarse[(gy + 1) * gridW + gx] * (1 - fx) + coarse[(gy + 1) * gridW + gx + 1] * fx
            var shade = 1 + 0.025f * (top * (1 - fy) + bottom * fy) + 0.045f * (random.nextFloat() * 2 - 1)
            if (random.nextInt(900) == 0) shade *= 0.82f
            pixels[y * width + x] = (0xFF shl 24) or
                ((red * shade).roundToInt().coerceIn(0, 255) shl 16) or
                ((green * shade).roundToInt().coerceIn(0, 255) shl 8) or
                (blue * shade).roundToInt().coerceIn(0, 255)
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
}

private fun drawFace(logo: Bitmap?): Bitmap {
    val width = 720
    val scale = width / BOARD_W
    val height = (TAB_TOP * scale).roundToInt()
    val bitmap = mdf(width, height, Random(3))
    val canvas = Canvas(bitmap)
    fun px(x: Float) = x * scale
    fun py(y: Float) = (TAB_TOP - y) * scale

    // The laser leaves the cut edges dark and singes the face a little way in.
    val outline = Path().apply {
        OUTLINE.forEachIndexed { i, p -> if (i == 0) moveTo(px(p.x), py(p.y)) else lineTo(px(p.x), py(p.y)) }
        close()
    }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    canvas.drawPath(outline, stroke.apply { strokeWidth = 12f; color = 0x403B2618 })
    canvas.drawPath(outline, stroke.apply { strokeWidth = 4f; color = 0xD03B2618.toInt() })

    // The keyholes it could hang from.
    val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C120B.toInt() }
    for ((x, y) in listOf(0.041f to 0.685f, 0.424f to 0.698f)) {
        canvas.drawCircle(px(x), py(y), 0.0032f * scale, hole)
        canvas.drawRect(px(x) - 0.0009f * scale, py(y + 0.007f), px(x) + 0.0009f * scale, py(y), hole)
    }

    // The sticker on the tab, as wide as the photo's; its height follows the logo.
    if (logo != null) {
        val left = px(0.060f)
        val right = px(0.198f)
        val half = (right - left) * logo.height / logo.width / 2
        val target = RectF(left, py(0.720f) - half, right, py(0.720f) + half)
        // Halved first: one bilinear step from several times the size skips pixels and frays the thin outlines.
        var source: Bitmap = logo
        while (source.width > 2 * target.width()) {
            source = Bitmap.createScaledBitmap(source, source.width / 2, source.height / 2, true)
        }
        canvas.drawBitmap(source, null, target, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    } else {
        drawFlame(canvas, left = px(0.062f), top = py(0.740f), right = px(0.196f), bottom = py(0.700f))
    }
    return bitmap
}

/** A stand-in for the logo: a flame with the name on it. */
private fun drawFlame(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
    val w = right - left
    val h = bottom - top
    fun x(f: Float) = left + f * w
    fun y(f: Float) = top + f * h
    val flame = Path().apply {
        moveTo(x(0.20f), y(0.14f))
        lineTo(x(0.93f), y(0.10f))
        cubicTo(x(1.01f), y(0.10f), x(1.01f), y(0.90f), x(0.93f), y(0.90f))
        lineTo(x(0.30f), y(0.88f))
        quadTo(x(0.14f), y(0.98f), x(0.02f), y(0.92f))
        quadTo(x(0.14f), y(0.82f), x(0.18f), y(0.72f))
        quadTo(x(0.08f), y(0.70f), x(0.0f), y(0.56f))
        quadTo(x(0.12f), y(0.54f), x(0.17f), y(0.46f))
        quadTo(x(0.07f), y(0.36f), x(0.04f), y(0.22f))
        quadTo(x(0.14f), y(0.26f), x(0.20f), y(0.14f))
        close()
    }
    canvas.drawPath(flame, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD42A1E.toInt() })
    canvas.drawPath(flame, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = h * 0.06f; color = 0xFFFFC72C.toInt() })

    val text = "Hot Wheels"
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
        textSize = h * 0.56f
        textSkewX = -0.2f
    }
    val room = w * 0.66f
    paint.textScaleX = (room / paint.measureText(text)).coerceAtMost(1f)
    val baseline = y(0.5f) + paint.textSize * 0.36f
    val start = x(0.27f) + (room - paint.measureText(text)) / 2
    canvas.drawText(text, start, baseline, paint.apply { style = Paint.Style.STROKE; strokeWidth = h * 0.08f; color = 0xFF7A0E0E.toInt() })
    canvas.drawText(text, start, baseline, paint.apply { style = Paint.Style.FILL; color = 0xFFFFC72C.toInt() })
}
