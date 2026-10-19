package io.github.caropeboka.diecasttracker.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

/** OpenFreeMap's dark style: OpenStreetMap data, free, no key, and already dark like the Garage theme. */
const val MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Where a map starts without anything better to show (web: the location picker's default). */
val JAKARTA = LatLng(-6.2, 106.82)

/**
 * A MapLibre map that follows the screen's lifecycle. [onReady] runs once the style is loaded, which is when
 * sources and layers can be added.
 */
@Composable
fun GarageMap(
    modifier: Modifier = Modifier,
    start: LatLng = JAKARTA,
    startZoom: Double = 11.0,
    onReady: (MapLibreMap, Style) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val ready by rememberUpdatedState(onReady)

    val holder = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            // Texture mode: the map can sit in a scrolling, rounded card.
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(true)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .compassEnabled(false)
            .camera(
                CameraPosition.Builder().target(start).zoom(startZoom).build(),
            )
        val mapView = MapView(context, options).apply { onCreate(null) }
        mapView.getMapAsync { map ->
            with(density) {
                val margin = 8.dp.roundToPx()
                map.uiSettings.setAttributionMargins(margin, 0, 0, margin)
            }
            map.uiSettings.setAttributionTintColor(Garage.MutedForeground.toArgb())
            map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style -> ready(map, style) }
        }
        TouchGrabbingLayout(context).apply { addView(mapView) } to mapView
    }

    DisposableEffect(lifecycle) {
        val mapView = holder.second
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { holder.first }, modifier = modifier)
}

/** Keeps a scrolling parent from stealing the map's drags. */
@SuppressLint("ViewConstructor")
private class TouchGrabbingLayout(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }
}

/** "#rrggbb" for MapLibre's paint properties. */
fun Color.toHex(): String = String.format(Locale.ROOT, "#%06x", toArgb() and 0xFFFFFF)
