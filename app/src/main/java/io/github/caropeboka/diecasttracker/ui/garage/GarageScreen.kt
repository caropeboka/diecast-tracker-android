package io.github.caropeboka.diecasttracker.ui.garage

import android.content.ActivityNotFoundException
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.filament.Camera
import dev.romainguy.kotlin.math.Ray
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.Packaging
import io.github.caropeboka.diecasttracker.data.db.ShelfRow
import io.github.caropeboka.diecasttracker.data.shelf.Shelf
import io.github.caropeboka.diecasttracker.data.shelf.ShelfLayout
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.sharedDiecastCard
import io.github.caropeboka.diecasttracker.ui.sharedGarage
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.sceneview.RenderQuality
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.utils.motionEventToRay
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// The user's three shelves from their photo with the collection on them, each diecast where the collector put it:
// any compartment of the white case, any lane of the boards (cards slide down onto the ones there), anywhere along
// the black shelf's tiers. Sizes in metres; the back wall faces +z, the left wall +x.

/** The walls' faces: the black shelf stands in their corner. */
internal const val WALL_Z = -0.02f
internal const val LEFT_WALL_X = -0.98f

private const val ITEM = "item-"
internal const val FOCUS = "focus-"

/** A pulled diecast is held this many times its width in front of the eye: most of a portrait screen. */
private const val HOLD = 3.9f

/** How far a diecast being moved comes out of its place, of the way out (see [Item]). */
private const val LIFTED = 0.3f

/** Out of the tray into its place, or carried to another one by hand: through the place's way out. */
private const val CARRY_MS = 750

/** Pushed along by a neighbour. */
private const val NUDGE_MS = 320

/** From the hand into the tray. */
private const val TO_TRAY_MS = 450

/** Between diecasts coming out of the tray together, and at most before the last one sets off. */
private const val STAGGER_MS = 70
private const val MAX_STAGGER_MS = 1400

/** Things go into and come out of the tray this far in front of the eye, under its button, this big. */
private const val TRAY_DISTANCE = 0.3f
private const val TRAY_SIZE = 0.02f

/** The 3D view starts blank: what was on the screen stays over it until it has drawn this many frames. */
private const val READY_FRAMES = 3
private const val COVER_TIMEOUT_MS = 1500L
private const val COVER_FADE_MS = 250

/** About the lit wall's colour, over the view until it shows when nothing of it was kept. */
private val COVER = Color(0xFFDCD8D1)

/**
 * Render settings to try on the phone: full, without any post-processing, without shadows/AO/bloom. The emulator's
 * software GPU renders nothing but white with post-processing and aborts compiling the "Hemat" one.
 */
private val QUALITIES = listOf("Normal", "Polos", "Hemat")
private const val PLAIN = 1
private const val LIGHT = 2

/** What the camera looks at. The shelves' nodes are named after these, so tapping one brings it into view. */
enum class Focus(val label: String, val eye: Position, val target: Position) {
    /** From a little above, like the photo; seen level, the undersides of the case's shelves show as dark bars. */
    All("Semua", Position(0.08f, 1.87f, 5.6f), Position(0.08f, 0.48f, 0.1f)),

    /** From the front corner of the room, like the photo, so both walls' carving shows. */
    Shelf("Laci", Position(0.52f, 0.9f, 1.48f), Position(-0.78f, 0.55f, 0.18f)),

    /** Both boards across the screen. */
    Board("Papan", Position(BOARDS_X, 0.6f, 2.5f), Position(BOARDS_X, 0.4f, 0f)),
    Case("Rak putih", Position(CASE_X, 0.8f, 1.55f), Position(CASE_X, 0.25f, 0.06f)),
    ;

    internal val sight get() = Sight(eye, target)
}

/** The place under a tap on the furniture in view; none in the overview, where a tap picks the furniture. */
private fun spotAt(focus: Focus, ray: Ray): Spot? = when (focus) {
    Focus.All -> null
    Focus.Shelf -> tierAt(ray)?.let { (tier, angle) -> Spot(Shelf.Corner, tier, angle) }
    Focus.Board -> laneAt(ray)?.let { Spot(Shelf.Boards, it) }
    Focus.Case -> cellAt(ray)?.let { Spot(Shelf.Case, it) }
}

/** The furniture a diecast belongs on, to look at when it's picked from the tray. */
private fun home(row: ShelfRow) = when {
    row.packaging == Packaging.Carded -> Focus.Board
    ShelfLayout.fits(Shelf.Case, row.packaging, row.scale) -> Focus.Case
    else -> Focus.Shelf
}

/**
 * In front of the eye on its line of sight, facing it; never past what's in view when the camera is close to it.
 * (Plain arithmetic: kotlin-math's vector operators are inlined from JVM 21 bytecode, which this module's JVM 17
 * target can't take.)
 */
