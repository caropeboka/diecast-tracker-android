package io.github.caropeboka.diecasttracker.ui.garage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.sceneview.SceneScope
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import kotlin.math.cos
import kotlin.math.sin

// The user's nanoblock French bulldog on the desk, from their photo: white with a black saddle, black ears and eye
// patches, a red collar, standing on a lime green plate. Built brick by brick, studs and all; its own axes: x across,
// y up from the desk, z from its tail to its nose; sizes in metres.

/** A brick: 3.5 mm square, 3.2 mm high, like nanoblock's. */
private const val BRICK = 0.0035f
private const val LAYER = 0.0032f
private const val PLATE = 0.003f
private const val STUD_R = BRICK * 0.32f
private const val STUD_H = 0.0009f

/** The plate it stands on, in bricks: x −2..5, z −1..9. */
private const val PLATE_X0 = -2
private const val PLATE_X1 = 5
private const val PLATE_Z0 = -1
private const val PLATE_Z1 = 9

/** Turned so it looks out past the desk's front left corner, as on the desk in the photo. */
private const val DOG_YAW = -35f

/** What it takes up of the desk, turned as it stands: its plate's corners. */
internal val DOG_SIZE: Size = run {
    val w = (PLATE_X1 - PLATE_X0 + 1) * BRICK
    val d = (PLATE_Z1 - PLATE_Z0 + 1) * BRICK
    val angle = Math.toRadians(DOG_YAW.toDouble())
    val c = kotlin.math.abs(cos(angle).toFloat())
    val s = kotlin.math.abs(sin(angle).toFloat())
    Size(w * c + d * s, 0.045f, w * s + d * c)
}

/** Where it stands until it's moved: at the back, left of the middle, as in the photo (millimetres). */
internal val DOG_HOME = 385 to 45

internal const val DOG = "prop-dog"

/** How high it rises when picked, to show which. */
private const val DOG_LIFT = 0.015f

/**
 * The bricks, a layer at a time from the bottom: each row is one brick deep from the tail (first) to the nose
 * (last), each character a brick across: W white, K black, R red, . none.
 */
private val LAYERS: List<List<String>> = listOf(
    // Legs.
    listOf("....", "W..W", "....", "....", "....", "....", "W..W", "....", "...."),
    listOf("....", "W..W", "....", "....", "....", "....", "W..W", "....", "...."),
    listOf("....", "W..W", "....", "....", "....", "....", "W..W", "....", "...."),
    // The belly, then the black saddle down its sides, the collar and the lower jaw.
    listOf("....", "WWWW", "WWWW", "WWWW", "WWWW", "WWWW", "WWWW", "....", "...."),
    listOf("....", "KWWK", "KWWK", "KWWK", "WWWW", "WWWW", "WWWW", "WWWW", "WWWW"),
    listOf(".KK.", "KKKK", "KKKK", "KKKK", "KKKK", "RRRR", "WWWW", "WWWW", "WWWW"),
    // The head: the nose, the eye patches with the white blaze between them, the crown.
    listOf("....", "....", "....", "....", "....", "KWWK", "WWWW", "WWWW", "WKKW"),
    listOf("....", "....", "....", "....", "....", "KKKK", "KWWK", "KWWK", "KWWK"),
    listOf("....", "....", "....", "....", "....", "KKKK", "KWWK", "KWWK", "...."),
    // The bat ears.
    listOf("....", "....", "....", "....", "....", "....", "K..K", "K..K", "...."),
    listOf("....", "....", "....", "....", "....", "....", "K..K", "....", "...."),
    listOf("....", "....", "....", "....", "....", "....", "K..K", "....", "...."),
)

private val COLOURS = mapOf(
    'W' to Color(0xFFF2F1EC),
    'K' to Color(0xFF1B1B1D),
    'R' to Color(0xFFC8102E),
)
private val PLATE_GREEN = Color(0xFF8CCF3F)

