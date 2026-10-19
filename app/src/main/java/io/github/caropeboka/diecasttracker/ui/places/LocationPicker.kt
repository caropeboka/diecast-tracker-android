package io.github.caropeboka.diecasttracker.ui.places

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.data.places.DeviceLocation
import io.github.caropeboka.diecasttracker.data.places.GpsFix
import io.github.caropeboka.diecasttracker.data.places.LocationException
import io.github.caropeboka.diecasttracker.data.places.PlaceLookup
import io.github.caropeboka.diecasttracker.data.places.PlaceName
import io.github.caropeboka.diecasttracker.data.places.PlaceResult
import io.github.caropeboka.diecasttracker.data.places.PlaceSource
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.data.places.round6
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.map.GarageMap
import io.github.caropeboka.diecasttracker.ui.map.JAKARTA
import io.github.caropeboka.diecasttracker.ui.map.toHex
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val CLOSE_ZOOM = 17.0

/** The usual "you are here" blue, so it can't be mistaken for the pin. */
private val GPS_BLUE = Color(0xFF3B82F6)

private sealed interface Lookup {
    data object Idle : Lookup

    data object Loading : Lookup

    data class Done(val result: PlaceName) : Lookup
}

/**
 * Map for choosing a location (web: `location-picker.tsx`): the pin stays in the middle and the map moves under
 * it, so a GPS fix taken outside can be dragged into the store before saving. The name follows the pin until
 * the collector types or picks one.
 */
@Composable
fun LocationPicker(initial: PlaceValue, onDismiss: () -> Unit, onPick: (PlaceValue) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val state = remember { PickerState(initial, container.places, container.location, scope) }

    var autoLocate by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        when {
            granted.values.any { it } -> state.locate(auto = autoLocate)
            !autoLocate -> state.gpsError = "Izin lokasi ditolak. Geser peta atau cari tempatnya."
        }
    }

    fun locate(auto: Boolean) {
        autoLocate = auto
        if (container.location.hasPermission()) state.locate(auto) else permission.launch(DeviceLocation.PERMISSIONS)
    }

    // Opened without a point: start from where the phone is.
    LaunchedEffect(Unit) {
        if (!initial.hasPoint) locate(auto = true)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Garage.Background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Batal")
                    }
                    Heading("Pilih lokasi")
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Geser peta sampai pin tepat di tempatnya, atau cari nama tempatnya.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Garage.MutedForeground,
                    )
                    SearchRow(state)
                    state.results?.let { PlaceList(it, state::choose) } ?: RecentPlaces(state.recent, state::choose)
                    MapWithPin(state, onLocate = { locate(auto = false) })
                    state.gpsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Loss) }
                    TextInput(
                        state.name,
                        { text ->
                            state.name = text
                            state.nameLocked = text.isNotBlank()
                        },
                        "Nama tempat",
                        placeholder = "Nama toko / tempat",
                    )
                    NameNote(state.lookup, state.name) { suggested ->
                        state.name = suggested
                        state.nameLocked = true
                    }
                    if (state.placed) {
                        Text(
                            coordinates(state.center.latitude, state.center.longitude, state.gps?.accuracy?.takeIf { state.atGps }),
                            style = MaterialTheme.typography.labelSmall,
                            color = Garage.MutedForeground,
                        )
                    }
                }

                HorizontalDivider(color = Garage.Border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss, shape = MaterialTheme.shapes.medium) { Text("Batal") }
                    Button(
                        onClick = {
                            onPick(state.confirm())
                            onDismiss()
                        },
                        enabled = state.placed && !state.waitingForName,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            when {
                                !state.placed -> "Geser peta ke lokasinya"
                                state.waitingForName -> "Mencari nama tempat…"
                                else -> "Pakai lokasi ini"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(state: PickerState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.query,
            onValueChange = {
                state.query = it
                if (it.isEmpty()) state.results = null
            },
            placeholder = { Text("Cari tempat, mis. Toys Kingdom GI") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.search() }),
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = { state.search() },
            enabled = !state.searching && state.query.isNotBlank(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Cari")
        }
    }
}