private fun held(sight: Sight, width: Float): Pose {
    val eye = sight.eye
    val dx = eye.x - sight.target.x
    val dy = eye.y - sight.target.y
    val dz = eye.z - sight.target.z
    val length = sqrt(dx * dx + dy * dy + dz * dz)
    val distance = minOf(width * HOLD, length * 0.7f) / length
    return facing(sight, Position(eye.x - dx * distance, eye.y - dy * distance, eye.z - dz * distance))
}

/** At [position], square to the line of sight. */
private fun facing(sight: Sight, position: Position): Pose {
    val dx = sight.eye.x - sight.target.x
    val dy = sight.eye.y - sight.target.y
    val dz = sight.eye.z - sight.target.z
    return Pose(
        position,
        Math.toDegrees(atan2(dx, dz).toDouble()).toFloat(),
        -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)).toDouble()).toFloat(),
    )
}

/** Held, its width fills the screen, unless it's tall enough for its height to. */
private fun holdWidth(size: Size) = maxOf(size.x, size.y / 1.6f)

/**
 * Where [placed] shows when held in front of [from], seen from [now] on a [width] × [height] view: left, top, right
 * and bottom in pixels; null when part of it is behind the eye.
 */
private fun heldCard(placed: Placed, from: Sight, now: Sight, width: Float, height: Float, fieldOfView: Float): FloatArray? {
    val pose = held(from, holdWidth(placed.size))
    val yaw = Math.toRadians(pose.yaw.toDouble())
    val pitch = Math.toRadians(pose.pitch.toDouble())
    val p = pose.position
    val corners = listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).map { (across, along) ->
        val x = across * placed.size.x / 2
        // Tipped about x, then turned about y, like its nodes.
        val y = along * placed.size.y / 2 * cos(pitch).toFloat()
        val z = along * placed.size.y / 2 * sin(pitch).toFloat()
        val corner = Position(
            p.x + x * cos(yaw).toFloat() + z * sin(yaw).toFloat(),
            p.y + y,
            p.z - x * sin(yaw).toFloat() + z * cos(yaw).toFloat(),
        )
        now.onScreen(corner, width, height, fieldOfView) ?: return null
    }
    return floatArrayOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
}

private val SightSaver = Saver<Sight?, FloatArray>(
    save = { it?.let { s -> floatArrayOf(s.eye.x, s.eye.y, s.eye.z, s.target.x, s.target.y, s.target.z) } },
    restore = { Sight(Position(it[0], it[1], it[2]), Position(it[3], it[4], it[5])) },
)

/** Where the scene and the tray button are, in pixels from the root's top left. */
private class ScreenSpots {
    var origin = Offset.Zero
    var size = IntSize.Zero
    var tray = Offset.Zero
}

/** How a diecast comes to where it's drawn: out of the tray, carried by hand, or pushed along by a neighbour. */
private class Motion(val from: Pose, val fromScale: Float, val viaClear: Boolean, val delay: Int) {
    val duration get() = if (viaClear) CARRY_MS else NUDGE_MS

    /** [k] of the way from [from] to [to]; through [clear], the place's way out, when carried. */
    fun along(clear: Pose, to: Pose, k: Float): Pose = when {
        !viaClear -> lerp(from, to, smooth(k))
        k < 0.6f -> lerp(from, clear, smooth(k / 0.6f))
        else -> lerp(clear, to, smooth((k - 0.6f) / 0.4f))
    }
}

/** A diecast on its way into the tray from where it was last drawn; out of its place first when it was in it. */
private class Ghost(val placed: Placed, val from: Pose, val to: Pose, val toScale: Float, val viaClear: Boolean)

/**
 * The shelves as last drawn, to tell what changed: what moved, what came out of the tray and what went into it.
 * Worked out while composing, so nothing is drawn for a frame where it no longer is.
 */
private class Stage {
    private var layout: Layout? = null
    private var shown: Set<Long> = emptySet()

    /** Where each diecast was last drawn. */
    val drawn = HashMap<Long, Pose>()

    /** Carried in the move under way: through their places' way out rather than straight. */
    var byHand: Set<Long> = emptySet()

    var motions: Map<Long, Motion> = emptyMap()
        private set

    val ghosts = mutableStateListOf<Ghost>()

