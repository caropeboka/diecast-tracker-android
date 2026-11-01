package io.github.caropeboka.diecasttracker.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlin.math.asin
import kotlin.math.roundToInt

/** The main screens, one tap apart; the app opens on the collection. */
enum class MainTab(val label: String, @DrawableRes val icon: Int) {
    Collection("Koleksi", R.drawable.ic_car),
    Garage("Garasi", R.drawable.ic_dashboard),
    Explore("Peta", R.drawable.ic_map),
    Dex("Dex", R.drawable.ic_collections_bookmark),
}

/** What the bar's centre button offers; provided once by the root, so every tab has it. */
class CreateActions(
    /** Without a key, "Scan dengan AI" leads to the AI settings and says why. */
    val scanEnabled: Boolean,
    val onScan: () -> Unit,
    val onAdd: () -> Unit,
    val onCheck: () -> Unit,
    val menu: CreateMenu,
)

/** Whether the centre button's menu is open, and where the button is: its middle, from the root's top left. */
@Stable
class CreateMenu {
    var open by mutableStateOf(false)
    var anchor by mutableStateOf(Offset.Unspecified)
}

val LocalCreateActions = compositionLocalOf<CreateActions?> { null }

private val BAR_HEIGHT = 64.dp
private val BAR_CORNER = 20.dp

/** The centre button: its size, and how far below the bar's top edge its middle sits. */
private val BUMP = 66.dp
private val BUMP_DROP = 2.dp

/** The brand circle inside it. */
private val CIRCLE = 54.dp

/**
 * Koleksi · Garasi · ＋ · Peta · Dex: the centre button, a circle standing out above the bar, opens scan, add by hand
 * and the in-store check ([CreateMenuOverlay]).
 */
@Composable
fun MainNavigationBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    val actions = LocalCreateActions.current
    val (left, right) = MainTab.entries.chunked(2)
    val shape = RoundedCornerShape(topStart = BAR_CORNER, topEnd = BAR_CORNER)

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Garage.Card)
                .border(1.dp, Garage.Border, shape)
                .navigationBarsPadding()
                .height(BAR_HEIGHT)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            left.forEach { TabItem(it, current, onSelect) }
            if (actions != null) CreateLabel { actions.menu.open = true }
            right.forEach { TabItem(it, current, onSelect) }
        }
        if (actions != null) {
            CreateButton(
                // On the bar's top edge, but outside its size: the screens make room for the bar alone.
                Modifier
                    .align(Alignment.TopCenter)
                    .layout { measurable, constraints ->
                        val button = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) { button.place(-button.width / 2, BUMP_DROP.roundToPx() - button.height / 2) }
                    },
                onPlaced = { actions.menu.anchor = it },
            ) { actions.menu.open = true }
        }
    }
}

/** A tab: icon over label; the open one in the brand colour with a bolder label. */
@Composable
private fun RowScope.TabItem(tab: MainTab, current: MainTab, onSelect: (MainTab) -> Unit) {
    val selected = tab == current
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 32.dp),
                role = Role.Tab,
                onClick = { if (!selected) onSelect(tab) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painterResource(tab.icon),
            contentDescription = null,
            tint = if (selected) Garage.Brand else Garage.MutedForeground,
            modifier = Modifier.size(24.dp),
        )
        BarLabel(tab.label, if (selected) Garage.Foreground else Garage.MutedForeground, if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** The centre button's label, in line with the tabs' labels, under the circle. */
@Composable
private fun RowScope.CreateLabel(onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            // The circle above says what it does; this is only a bigger target.
            .clearAndSetSemantics {}
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Spacer(Modifier.size(24.dp))
        BarLabel("Tambah", Garage.Foreground, FontWeight.Normal)
    }
}

@Composable
private fun BarLabel(text: String, color: Color, weight: FontWeight) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = weight, maxLines = 1)
}