/** The brick at ([x], [y], [z]), or ' ' outside the dog. */
private fun brick(x: Int, y: Int, z: Int): Char = LAYERS.getOrNull(y)?.getOrNull(z)?.getOrNull(x)?.takeIf { it != '.' } ?: ' '

/** Where a brick's middle is, the plate's middle at the origin. */
private fun brickCentre(x: Int, y: Int, z: Int) = Position(
    (x - (PLATE_X0 + PLATE_X1) / 2f) * BRICK,
    PLATE + y * LAYER + LAYER / 2,
    (z - (PLATE_Z0 + PLATE_Z1) / 2f) * BRICK,
)

private class MeshBuilder {
    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()

    /** A quad from its four corners, in either order round it, facing [normal]. */
    fun quad(a: Position, b: Position, c: Position, d: Position, normal: Direction) {
        val first = vertices.size
        listOf(a, b, c, d).forEach { vertices += Geometry.Vertex(it, normal, UvCoordinate(0f, 0f)) }
        indices += facing(vertices, first, first + 1, first + 2, normal)
        indices += facing(vertices, first, first + 2, first + 3, normal)
    }

    /** A box's faces, but those listed in [hidden] (0 −x, 1 +x, 2 −y, 3 +y, 4 −z, 5 +z). */
    fun box(centre: Position, size: Size, hidden: Set<Int> = emptySet()) {
        val (cx, cy, cz) = Triple(centre.x, centre.y, centre.z)
        val hx = size.x / 2
        val hy = size.y / 2
        val hz = size.z / 2
        fun p(x: Float, y: Float, z: Float) = Position(cx + x, cy + y, cz + z)
        if (0 !in hidden) quad(p(-hx, -hy, -hz), p(-hx, -hy, hz), p(-hx, hy, hz), p(-hx, hy, -hz), Direction(-1f, 0f, 0f))
        if (1 !in hidden) quad(p(hx, -hy, -hz), p(hx, hy, -hz), p(hx, hy, hz), p(hx, -hy, hz), Direction(1f, 0f, 0f))
        if (2 !in hidden) quad(p(-hx, -hy, -hz), p(hx, -hy, -hz), p(hx, -hy, hz), p(-hx, -hy, hz), Direction(0f, -1f, 0f))
        if (3 !in hidden) quad(p(-hx, hy, -hz), p(-hx, hy, hz), p(hx, hy, hz), p(hx, hy, -hz), Direction(0f, 1f, 0f))
        if (4 !in hidden) quad(p(-hx, -hy, -hz), p(-hx, hy, -hz), p(hx, hy, -hz), p(hx, -hy, -hz), Direction(0f, 0f, -1f))
        if (5 !in hidden) quad(p(-hx, -hy, hz), p(hx, -hy, hz), p(hx, hy, hz), p(-hx, hy, hz), Direction(0f, 0f, 1f))
    }

    /** A stud standing on [base]: its round side and its top. */
    fun stud(base: Position, segments: Int = 10) {
        val top = base.y + STUD_H
        for (i in 0 until segments) {
            val a = Math.toRadians(360.0 * i / segments)
            val b = Math.toRadians(360.0 * (i + 1) / segments)
            val ax = base.x + STUD_R * cos(a).toFloat()
            val az = base.z + STUD_R * sin(a).toFloat()
            val bx = base.x + STUD_R * cos(b).toFloat()
            val bz = base.z + STUD_R * sin(b).toFloat()
            val middle = (a + b) / 2
            quad(
                Position(ax, base.y, az),
                Position(bx, base.y, bz),
                Position(bx, top, bz),
                Position(ax, top, az),
                Direction(cos(middle).toFloat(), 0f, sin(middle).toFloat()),
            )
            val first = vertices.size
            val up = Direction(0f, 1f, 0f)
            listOf(Position(base.x, top, base.z), Position(ax, top, az), Position(bx, top, bz)).forEach {
                vertices += Geometry.Vertex(it, up, UvCoordinate(0f, 0f))
            }
            indices += facing(vertices, first, first + 1, first + 2, up)
        }
    }