    /** [tray]: where a diecast goes into or comes out of the tray, and how small; null before the screen is measured. */
    fun update(scene: GarageScene?, tray: (Placed) -> Pair<Pose, Float>?) {
        val now = scene?.layout
        if (now === layout) return
        val before = layout
        val wasShown = shown
        layout = now
        shown = scene?.rows?.map { it.id }?.toSet().orEmpty()
        if (before == null || now == null) {
            motions = emptyMap()
            return
        }
        val old = before.placed.associateBy { it.item.id }
        val stays = now.placed.map { it.item.id }.toSet()
        var arriving = 0
        motions = buildMap {
            now.placed.forEach { placed ->
                val id = placed.item.id
                val was = old[id]
                when {
                    // Sold or deleted ones never were in the tray; new ones come in without a show.
                    was == null -> if (id in wasShown) {
                        tray(placed)?.let { (pose, scale) ->
                            put(id, Motion(pose, scale, viaClear = true, delay = minOf(arriving++ * STAGGER_MS, MAX_STAGGER_MS)))
                        }
                    }
                    !same(was.rest, placed.rest) -> put(id, Motion(drawn[id] ?: was.rest, 1f, viaClear = id in byHand, delay = 0))
                }
            }
        }
        before.placed.filter { it.item.id !in stays && it.item.id in shown }.forEach { placed ->
            val from = drawn[placed.item.id] ?: placed.rest
            tray(placed)?.let { (pose, scale) -> ghosts += Ghost(placed, from, pose, scale, viaClear = near(from, placed.rest)) }
        }
        byHand = emptySet()
    }
}

/** The motion a diecast is on, and how far along it is. */
private class Trip {
    var motion: Motion? = null
        private set
    var progress = Animatable(1f)
        private set

    fun start(motion: Motion) {
        this.motion = motion
        progress = Animatable(0f)
    }
}

private fun same(a: Pose, b: Pose) = a.position == b.position && a.yaw == b.yaw && a.pitch == b.pitch

private fun near(a: Pose, b: Pose): Boolean {
    val dx = a.position.x - b.position.x
    val dy = a.position.y - b.position.y
    val dz = a.position.z - b.position.z
    return dx * dx + dy * dy + dz * dz < 0.005f * 0.005f
}