/** A brand circle in a ring of the bar's colour, so it looks lifted out of the bar; its outline carries the bar's. */
@Composable
private fun CreateButton(modifier: Modifier, onPlaced: (Offset) -> Unit, onClick: () -> Unit) {
    Box(
        modifier
            .size(BUMP)
            .onGloballyPositioned { onPlaced(it.boundsInRoot().center) }
            .drawBehind {
                drawCircle(Garage.Card)
                // The bar's top border, carried over the part above the bar.
                val stroke = 1.dp.toPx()
                val radius = size.minDimension / 2
                val above = Math.toDegrees(asin(BUMP_DROP.toPx() / radius).toDouble()).toFloat()
                drawArc(
                    Garage.Border,
                    startAngle = 180f + above,
                    sweepAngle = 180f - 2 * above,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke),
                )
            }
            .padding(6.dp)
            .clip(CircleShape)
            .background(Garage.Brand)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = "Tambah: scan, isi manual, atau cek di toko",
            tint = Garage.OnBrand,
            modifier = Modifier.size(30.dp),
        )
    }
}

/**
 * The centre button's menu, over every screen (Material's FAB menu): the screen dims, the button turns into a ×, and
 * the three ways in rise above it, scan last: closest to the thumb.
 */
@Composable
fun CreateMenuOverlay(actions: CreateActions) {
    val menu = actions.menu
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = menu.open
    val anchor = menu.anchor
    if (!visible.currentState && !visible.targetState || anchor == Offset.Unspecified) return

    BackHandler(enabled = menu.open) { menu.open = false }
    val transition = rememberTransition(visible, label = "create menu")
    val shown by transition.animateFloat({ tween(if (targetState) 220 else 160) }, label = "scrim") { if (it) 1f else 0f }
    // Once: a quick second tap while it closes shouldn't open a second form.
    val choose = { action: () -> Unit ->
        if (menu.open) {
            menu.open = false
            action()
        }
    }
    // The screen a choice opens grows out of it, and goes back into the button.
    val button = with(LocalDensity.current) { Spot(Rect(anchor, CIRCLE.toPx() / 2), Garage.Brand) }
    // Centred on the button, or above it.
    fun Modifier.at(above: Dp) = layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val bottom = if (above == Dp.Unspecified) placeable.height / 2 else above.roundToPx() + placeable.height
            placeable.place(anchor.x.roundToInt() - placeable.width / 2, anchor.y.roundToInt() - bottom)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown }
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(menu) { detectTapGestures { menu.open = false } },
        )
        Column(
            Modifier.at(above = CIRCLE / 2 + 16.dp).width(IntrinsicSize.Max),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MenuOption(transition, 2, R.drawable.ic_add, "Isi manual", button) { choose(actions.onAdd) }
            MenuOption(transition, 1, R.drawable.ic_search, "Cek di toko", button) { choose(actions.onCheck) }
            MenuOption(
                transition,
                0,
                R.drawable.ic_photo_camera,
                "Scan dengan AI",
                button,
                note = if (actions.scanEnabled) null else "Isi API key AI dulu",
                primary = true,
            ) { choose(actions.onScan) }
        }
        Box(
            Modifier
                .at(above = Dp.Unspecified)
                .size(CIRCLE)
                .clip(CircleShape)
                .background(Garage.Brand)
                .clickable(role = Role.Button) { menu.open = false },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = "Tutup",
                tint = Garage.OnBrand,
                modifier = Modifier.size(30.dp).graphicsLayer { rotationZ = 45f * shown },
            )
        }
    }
}

/** One way in: rises into place after the ones nearer the button ([order] 0 is nearest), goes before them. */
@Composable
private fun MenuOption(
    transition: Transition<Boolean>,
    order: Int,
    @DrawableRes icon: Int,
    label: String,
    back: Spot,
    note: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val shown by transition.animateFloat(
        { if (targetState) tween(220, delayMillis = order * 40, easing = FastOutSlowInEasing) else tween(140, delayMillis = (2 - order) * 30) },
        label = label,
    ) { if (it) 1f else 0f }
    val shape = RoundedCornerShape(50)
    val content = if (primary) Garage.OnBrand else Garage.Foreground

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown
                translationY = (1 - shown) * 24.dp.toPx()
                scaleX = 0.85f + 0.15f * shown
                scaleY = 0.85f + 0.15f * shown
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .then(revealSource(if (primary) Garage.Brand else Garage.Popover, back))
            .clip(shape)
            .background(if (primary) Garage.Brand else Garage.Popover)
            .border(1.dp, if (primary) Garage.Brand else Garage.Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 10.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (primary) Garage.OnBrand.copy(alpha = 0.14f) else Garage.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (primary) Garage.OnBrand else Garage.Brand, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.75f), maxLines = 1) }
        }
    }
}
