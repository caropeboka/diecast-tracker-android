package io.github.caropeboka.diecasttracker.ui.garage

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// The garage's camera, like a map's: one finger turns round what's in view, two fingers zoom towards the point
// between them and drag the view along at the same time, a double tap zooms in on the spot (a two-finger tap out).
// SceneView's own always zooms to the middle of the screen and drags with two fingers only while they keep their
// distance, so a board's sides and bottom couldn't be looked at closely. Plain arithmetic, like [held].

/** Radians per pixel of a one-finger drag. */
private const val TURN = 0.0035f

/** How far round from where it started the camera may turn, and how far down or up it may look. */
private const val MAX_YAW = 1f
private const val MIN_PITCH = -0.1f
private const val MAX_PITCH = 1.2f

/** Nearest to what's in view, and farthest out of the distance it started at. */
private const val MIN_DISTANCE = 0.15f
private const val MAX_OUT = 1.3f

private const val TAP_ZOOM = 0.5f
private const val TAP_ZOOM_SECONDS = 0.3f

/** What the camera looks at stays on the furniture or near it; the camera itself in front of the walls. */
private const val LOOK_LEFT = LEFT_WALL_X + 0.1f
private const val LOOK_RIGHT = CASE_X + 0.6f
private const val WALL_GAP = 0.08f

private val UP = floatArrayOf(0f, 1f, 0f)

/** Where the camera is and what it looks at. */
internal class Sight(val eye: Position, val target: Position) {
    private val eyeArray get() = floatArrayOf(eye.x, eye.y, eye.z)
    private val targetArray get() = floatArrayOf(target.x, target.y, target.z)

    /**
     * The point [distance] ahead of the eye under ([x], [y]), in pixels from the top left of a [width] × [height]
     * view seen with a vertical [fieldOfView] in degrees.
     */
    fun ahead(x: Float, y: Float, width: Float, height: Float, fieldOfView: Float, distance: Float): Position {
        val (forward, right, up) = axes(eyeArray, targetArray)
        val tanY = tan(Math.toRadians(fieldOfView / 2.0)).toFloat()
        val across = (2 * x / width - 1) * tanY * width / height
        val along = (1 - 2 * y / height) * tanY
        return Position(
            eye.x + (forward[0] + right[0] * across + up[0] * along) * distance,
            eye.y + (forward[1] + right[1] * across + up[1] * along) * distance,
            eye.z + (forward[2] + right[2] * across + up[2] * along) * distance,
        )
    }

    /** Where [point] shows in such a view, in pixels from its top left; null behind the eye. */
    fun onScreen(point: Position, width: Float, height: Float, fieldOfView: Float): Pair<Float, Float>? {
        val (forward, right, up) = axes(eyeArray, targetArray)
        val v = floatArrayOf(point.x - eye.x, point.y - eye.y, point.z - eye.z)
        val depth = dot(v, forward)
        if (depth <= 0) return null
        val tanY = tan(Math.toRadians(fieldOfView / 2.0)).toFloat()
        val across = dot(v, right) / (depth * tanY * width / height)
        val along = dot(v, up) / (depth * tanY)
        return (across + 1) / 2 * width to (1 - along) / 2 * height
    }
}

/** [fieldOfView]: the camera's vertical one, in degrees, to know which point lies under the fingers. */
internal class ShelfCamera(start: Sight, private val fieldOfView: () -> Float) : CameraGestureDetector.CameraManipulator {
    private val eye = floatArrayOf(start.eye.x, start.eye.y, start.eye.z)
    private val target = floatArrayOf(start.target.x, start.target.y, start.target.z)
    private val farthest = distance() * MAX_OUT
    private val homeYaw = minus(eye, target).let { atan2(it[0], it[2]) }
    private var width = 1
    private var height = 1
    private var lastX = 0
    private var lastY = 0
    private var panning = false

    /** A double tap's zoom on its way. */
    private class Glide(val eye: FloatArray, val target: FloatArray, val toEye: FloatArray, val toTarget: FloatArray) {
        var time = 0f
    }

    private var glide: Glide? = null

    val sight get() = Sight(Position(eye[0], eye[1], eye[2]), Position(target[0], target[1], target[2]))

    override fun setViewport(width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
    }

    /** The camera's frame: x to the right, y up, looking down -z, at the eye. */
    override fun getTransform(): Mat4 {
        val (forward, right, up) = axes()
        return Mat4(
            Float4(right[0], right[1], right[2], 0f),
            Float4(up[0], up[1], up[2], 0f),
            Float4(-forward[0], -forward[1], -forward[2], 0f),
            Float4(eye[0], eye[1], eye[2], 1f),
        )
    }

