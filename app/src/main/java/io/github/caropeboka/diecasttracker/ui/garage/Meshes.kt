package io.github.caropeboka.diecasttracker.ui.garage

import androidx.compose.runtime.Composable
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.SceneScope
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.triangulation.Earcut
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Shapes for the furniture, built in code: turned on a lathe, cut out of a sheet, or flat. Sizes in metres. SceneView's
// materials all read UVs, so every vertex gets one.

/**
 * A shape turned on a lathe: [profile] (radius, height) swept round the y axis from [from] to [to] degrees, 0° along
 * +x and 90° along +z. The profile runs with the material on its right (top, outside, bottom); bends sharper than
 * [crease] degrees stay sharp, gentler ones are smoothed. One unit of uv per [uvScale] metres.
 */
internal fun lathe(
    profile: List<Float2>,
    from: Float = 0f,
    to: Float = 360f,
    segments: Int = 20,
    crease: Float = 40f,
    uvScale: Float = 0.25f,
): Geometry.Builder {
    val normals = (0 until profile.size - 1).map { i ->
        val dr = profile[i + 1].x - profile[i].x
        val dy = profile[i + 1].y - profile[i].y
        val length = sqrt(dr * dr + dy * dy)
        if (length == 0f) null else Float2(-dy / length, dr / length)
    }
    val along = FloatArray(profile.size)
    for (i in 1 until profile.size) along[i] = along[i - 1] + distance(profile[i - 1], profile[i])
    val limit = cos(Math.toRadians(crease.toDouble())).toFloat()

    // Segment [s]'s normal at its end [i], shared with the neighbouring segment across a gentle bend.
    fun normalAt(i: Int, s: Int): Float2 {
        val own = normals[s]!!
        val other = normals.getOrNull(if (i == s) s - 1 else s + 1) ?: return own
        if (own.x * other.x + own.y * other.y < limit) return own
        val x = own.x + other.x
        val y = own.y + other.y
        val length = sqrt(x * x + y * y)
        return Float2(x / length, y / length)
    }

    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()
    for (s in normals.indices) {
        if (normals[s] == null) continue
        val first = vertices.size
        for (k in 0..segments) {
            val angle = Math.toRadians((from + (to - from) * k / segments).toDouble()).toFloat()
            val c = cos(angle)
            val sn = sin(angle)
            for (i in s..s + 1) {
                val p = profile[i]
                val n = normalAt(i, s)
                vertices += Geometry.Vertex(
                    Position(p.x * c, p.y, p.x * sn),
                    Direction(n.x * c, n.y, n.x * sn),
                    UvCoordinate(angle * p.x / uvScale, along[i] / uvScale),
                )
            }
        }
        for (k in 0 until segments) {
            val a = first + k * 2
            indices += facing(vertices, a, a + 2, a + 3)
            indices += facing(vertices, a, a + 3, a + 1)
        }
    }
    return Geometry.Builder().vertices(vertices).indices(indices)
}

/**
 * A piece [depth] thick cut to [outline] with [holes] through it, its front facing +z, centred on z = 0. Either
 * winding will do. One unit of uv per [uvScale] metres, across the faces and along the cut edges.
 */