    fun build() = Geometry.Builder().vertices(vertices).indices(indices)
}

/** One mesh per colour of brick: only the faces that show, a stud on every top left uncovered. */
private fun dogMeshes(): Map<Color, Geometry.Builder> {
    val builders = HashMap<Char, MeshBuilder>()
    for (y in LAYERS.indices) for (z in LAYERS[y].indices) for (x in LAYERS[y][z].indices) {
        val colour = brick(x, y, z)
        if (colour == ' ') continue
        val hidden = buildSet {
            if (brick(x - 1, y, z) != ' ') add(0)
            if (brick(x + 1, y, z) != ' ') add(1)
            // The legs stand on the plate.
            if (y == 0 || brick(x, y - 1, z) != ' ') add(2)
            if (brick(x, y + 1, z) != ' ') add(3)
            if (brick(x, y, z - 1) != ' ') add(4)
            if (brick(x, y, z + 1) != ' ') add(5)
        }
        val builder = builders.getOrPut(colour) { MeshBuilder() }
        val centre = brickCentre(x, y, z)
        builder.box(centre, Size(BRICK, LAYER, BRICK), hidden)
        if (3 !in hidden) builder.stud(Position(centre.x, centre.y + LAYER / 2, centre.z))
    }
    return builders.entries.associate { (colour, builder) -> COLOURS.getValue(colour) to builder.build() }
}

/** The green plate with its studs, but where the legs stand. */
private fun plateMesh(): Geometry.Builder {
    val builder = MeshBuilder()
    val width = (PLATE_X1 - PLATE_X0 + 1) * BRICK
    val depth = (PLATE_Z1 - PLATE_Z0 + 1) * BRICK
    builder.box(Position(0f, PLATE / 2, 0f), Size(width, PLATE, depth), hidden = setOf(2))
    for (x in PLATE_X0..PLATE_X1) for (z in PLATE_Z0..PLATE_Z1) {
        if (brick(x, 0, z) != ' ') continue
        val centre = brickCentre(x, 0, z)
        builder.stud(Position(centre.x, PLATE, centre.z))
    }
    return builder.build()
}

/** The dog standing where [placed] says on the desk; [lifted] while it is picked. */
@Composable
internal fun SceneScope.NanoDog(placed: PlacedDog, lifted: Boolean) {
    val lift by animateFloatAsState(if (lifted) DOG_LIFT else 0f, tween(250), label = "lift")
    // Freed with the engine when the screen goes.
    val meshes = remember(engine) { dogMeshes().mapValues { it.value.build(engine) } }
    val plate = remember(engine) { plateMesh().build(engine) }
    val materials = remember(materialLoader) {
        (COLOURS.values + PLATE_GREEN).associateWith { materialLoader.createColorInstance(it, roughness = 0.3f) }
    }
    val at = onDesk(placed.x / 1000f, placed.depth / 1000f)
    Node(position = Position(at.x, at.y + lift, at.z), rotation = Rotation(x = 0f, y = DOG_YAW, z = 0f)) {
        Mesh(plate, materials.getValue(PLATE_GREEN), name = DOG)
        meshes.forEach { (colour, mesh) -> Mesh(mesh, materials.getValue(colour), name = DOG) }
    }
}

/** The dog picked, what's going on, and what can be done with it. */
@Composable
internal fun DogBar(title: String, text: String, actions: @Composable () -> Unit) {
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
            Icon(
                painterResource(R.drawable.ic_pets),
                contentDescription = null,
                tint = Garage.Brand,
                modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(Garage.Muted).padding(8.dp),
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
        Row(Modifier.align(Alignment.End)) { actions() }
    }
}