@Composable
private fun RecentPlaces(places: List<PlaceResult>, onChoose: (PlaceResult) -> Unit) {
    if (places.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(places, key = { "${it.name}:${it.lat}" }) { place ->
            AssistChip(
                onClick = { onChoose(place) },
                label = { Text(place.name, maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_history), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors(labelColor = Garage.MutedForeground),
            )
        }
    }
}

@Composable
private fun PlaceList(places: List<PlaceResult>, onChoose: (PlaceResult) -> Unit) {
    if (places.isEmpty()) {
        Text(
            "Tidak ditemukan. Coba nama lain, atau geser peta ke lokasinya.",
            style = MaterialTheme.typography.bodyMedium,
            color = Garage.MutedForeground,
        )
        return
    }

    val shape = MaterialTheme.shapes.medium
    Column(Modifier.fillMaxWidth().border(1.dp, Garage.Border, shape).clip(shape)) {
        places.forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider(color = Garage.Border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(place) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val history = place.source == PlaceSource.History
                Icon(
                    painterResource(if (history) R.drawable.ic_history else R.drawable.ic_place),
                    contentDescription = null,
                    tint = if (history) Garage.Brand else Garage.MutedForeground,
                    modifier = Modifier.padding(top = 2.dp).size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (history) "Pernah kamu kunjungi" else place.address.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MapWithPin(state: PickerState, onLocate: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    val lift by animateDpAsState(if (state.moving) (-8).dp else 0.dp, label = "pin")

    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(shape)
            .border(1.dp, Garage.Border, shape),
    ) {
        GarageMap(
            Modifier.fillMaxSize(),
            start = state.center,
            startZoom = if (state.placed) CLOSE_ZOOM else 11.0,
            onReady = state::attach,
        )

        // The pin stays centred; its tip marks the spot.
        Box(Modifier.size(6.dp).align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), CircleShape))
        Icon(
            painterResource(R.drawable.ic_place),
            contentDescription = null,
            tint = Garage.Brand,
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(0, ((-18).dp + lift).roundToPx()) }
                .size(40.dp),
        )

        FilledTonalButton(
            onClick = onLocate,
            enabled = !state.locating,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 28.dp),
        ) {
            if (state.locating) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp))
            }
            Text("Lokasi saya", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Where the name came from, or the name at the pin when the collector chose another one. */
@Composable
private fun NameNote(lookup: Lookup, name: String, onUse: (String) -> Unit) {
    val style = MaterialTheme.typography.bodySmall
    when (lookup) {
        Lookup.Idle -> Unit
        Lookup.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text("Mencari nama tempat di titik ini…", style = style, color = Garage.MutedForeground)
        }
        is Lookup.Done -> {
            val result = lookup.result
            when {
                result.name == null -> Text("Nama tempat di titik ini tidak ditemukan — ketik sendiri.", style = style, color = Garage.MutedForeground)
                result.name != name.trim() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Di titik ini: ", style = style, color = Garage.MutedForeground)
                    Text(result.name, style = style, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { onUse(result.name) }) { Text("pakai nama ini", style = style, color = Garage.Brand) }
                }
                result.source == PlaceSource.History -> Text(
                    "Dari riwayatmu" + (result.distance?.let { " ($it m dari pin)" } ?: ""),
                    style = style,
                    color = Garage.MutedForeground,
                )
                else -> Text("Nama dari OpenStreetMap — ubah kalau kurang tepat", style = style, color = Garage.MutedForeground)
            }
        }
    }
}

/** "📍 -6.19510, 106.82190 · GPS ±12 m" */
fun coordinates(lat: Double, lng: Double, accuracy: Int?): String =
    String.format(Locale.ROOT, "📍 %.5f, %.5f", lat, lng) + (accuracy?.let { " · GPS ±$it m" } ?: "")