/** The 3D view's TextureView, to keep a picture of what it shows. */
private fun View.textureView(): TextureView? = when (this) {
    is TextureView -> this
    is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).textureView() }
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: GarageViewModel = viewModel(factory = GarageViewModel.Factory),
) {
    val scene by viewModel.scene.collectAsStateWithLifecycle()
    val art by viewModel.art.collectAsStateWithLifecycle()
    // Still in hand when its page closes again.
    var pulled by rememberSaveable { mutableStateOf<Long?>(null) }
    // Picked to move: the next tap on a place, or on another diecast to trade places, puts it there.
    var carrying by rememberSaveable { mutableStateOf<Long?>(null) }
    // Put somewhere, or into the tray: kept out of its place until the shelves show where it went.
    var landing by remember { mutableStateOf<Long?>(null) }
    var releasing by remember { mutableStateOf<Long?>(null) }
    var trayOpen by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf<Spot?>(null) }
    // A decoration picked (its bar shows), being moved, changed or deleted.
    var decorHeld by rememberSaveable { mutableStateOf<Long?>(null) }
    var decorMoving by rememberSaveable { mutableStateOf<Long?>(null) }
    var decorEditing by remember { mutableStateOf<Long?>(null) }
    var decorDeleting by remember { mutableStateOf<Long?>(null) }
    // Where a new decoration goes (tier, angle) while its photo is taken or picked, and the camera's file.
    var decorSpot by rememberSaveable { mutableStateOf<FloatArray?>(null) }
    var decorCamera by rememberSaveable { mutableStateOf<String?>(null) }
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val decorProgress by viewModel.progress.collectAsStateWithLifecycle()
    val pickDecor = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val at = decorSpot
        if (uri != null && at != null) viewModel.makeDecoration(Spot(Shelf.Corner, at[0].toInt(), at[1]), uri = uri)
    }
    val shootDecor = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val at = decorSpot
        val file = decorCamera?.let(::File)
        decorCamera = null
        if (taken && at != null && file != null) viewModel.makeDecoration(Spot(Shelf.Corner, at[0].toInt(), at[1]), file = file) else file?.delete()
    }
    var focus by rememberSaveable { mutableStateOf(Focus.All) }
    // A tap on the chip already chosen brings the camera back to where it started.
    var resets by remember { mutableIntStateOf(0) }
    // Where the camera was when the one in hand was pulled out, and where it was when its page opened.
    var heldFrom by rememberSaveable(stateSaver = SightSaver) { mutableStateOf<Sight?>(null) }
    var resume by rememberSaveable(stateSaver = SightSaver) { mutableStateOf<Sight?>(null) }
    // The diecast whose page was opened and its card on the screen then: the page shrinks back into it.
    var opened by rememberSaveable { mutableStateOf<Long?>(null) }
    var opening by rememberSaveable { mutableStateOf<FloatArray?>(null) }
    // Where the camera was when each diecast was pulled out: it comes up in front of that.
    val sights = remember {
        mutableStateMapOf<Long, Sight>().apply {
            val id = pulled
            val from = heldFrom
            if (id != null && from != null) put(id, from)
        }
    }
    var quality by remember { mutableIntStateOf(0) }
    val stage = remember { Stage() }
    val screen = remember { ScreenSpots() }
    val scope = rememberCoroutineScope()
    val host = LocalView.current

    // Gone from the shelves (into the tray, sold, deleted): nothing left to hold or move. Whatever was put somewhere
    // has got there.
    LaunchedEffect(scene) {
        val current = scene ?: return@LaunchedEffect
        if (current.rows.none { it.id == carrying }) carrying = null
        if (current.layout.placed.none { it.item.id == pulled }) pulled = null
        if (current.decor.none { it.id == decorHeld }) decorHeld = null
        if (current.decor.none { it.id == decorMoving }) decorMoving = null
        landing = null
        releasing = null
    }

    fun look(at: Focus) {
        resume = null
        if (at == focus) resets++ else focus = at
    }

    // What the 3D view showed when the screen was left (or the wall's colour), over it until it has drawn itself.
    var picture by remember { mutableStateOf(viewModel.snapshot).also { viewModel.snapshot = null } }
    val cover = remember { Animatable(1f) }
    var frames by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(COVER_TIMEOUT_MS) { snapshotFlow { frames }.first { it >= READY_FRAMES } }
        cover.animateTo(0f, tween(COVER_FADE_MS))
        picture = null
    }

    Scaffold(
        // Grows out of the dashboard's button and shrinks back into it.
        modifier = sharedGarage(page = true),
        topBar = {
            TopAppBar(
                title = { Heading("Garasi 3D") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali")
                    }
                },
                actions = {
                    // G0: to compare how the phone copes with each.
                    TextButton(onClick = { quality = (quality + 1) % QUALITIES.size }) { Text(QUALITIES[quality]) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .onGloballyPositioned {
                    screen.origin = it.positionInRoot()
                    screen.size = it.size
                },
        ) {
            val engine = rememberEngine()
            val materialLoader = rememberMaterialLoader(engine)
            val view = rememberView(engine)
            // Back from a diecast's page, where it was left.
            val start = resume ?: focus.sight
            val cameraNode = rememberCameraNode(engine) {
                position = start.eye
                lookAt(start.target)
            }
            val fieldOfView = { cameraNode.camera.getFieldOfViewInDegrees(Camera.Fov.VERTICAL).toFloat() }
            // A new one per focus or reset: SceneView glides the camera over from where it is. Kept across other
            // recompositions, which would otherwise swing it back.
            val camera = remember(focus, resets) { ShelfCamera(resume ?: focus.sight, fieldOfView) }
            // Where the camera really is, frame by frame: decorations turn to face it.
            val eye = remember { mutableStateOf(start.eye) }

            // Into and out of the tray just under its button, small, square to the line of sight.
            stage.update(scene) { placed ->
                val size = screen.size
                if (size.width == 0 || size.height == 0) return@update null
                val sight = camera.sight
                val x = screen.tray.x - screen.origin.x
                val y = screen.tray.y - screen.origin.y
                val at = sight.ahead(x, y, size.width.toFloat(), size.height.toFloat(), fieldOfView(), TRAY_DISTANCE)
                facing(sight, at) to TRAY_SIZE / maxOf(placed.size.x, placed.size.y)
            }

            fun pull(id: Long) {
                sights[id] = camera.sight
                heldFrom = camera.sight
                pulled = id
            }

            // The page grows out of the diecast in hand; on the way back, a picture of the scene covers it until
            // it's drawn again.
            fun open(id: Long) {
                val placed = scene?.layout?.placed?.find { it.item.id == id }
                val from = sights[id]
                val size = screen.size
                opening = if (placed != null && from != null && size.width > 0) {
                    heldCard(placed, from, camera.sight, size.width.toFloat(), size.height.toFloat(), fieldOfView())
                } else {
                    null
                }
                opened = id
                resume = camera.sight
                viewModel.snapshot = runCatching { host.textureView()?.bitmap }.getOrNull()
                onOpen(id)
            }

            LaunchedEffect(view, quality) { view.isPostProcessingEnabled = quality != PLAIN }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                // Not a SurfaceView: it fades and scales with the screen around it as screens give way.
                surfaceType = SurfaceType.TextureSurface,
                engine = engine,
                materialLoader = materialLoader,
                view = view,
                renderQuality = if (quality == LIGHT) RenderQuality.Performance else RenderQuality.Default,
                // A ceiling lamp in front of the shelves, so their faces and the cards are lit; the default sun shines
                // straight down and leaves anything upright dark. Nothing casts a shadow yet (see Mesh).
                mainLightNode = rememberMainLightNode(engine) {
                    lightDirection = Direction(0.15f, -0.55f, -0.82f)
                    intensity = 9_000f
                },
                fillLightNode = rememberFillLightNode(engine) {
                    lightDirection = Direction(-0.6f, -0.2f, -0.75f)
                    intensity = 3_500f
                },
                cameraNode = cameraNode,
                cameraManipulator = camera,
                autoCenterContent = false,
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = onTap@{ event, node ->
                        val current = viewModel.scene.value ?: return@onTap
                        val name = node?.name.orEmpty()
                        val moving = carrying
                        val tapped = name.takeIf { it.startsWith(ITEM) }?.removePrefix(ITEM)?.toLongOrNull()
                        val tappedDecor = name.takeIf { it.startsWith(DECOR) }?.removePrefix(DECOR)?.toLongOrNull()
                        if (tappedDecor != null) {
                            when {
                                decorMoving == tappedDecor -> decorMoving = null
                                moving != null || decorMoving != null -> viewModel.say("Di situ sudah ada hiasan.")
                                else -> {
                                    pulled = null
                                    decorHeld = if (decorHeld == tappedDecor) null else tappedDecor
                                }
                            }
                            return@onTap
                        }
                        decorHeld = null
                        val movingDecor = decorMoving
                        if (movingDecor != null) {
                            val spot = view.motionEventToRay(event)?.let { spotAt(focus, it) }
                            when {
                                spot?.shelf == Shelf.Corner -> scope.launch { if (viewModel.placeDecoration(movingDecor, spot)) decorMoving = null }
                                spot != null || tapped != null -> viewModel.say("Tap tempat kosong di laci hitam.")
                                name.startsWith(FOCUS) -> Focus.valueOf(name.removePrefix(FOCUS)).takeIf { it != focus }?.let(::look)
                            }
                            return@onTap
                        }
                        if (tapped != null) {
                            when (moving) {
                                null -> if (tapped == pulled) pulled = null else pull(tapped)
                                tapped -> carrying = null
                                else -> scope.launch {
                                    landing = moving
                                    stage.byHand = setOf(moving, tapped)
                                    if (viewModel.swap(moving, tapped)) carrying = null else landing = null
                                }
                            }
                            return@onTap
                        }
                        pulled = null
                        val spot = view.motionEventToRay(event)?.let { spotAt(focus, it) }
                        if (spot == null) {
                            if (name.startsWith(FOCUS)) Focus.valueOf(name.removePrefix(FOCUS)).takeIf { it != focus }?.let(::look)
                            return@onTap
                        }
                        val occupant = current.occupant(spot)
                        when {
                            moving != null -> scope.launch {
                                landing = moving
                                stage.byHand = setOf(moving)
                                if (viewModel.place(moving, spot)) carrying = null else landing = null
                            }
                            occupant != null -> pull(occupant)
                            current.fitting(spot).isNotEmpty() || spot.shelf == Shelf.Corner -> picking = spot
                            current.tray.isEmpty() -> viewModel.say("Nampan kosong: semua sudah di rak.")
                            else -> viewModel.say("Tidak ada diecast di nampan yang muat di sini.")
                        }
                    },
                ),
                onFrame = {
                    if (frames < READY_FRAMES && scene != null && art != null) frames++
                    val at = cameraNode.worldPosition
                    if (at != eye.value) eye.value = at
                },
            ) {
                Room()
                art?.let {
                    BlisterBoards(it.board)
                    CornerShelf(it.wood)
                }
                LooseCase()
                scene?.layout?.placed?.forEach { placed ->
                    val id = placed.item.id
                    key(id) {
                        val out = when (id) {
                            pulled, releasing -> 1f
                            carrying, landing -> LIFTED
                            else -> 0f
                        }
                        Item(placed, out, sights[id] ?: focus.sight, stage.motions[id], stage.drawn)
                    }
                }
                scene?.layout?.decor?.forEach { placed ->
                    key(DECOR + placed.item.id) {
                        Decoration(placed, eye, lifted = placed.item.id == decorHeld || placed.item.id == decorMoving)
                    }
                }
                stage.ghosts.forEach { ghost ->
                    key(ghost) { IntoTray(ghost) { stage.ghosts.remove(ghost) } }
                }
            }

            if (cover.value > 0f) {
                val shown = picture
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.value }.background(COVER)) {
                    if (shown != null) {
                        Image(shown.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
            }

            // Where the diecast in hand showed when its page opened: the page grows out of it and shrinks back.
            val card = opening
            val cardOf = pulled
            if (card != null && cardOf != null && cardOf == opened) {
                with(LocalDensity.current) {
                    Box(
                        Modifier
                            .offset { IntOffset(card[0].roundToInt(), card[1].roundToInt()) }
                            .size((card[2] - card[0]).toDp(), (card[3] - card[1]).toDp())
                            .then(sharedDiecastCard(cardOf)),
                    )
                }
            }

            scene?.let { current ->
                TrayButton(
                    current.tray.size,
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .onGloballyPositioned { screen.tray = it.boundsInRoot().center },
                ) { trayOpen = true }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val moving = carrying?.let { scene?.row(it) }
                val holding = pulled?.let { scene?.row(it) }
                val decorMoved = decorMoving?.let { scene?.decoration(it) }
                val decorPicked = decorHeld?.let { scene?.decoration(it) }
                when {
                    decorMoved != null -> DecorBar(decorMoved, "Tap tempat kosong di laci hitam.") {
                        TextButton(onClick = { decorMoving = null }) { Text("Batal") }
                    }
                    decorPicked != null -> DecorBar(decorPicked, "Hiasan · ${ShelfLayout.label(Shelf.Corner, decorPicked.tier)}") {
                        TextButton(
                            onClick = {
                                decorMoving = decorPicked.id
                                decorHeld = null
                                if (focus != Focus.Shelf) look(Focus.Shelf)
                            },
                        ) { Text("Pindahkan") }
                        TextButton(onClick = { decorEditing = decorPicked.id }) { Text("Ubah") }
                        TextButton(onClick = { decorDeleting = decorPicked.id }) { Text("Hapus") }
                    }
                    moving != null -> {
                        val hint = if (focus == Focus.All) {
                            "Pilih raknya di bawah, lalu tap tempatnya."
                        } else {
                            "Tap tempat kosong, atau diecast lain untuk bertukar tempat."
                        }
                        Bar(moving, hint) {
                            TextButton(onClick = { carrying = null }) { Text("Batal") }
                        }
                    }
                    holding != null -> Bar(holding, holding.slot?.let(ShelfLayout::label) ?: "Di nampan") {
                        TextButton(
                            onClick = {
                                carrying = holding.id
                                pulled = null
                            },
                        ) { Text("Pindahkan") }
                        TextButton(
                            onClick = {
                                releasing = holding.id
                                pulled = null
                                viewModel.release(holding.id)
                            },
                        ) { Text("Ke nampan") }
                        TextButton(onClick = { open(holding.id) }) { Text("Detail") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Focus.entries.forEach { option ->
                        FilterChip(
                            selected = option == focus,
                            onClick = {
                                pulled = null
                                look(option)
                            },
                            label = { Text(option.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Garage.Card,
                                selectedContainerColor = Garage.Brand,
                                selectedLabelColor = Garage.OnBrand,
                            ),
                        )
                    }
                }
            }
        }
    }

    val current = scene
    if (trayOpen && current != null) {
        TraySheet(
            title = "Belum ditaruh",
            hint = "Pilih satu, lalu tap tempatnya di rak. Lokasi simpannya ikut terisi.",
            rows = current.tray,
            withoutPhoto = current.withoutPhoto,
            onArrange = {
                trayOpen = false
                viewModel.arrangeAll()
            },
            onPick = { row ->
                trayOpen = false
                pulled = null
                carrying = row.id
                if (focus == Focus.All) look(home(row))
            },
            onDismiss = { trayOpen = false },
        )
    }
    val spot = picking
    if (spot != null && current != null) {
        TraySheet(
            title = "Taruh di sini",
            hint = ShelfLayout.label(spot.shelf, spot.spot),
            rows = current.fitting(spot),
            empty = "Tidak ada diecast di nampan yang muat di sini.",
            header = if (spot.shelf != Shelf.Corner) {
                null
            } else {
                {
                    NewDecorRow(
                        onCamera = {
                            picking = null
                            decorSpot = floatArrayOf(spot.spot.toFloat(), spot.angle)
                            val (file, uri) = viewModel.cameraTarget()
                            decorCamera = file.path
                            try {
                                shootDecor.launch(uri)
                            } catch (_: ActivityNotFoundException) {
                                viewModel.say("Tidak ada aplikasi kamera.")
                            }
                        },
                        onGallery = {
                            picking = null
                            decorSpot = floatArrayOf(spot.spot.toFloat(), spot.angle)
                            pickDecor.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                }
            },
            onPick = { row ->
                picking = null
                scope.launch { viewModel.place(row.id, spot) }
            },
            onDismiss = { picking = null },
        )
    }
    draft?.let { making ->
        DecorDraftSheet(making, decorProgress, onSave = viewModel::saveDecoration, onDismiss = viewModel::dropDraft)
    }
    decorEditing?.let { current?.decoration(it) }?.let { item ->
        DecorEditDialog(
            item,
            onSave = { name, heightMm ->
                decorEditing = null
                viewModel.editDecoration(item.id, name, heightMm)
            },
            onDismiss = { decorEditing = null },
        )
    }
    decorDeleting?.let { current?.decoration(it) }?.let { item ->
        DecorDeleteDialog(
            item,
            onConfirm = {
                decorDeleting = null
                decorHeld = null
                viewModel.deleteDecoration(item.id)
            },
            onDismiss = { decorDeleting = null },
        )
    }
}

/** Opens the tray, with how many diecasts wait for a place. */
@Composable
private fun TrayButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // One more once the diecast going in has landed, with a bump.
    var shown by remember { mutableIntStateOf(count) }
    val bump = remember { Animatable(1f) }
    LaunchedEffect(count) {
        if (count > shown) {
            delay(TO_TRAY_MS.toLong())
            shown = count
            bump.animateTo(1.15f, tween(110))
            bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
        } else {
            shown = count
        }
    }
    val shape = RoundedCornerShape(50)
    val content = if (shown > 0) Garage.OnBrand else MaterialTheme.colorScheme.onSurface
    Row(
        modifier
            .graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            }
            .clip(shape)
            .background(if (shown > 0) Garage.Brand else Garage.Card)
            .border(1.dp, Garage.Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(R.drawable.ic_collections_bookmark), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text("Nampan · $shown", style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** The diecast in hand, what's going on, and what can be done with it. */
@Composable
private fun Bar(row: ShelfRow, text: String, actions: @Composable () -> Unit) {
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
            DiecastPhoto(row.coverFile, contentDescription = null, modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small), placeholderSize = 20.dp)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
        Row(Modifier.align(Alignment.End)) { actions() }
    }
}

/** Diecasts waiting for a place: tap one to put it somewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TraySheet(
    title: String,
    hint: String,
    rows: List<ShelfRow>,
    withoutPhoto: Int = 0,
    empty: String = "Semua sudah di rak.",
    header: (@Composable () -> Unit)? = null,
    onArrange: (() -> Unit)? = null,
    onPick: (ShelfRow) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubHeading(title, Modifier.weight(1f))
                if (onArrange != null && rows.isNotEmpty()) TextButton(onClick = onArrange) { Text("Taruh semua otomatis") }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            header?.let {
                Box(Modifier.padding(top = 12.dp)) { it() }
            }
            if (rows.isEmpty()) {
                Text(empty, Modifier.padding(vertical = 24.dp), color = Garage.MutedForeground)
            }
            LazyColumn(Modifier.heightIn(max = 440.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.id }) { row -> TrayRow(row) { onPick(row) } }
            }
            if (withoutPhoto > 0) {
                Text(
                    "$withoutPhoto diecast tanpa foto tidak tampil di garasi.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
        }
    }
}

@Composable
private fun TrayRow(row: ShelfRow, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Garage.Muted)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiecastPhoto(row.coverFile, contentDescription = null, modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small), placeholderSize = 24.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(row.packaging.label, row.scale).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
            // Put somewhere that's run out of room (or no longer fits): it waits here again.
            row.slot?.let {
                Text("Tidak muat lagi di ${ShelfLayout.label(it)}", style = MaterialTheme.typography.bodySmall, color = Garage.Brand2)
            }
        }
    }
}

/** The back wall, the left one closing the corner the black shelf stands in, and the floor; big enough for "Semua". */
@Composable
private fun SceneScope.Room() {
    val wall = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFFE6E1D8), roughness = 0.35f) }
    val floor = remember(materialLoader) { materialLoader.createColorInstance(Color(0xFFECEAE5), roughness = 0.25f) }
    CubeNode(size = Size(8f, 3f, 0.02f), center = Position(LEFT_WALL_X + 4f, 1.5f, WALL_Z - 0.01f), materialInstance = wall)
    CubeNode(size = Size(0.02f, 3f, 8f), center = Position(LEFT_WALL_X - 0.01f, 1.5f, WALL_Z + 4f), materialInstance = wall)
    CubeNode(size = Size(8f, 0.02f, 8f), center = Position(LEFT_WALL_X + 4f, -0.01f, WALL_Z + 4f), materialInstance = floor)
}

/**
 * A diecast in its place, [out] of the way out: free of its shelf by about half (up the board's groove, off the black
 * shelf's tier, out of its compartment), then floating up to the camera. A [motion] brings it there from wherever it
 * was drawn last ([drawn]).
 */
@Composable
private fun SceneScope.Item(placed: Placed, out: Float, sight: Sight, motion: Motion?, drawn: MutableMap<Long, Pose>) {
    val t by animateFloatAsState(out, tween(1000, easing = LinearEasing), label = "pull")
    val slide = smooth(t / 0.55f)
    val forward = smooth((t - 0.45f) / 0.55f)
    val id = placed.item.id
    val target = lerp(lerp(placed.rest, placed.clear, slide), held(sight, holdWidth(placed.size)), forward)
    // A new motion sets off from where it's drawn now; until then it carries on with the one under way.
    val trip = remember { Trip() }
    if (motion != null && motion !== trip.motion) trip.start(motion)
    val progress = trip.progress
    LaunchedEffect(progress) {
        val on = trip.motion ?: return@LaunchedEffect
        progress.animateTo(1f, tween(on.duration, delayMillis = on.delay, easing = LinearEasing))
    }
    val on = trip.motion
    val k = progress.value
    val pose = if (on == null || k >= 1f) target else on.along(placed.clear, target, k)
    drawn[id] = pose
    Shown(
        placed,
        pose,
        scale = if (on == null) 1f else lerp(on.fromScale, 1f, smooth(k / 0.5f)),
        name = ITEM + id,
        // Waiting its turn to come out of the tray.
        visible = on == null || on.delay == 0 || k > 0f,
    )
}

/** Into the tray: out of its place first if it was in it, then shrinking away under the tray's button. */
@Composable
private fun SceneScope.IntoTray(ghost: Ghost, onGone: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(if (ghost.viaClear) CARRY_MS else TO_TRAY_MS, easing = LinearEasing))
        onGone()
    }
    val k = progress.value
    val out = if (ghost.viaClear) 0.35f else 0f
    val away = smooth((k - out) / (1 - out))
    val pose = if (k < out) {
        lerp(ghost.from, ghost.placed.clear, smooth(k / out))
    } else {
        lerp(if (ghost.viaClear) ghost.placed.clear else ghost.from, ghost.to, away)
    }
    // Not to be tapped: it's in the tray already.
    Shown(ghost.placed, pose, lerp(1f, ghost.toScale, away), name = "")
}