    // Touch coordinates come with y up from the bottom of the view.

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        glide = null
        lastX = x
        lastY = y
        panning = strafe
    }

    override fun grabUpdate(x: Int, y: Int) {
        if (panning) drag(x - lastX, y - lastY) else turn(x - lastX, y - lastY)
        lastX = x
        lastY = y
    }

    override fun grabEnd() = Unit

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        glide = null
        lastX = x
        lastY = y
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
        drag(x - lastX, y - lastY)
        if (prevSeparation > 0 && currSeparation > 0) zoom(x, y, prevSeparation / currSeparation)
        lastX = x
        lastY = y
    }

    override fun scrollEnd() = Unit

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        val fromEye = eye.copyOf()
        val fromTarget = target.copyOf()
        zoom(x, y, if (zoomIn) TAP_ZOOM else 1 / TAP_ZOOM)
        glide = Glide(fromEye, fromTarget, eye.copyOf(), target.copyOf())
        fromEye.copyInto(eye)
        fromTarget.copyInto(target)
    }

    override fun update(deltaTime: Float) {
        val on = glide ?: return
        on.time += deltaTime
        val t = (on.time / TAP_ZOOM_SECONDS).coerceIn(0f, 1f)
        val k = t * t * (3 - 2 * t)
        for (i in 0..2) {
            eye[i] = on.eye[i] + (on.toEye[i] - on.eye[i]) * k
            target[i] = on.target[i] + (on.toTarget[i] - on.target[i]) * k
        }
        if (t >= 1f) glide = null
    }

    override val isFrameActive get() = glide != null

    /** The point under ([x], [y]) on the plane through what's in view, square to the line of sight. */
    internal fun pointAt(x: Int, y: Int): Position {
        val (forward, right, up) = axes()
        val tanY = tan(Math.toRadians(fieldOfView() / 2.0)).toFloat()
        val tanX = tanY * width / height
        val across = (2f * x / width - 1) * tanX
        val along = (2f * y / height - 1) * tanY
        val d = distance()
        val p = FloatArray(3) { eye[it] + (forward[it] + right[it] * across + up[it] * along) * d }
        return Position(p[0], p[1], p[2])
    }

    /** Round what's in view: the scene follows the finger. */
    private fun turn(dx: Int, dy: Int) {
        val offset = minus(eye, target)
        val radius = length(offset)
        val yaw = (atan2(offset[0], offset[2]) - dx * TURN).coerceIn(homeYaw - MAX_YAW, homeYaw + MAX_YAW)
        val pitch = (asin(offset[1] / radius) - dy * TURN).coerceIn(MIN_PITCH, MAX_PITCH)
        eye[0] = target[0] + radius * sin(yaw) * cos(pitch)
        eye[1] = target[1] + radius * sin(pitch)
        eye[2] = target[2] + radius * cos(yaw) * cos(pitch)
        keepInRoom()
    }

    /** Sideways and up or down, so what's in view moves with the fingers. */
    private fun drag(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val (_, right, up) = axes()
        val perPixel = 2 * distance() * tan(Math.toRadians(fieldOfView() / 2.0)).toFloat() / height
        for (i in 0..2) {
            val move = -(right[i] * dx + up[i] * dy) * perPixel
            eye[i] += move
            target[i] += move
        }
        keepInRoom()
    }

    /** Nearer ([scale] below 1) or farther, keeping the point under ([x], [y]) where it is on the screen. */
    private fun zoom(x: Int, y: Int, scale: Float) {
        val d = distance()
        val s = scale.coerceIn(MIN_DISTANCE / d, maxOf(farthest / d, MIN_DISTANCE / d))
        val p = pointAt(x, y)
        val fixed = floatArrayOf(p.x, p.y, p.z)
        for (i in 0..2) {
            eye[i] = fixed[i] + (eye[i] - fixed[i]) * s
            target[i] = fixed[i] + (target[i] - fixed[i]) * s
        }
        keepInRoom()
    }

    private fun keepInRoom() {
        val shift = floatArrayOf(
            target[0].coerceIn(LOOK_LEFT, LOOK_RIGHT) - target[0],
            target[1].coerceIn(0f, 1.4f) - target[1],
            target[2].coerceIn(WALL_Z, 1.2f) - target[2],
        )
        for (i in 0..2) {
            target[i] += shift[i]
            eye[i] += shift[i]
        }
        eye[0] = eye[0].coerceAtLeast(LEFT_WALL_X + WALL_GAP)
        eye[1] = eye[1].coerceIn(0.05f, 2.8f)
        eye[2] = eye[2].coerceAtLeast(WALL_Z + WALL_GAP)
    }

    private fun distance() = length(minus(target, eye))

    private fun axes() = axes(eye, target)
}

/** Forward, right and up of a camera at [eye] looking at [target], as unit vectors. */
private fun axes(eye: FloatArray, target: FloatArray): Triple<FloatArray, FloatArray, FloatArray> {
    val forward = normalized(minus(target, eye))
    val right = normalized(cross(forward, UP))
    return Triple(forward, right, cross(right, forward))
}

private fun minus(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun length(a: FloatArray) = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

private fun normalized(a: FloatArray): FloatArray {
    val l = length(a)
    return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
}

private fun cross(a: FloatArray, b: FloatArray) =
    floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