internal fun extrude(outline: List<Float2>, holes: List<List<Float2>>, depth: Float, uvScale: Float = 0.25f): Geometry.Builder {
    // Counter-clockwise outside and clockwise holes, so every cut edge faces away from the material on its left.
    val outer = if (area(outline) < 0) outline.reversed() else outline
    val inner = holes.map { if (area(it) > 0) it.reversed() else it }
    val rings = listOf(outer) + inner
    val path = rings.flatten()
    val holeStarts = inner.runningFold(outer.size) { start, hole -> start + hole.size }.dropLast(1)
    val triangles = Earcut.triangulate(path, holeStarts).chunked(3)

    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()
    for (side in listOf(1f, -1f)) {
        val first = vertices.size
        val normal = Direction(0f, 0f, side)
        path.forEach { vertices += Geometry.Vertex(Position(it.x, it.y, side * depth / 2), normal, UvCoordinate(it.x / uvScale, it.y / uvScale)) }
        triangles.forEach { (a, b, c) -> indices += facing(vertices, first + a, first + b, first + c, normal) }
    }
    for (ring in rings) {
        var u = 0f
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            val length = distance(a, b)
            if (length == 0f) continue
            val normal = Direction((b.y - a.y) / length, -(b.x - a.x) / length, 0f)
            val first = vertices.size
            for ((point, z, v) in listOf(Triple(a, depth / 2, u), Triple(b, depth / 2, u + length), Triple(b, -depth / 2, u + length), Triple(a, -depth / 2, u))) {
                vertices += Geometry.Vertex(Position(point.x, point.y, z), normal, UvCoordinate(v / uvScale, (z + depth / 2) / uvScale))
            }
            indices += facing(vertices, first, first + 1, first + 2, normal)
            indices += facing(vertices, first, first + 2, first + 3, normal)
            u += length
        }
    }
    return Geometry.Builder().vertices(vertices).indices(indices)
}

/** A rectangle [width] × [height] facing +z, centred on the origin, with the whole texture on it the right way up. */
internal fun plane(width: Float, height: Float): Geometry.Builder {
    val normal = Direction(0f, 0f, 1f)
    val vertices = listOf(
        Geometry.Vertex(Position(-width / 2, -height / 2, 0f), normal, UvCoordinate(0f, 0f)),
        Geometry.Vertex(Position(width / 2, -height / 2, 0f), normal, UvCoordinate(1f, 0f)),
        Geometry.Vertex(Position(width / 2, height / 2, 0f), normal, UvCoordinate(1f, 1f)),
        Geometry.Vertex(Position(-width / 2, height / 2, 0f), normal, UvCoordinate(0f, 1f)),
    )
    return Geometry.Builder().vertices(vertices).indices(listOf(0, 1, 2, 0, 2, 3))
}

/**
 * [geometry] at [position], optionally named for taps. Casts no shadow, like every node by default: with shadow
 * casters in the scene, the emulator's software GPU paints anything in a plain colour that receives them red.
 */
@Composable
internal fun SceneScope.Mesh(
    geometry: Geometry,
    material: MaterialInstance,
    position: Position = Position(0f),
    rotation: Rotation = Rotation(x = 0f, y = 0f, z = 0f),
    name: String? = null,
) {
    Node(position = position, rotation = rotation) {
        MeshNode(
            PrimitiveType.TRIANGLES,
            geometry.vertexBuffer,
            geometry.indexBuffer,
            geometry.boundingBox,
            material,
            apply = { if (name != null) this.name = name },
        )
    }
}

/** The triangle wound so its front faces [normal] (back faces are culled); by default, its vertices' normals. */
internal fun facing(
    vertices: List<Geometry.Vertex>,
    a: Int,
    b: Int,
    c: Int,
    normal: Direction = listOf(a, b, c).map { vertices[it].normal!! }.let { (p, q, r) -> Direction(p.x + q.x + r.x, p.y + q.y + r.y, p.z + q.z + r.z) },
): List<Int> {
    val p = vertices[a].position
    val q = vertices[b].position
    val r = vertices[c].position
    val ux = q.x - p.x
    val uy = q.y - p.y
    val uz = q.z - p.z
    val vx = r.x - p.x
    val vy = r.y - p.y
    val vz = r.z - p.z
    val dot = (uy * vz - uz * vy) * normal.x + (uz * vx - ux * vz) * normal.y + (ux * vy - uy * vx) * normal.z
    return if (dot >= 0) listOf(a, b, c) else listOf(a, c, b)
}

/** Twice the signed area: positive when [ring] runs counter-clockwise. */
private fun area(ring: List<Float2>): Float =
    ring.indices.sumOf { i ->
        val a = ring[i]
        val b = ring[(i + 1) % ring.size]
        (a.x * b.y - b.x * a.y).toDouble()
    }.toFloat()

private fun distance(a: Float2, b: Float2): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}