@Composable
private fun SceneScope.Shown(placed: Placed, pose: Pose, scale: Float, name: String, visible: Boolean = true) {
    val size = placed.size
    Node(position = pose.position, rotation = Rotation(x = 0f, y = pose.yaw, z = 0f), scale = Scale(scale), isVisible = visible) {
        Node(rotation = Rotation(x = pose.pitch, y = 0f, z = 0f)) {
            when (placed.item.packaging) {
                Packaging.Carded -> ImageNode(
                    bitmap = placed.item.image,
                    size = Size(size.x, size.y),
                    normal = Direction(z = 1f),
                    apply = { this.name = name },
                )
                Packaging.Boxed -> BoxModel(placed, name)
                Packaging.Loose -> LooseCar(placed, name)
            }
        }
    }
}

/** The box in the colour round its front, the photo on the front. */
@Composable
private fun SceneScope.BoxModel(placed: Placed, name: String) {
    val size = placed.size
    val side = remember(placed.item) { materialLoader.createColorInstance(placed.item.side, roughness = 0.45f) }
    CubeNode(size = size, center = Position(0f), materialInstance = side, apply = { this.name = name })
    ImageNode(
        bitmap = placed.item.image,
        size = Size(size.x, size.y),
        normal = Direction(z = 1f),
        position = Position(0f, 0f, size.z / 2 + 0.0008f),
        apply = { this.name = name },
    )
}

/**
 * The car's photo without its backdrop, standing up like a cut-out. Without reflectance: Filament's transparent
 * blending keeps a surface's sheen where it is see-through, which showed the whole rectangle as a pale pane; the
 * photo has the shine.
 */
@Composable
private fun SceneScope.LooseCar(placed: Placed, name: String) {
    val geometry = remember(engine, placed.size) { plane(placed.size.x, placed.size.y).build(engine) }
    val material = remember(placed.item) {
        materialLoader.createTextureInstance(
            ImageTexture.Builder().bitmap(placed.item.image).build(engine),
            isOpaque = false,
            roughness = 1f,
            reflectance = 0f,
        )
    }
    Mesh(geometry, material, name = name)
}

private fun smooth(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

private fun lerp(from: Pose, to: Pose, fraction: Float) = Pose(
    Position(
        lerp(from.position.x, to.position.x, fraction),
        lerp(from.position.y, to.position.y, fraction),
        lerp(from.position.z, to.position.z, fraction),
    ),
    lerp(from.yaw, to.yaw, fraction),
    lerp(from.pitch, to.pitch, fraction),
)