/** The picker's logic, ported from the web component's state and handlers. */
private class PickerState(
    private val initial: PlaceValue,
    private val places: PlaceLookup,
    private val location: DeviceLocation,
    private val scope: CoroutineScope,
) {
    private var map: MapLibreMap? = null
    private var gpsSource: GeoJsonSource? = null
    private var accuracySource: GeoJsonSource? = null

    var center by mutableStateOf(if (initial.hasPoint) LatLng(initial.lat!!, initial.lng!!) else JAKARTA)
        private set

    /** False until something real put the pin somewhere (not just the default view). */
    var placed by mutableStateOf(initial.hasPoint)
        private set
    var moving by mutableStateOf(false)
        private set

    var name by mutableStateOf(initial.name)
    var lookup by mutableStateOf<Lookup>(Lookup.Idle)
        private set

    /** A name the collector typed or picked stays put; a looked-up one follows the pin. */
    var nameLocked = initial.name.isNotBlank()

    var gps by mutableStateOf<GpsFix?>(null)
        private set
    var atGps by mutableStateOf(false)
        private set
    var locating by mutableStateOf(false)
        private set
    var gpsError by mutableStateOf<String?>(null)

    var query by mutableStateOf(if (initial.hasPoint) "" else initial.name)
    var results by mutableStateOf<List<PlaceResult>?>(null)
    var recent by mutableStateOf(emptyList<PlaceResult>())
        private set
    var searching by mutableStateOf(false)
        private set

    private var userMoved = false
    private var skipLookup = false

    /** The pin position (≈1 m) the current name belongs to. */
    private var lookedUp: String? = null
    private var lookupJob: Job? = null
    private var pendingFix: GpsFix? = null

    val waitingForName get() = lookup == Lookup.Loading && !nameLocked

    fun attach(map: MapLibreMap, style: Style) {
        this.map = map
        addGpsLayers(style)

        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                userTouchedMap()
                moving = true
            }
        }
        map.addOnCameraIdleListener { handleIdle() }
        map.addOnMapClickListener { point ->
            userTouchedMap()
            map.animateCamera(CameraUpdateFactory.newLatLng(point), 300)
            true
        }

        gps?.let(::showGps)
        pendingFix?.let { fix ->
            pendingFix = null
            atGps = true
            goTo(LatLng(fix.lat, fix.lng))
        }

        // Recent places as quick picks; with nothing else to go on, start the map near the latest one.
        scope.launch {
            recent = runCatching { places.search("") }.getOrDefault(emptyList())
            recent.firstOrNull()?.let { latest ->
                if (!initial.hasPoint && !userMoved && !atGps) {
                    skipLookup = true
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latest.lat, latest.lng), 14.0))
                }
            }
        }

        // A name but no point yet: a single match from the collector's own history is surely it.
        if (!initial.hasPoint && initial.name.isNotBlank()) {
            val typed = initial.name.trim().lowercase()
            scope.launch {
                val same = search(initial.name).filter { it.source == PlaceSource.History && it.name.lowercase() == typed }
                if (same.size == 1 && !userMoved) choose(same.single())
            }
        }
    }

    fun locate(auto: Boolean) {
        if (locating) return
        locating = true
        gpsError = null
        scope.launch {
            try {
                val fix = location.current()
                gps = fix
                showGps(fix)
                // On open, don't yank the map away if the collector already moved it.
                if (!auto || !userMoved) {
                    if (map == null) {
                        pendingFix = fix
                    } else {
                        atGps = true
                        goTo(LatLng(fix.lat, fix.lng))
                    }
                }
            } catch (e: LocationException) {
                if (!auto) gpsError = e.message
            } finally {
                locating = false
            }
        }
    }

    fun search() {
        scope.launch { search(query) }
    }

    private suspend fun search(text: String): List<PlaceResult> {
        val q = text.trim()
        if (q.isEmpty()) {
            results = null
            return emptyList()
        }

        searching = true
        return try {
            val near = map?.cameraPosition?.target ?: center
            places.search(q, round6(near.latitude), round6(near.longitude)).also { results = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList<PlaceResult>().also { results = it }
        } finally {
            searching = false
        }
    }

    fun choose(place: PlaceResult) {
        userMoved = true
        nameLocked = true
        name = place.name
        lookup = Lookup.Done(PlaceName(place.name, place.address, place.source))
        results = null
        atGps = false
        goTo(LatLng(place.lat, place.lng), lookup = false)
    }

    fun confirm(): PlaceValue {
        val point = map?.cameraPosition?.target ?: center
        return PlaceValue(
            name = name.trim(),
            lat = round6(point.latitude),
            lng = round6(point.longitude),
            accuracy = gps?.accuracy?.takeIf { atGps },
            at = System.currentTimeMillis(),
        )
    }

    private fun goTo(point: LatLng, lookup: Boolean = true) {
        val map = map ?: return
        skipLookup = !lookup
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, max(map.cameraPosition.zoom, CLOSE_ZOOM)), 600)
        placed = true
    }

    private fun userTouchedMap() {
        userMoved = true
        skipLookup = false
        placed = true
        atGps = false
    }

    private fun handleIdle() {
        val target = map?.cameraPosition?.target ?: return
        val spot = String.format(Locale.ROOT, "%.5f,%.5f", target.latitude, target.longitude)
        center = target
        moving = false

        // Same spot (e.g. the map resized when the keyboard opened): keep the name we have or are fetching.
        if (spot == lookedUp) return
        lookupJob?.cancel()

        if (skipLookup) {
            skipLookup = false
            lookedUp = spot
            // A lookup cancelled by this move must not leave "Mencari…" behind.
            if (lookup == Lookup.Loading) lookup = Lookup.Idle
            return
        }
        if (!placed) return

        lookedUp = spot
        lookup = Lookup.Loading
        lookupJob = scope.launch {
            // Wait until the map settles: OpenStreetMap allows about one request a second.
            delay(700)
            val result = places.lookup(round6(target.latitude), round6(target.longitude))
            lookup = Lookup.Done(result)
            if (!nameLocked && result.name != null) name = result.name
        }
    }

    private fun addGpsLayers(style: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        accuracySource = GeoJsonSource("gps-accuracy", empty).also(style::addSource)
        gpsSource = GeoJsonSource("gps", empty).also(style::addSource)
        style.addLayer(
            FillLayer("gps-accuracy", "gps-accuracy").withProperties(
                PropertyFactory.fillColor(GPS_BLUE.toHex()),
                PropertyFactory.fillOpacity(0.12f),
                PropertyFactory.fillOutlineColor(GPS_BLUE.toHex()),
            ),
        )
        style.addLayer(
            CircleLayer("gps", "gps").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(GPS_BLUE.toHex()),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }

    private fun showGps(fix: GpsFix) {
        gpsSource?.setGeoJson(Point.fromLngLat(fix.lng, fix.lat))
        accuracySource?.setGeoJson(circle(fix.lat, fix.lng, fix.accuracy.toDouble()))
    }

    /** The accuracy disc as a polygon, so it keeps its real size at every zoom. */
    private fun circle(lat: Double, lng: Double, meters: Double, steps: Int = 48): Polygon {
        val points = (0..steps).map { i ->
            val angle = 2 * Math.PI * i / steps
            Point.fromLngLat(
                lng + meters / (111_320.0 * cos(Math.toRadians(lat))) * sin(angle),
                lat + meters / 111_320.0 * cos(angle),
            )
        }
        return Polygon.fromLngLats(listOf(points))
    }
}

