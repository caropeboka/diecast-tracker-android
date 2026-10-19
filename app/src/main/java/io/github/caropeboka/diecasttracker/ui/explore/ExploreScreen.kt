package io.github.caropeboka.diecasttracker.ui.explore

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.explore.EventKind
import io.github.caropeboka.diecasttracker.data.explore.ExplorePlace
import io.github.caropeboka.diecasttracker.data.explore.ExploreRange
import io.github.caropeboka.diecasttracker.data.explore.ExploreStats
import io.github.caropeboka.diecasttracker.data.explore.ExploreType
import io.github.caropeboka.diecasttracker.data.explore.TimelineDay
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.map.GarageMap
import io.github.caropeboka.diecasttracker.ui.map.toHex
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.formatDayLong
import io.github.caropeboka.diecasttracker.util.formatDayMonth
import io.github.caropeboka.diecasttracker.util.formatRupiah
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.sqrt

/** "2 dibeli · 3 ditemui" */
private fun describeCounts(sightings: Int, purchases: Int) =
    listOfNotNull(purchases.takeIf { it > 0 }?.let { "$it dibeli" }, sightings.takeIf { it > 0 }?.let { "$it ditemui" })
        .joinToString(" · ")

/** "Peta jelajah" (web: `explore/index.tsx`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onTab: (MainTab) -> Unit,
    onOpen: (Long) -> Unit,
    viewModel: ExploreViewModel = viewModel(factory = ExploreViewModel.Factory),
) {
    val exploration by viewModel.exploration.collectAsStateWithLifecycle()
    val type by viewModel.type.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selected.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val map = remember { ExploreMap() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Peta jelajah") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { MainNavigationBar(MainTab.Explore, onTab) },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val data = exploration
        if (data == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val mapped = data.places.filter { it.hasPoint }
        val selected = data.places.find { it.key == selectedKey }

        fun focus(place: ExplorePlace) {
            viewModel.select(place.key)
            map.focus(place)
            scope.launch { list.animateScrollToItem(MAP_ITEM) }
        }

        LazyColumn(
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    "Tempat-tempat kamu membeli dan menemukan diecast, kapan, dan apa saja yang ada di sana.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(
                        ExploreType.entries, type, viewModel::setType, label = { it.label },
                        icon = {
                            when (it) {
                                ExploreType.All -> R.drawable.ic_place
                                ExploreType.Sighting -> R.drawable.ic_visibility
                                ExploreType.Purchase -> R.drawable.ic_shopping_bag
                            }
                        },
                    )
                    Segmented(ExploreRange.entries, range, viewModel::setRange, label = { it.label })
                }
            }
            item { StatTiles(data.stats) }
            item {
                MapCard(map, mapped, selectedKey, onTap = { key -> viewModel.select(key) })
            }
            if (selected != null) {
                item(key = selected.key) { PlaceDetails(selected, onOpen, onClose = { viewModel.select(null) }) }
            }
            item { PlacesPanel(data.places, ::focus) }
            item { TimelinePanel(data.timeline) }
        }
    }
}

private const val MAP_ITEM = 3

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    icon: ((T) -> Int)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Garage.Muted, RoundedCornerShape(10.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (active) {
                            Modifier.background(Garage.Card).border(1.dp, Garage.Brand.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        } else {
                            Modifier
                        },
                    )
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Icon(
                        painterResource(it(option)),
                        contentDescription = null,
                        tint = if (active) Garage.Brand else Garage.MutedForeground,
                        modifier = Modifier.padding(end = 4.dp).size(14.dp),
                    )
                }
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatTiles(stats: ExploreStats) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(R.drawable.ic_place, "Tempat dijelajahi", stats.places.toString(), "${stats.mapped} punya titik di peta", Modifier.weight(1f))
            StatTile(
                R.drawable.ic_car, "Diecast", stats.diecasts.toString(),
                describeCounts(stats.sightings, stats.purchases).ifEmpty { "—" }, Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(R.drawable.ic_calendar, "Hari berburu", stats.days.toString(), stats.top?.let { "Paling sering: $it" }, Modifier.weight(1f))
            StatTile(
                R.drawable.ic_route, "Berburu sejak", stats.first?.let(::formatDate) ?: "—",
                stats.last?.let { "Terakhir ${formatDate(it)}" }, Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(@DrawableRes icon: Int, label: String, value: String, hint: String?, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun MapCard(map: ExploreMap, mapped: List<ExplorePlace>, selected: String?, onTap: (String?) -> Unit) {
    val shape = MaterialTheme.shapes.large
    val tap by rememberUpdatedState(onTap)
    val padding = with(LocalDensity.current) { 40.dp.roundToPx() }

    LaunchedEffect(map.ready, mapped, selected) { map.show(mapped, selected, padding) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(shape)
            .border(1.dp, Garage.Border, shape),
    ) {
        GarageMap(Modifier.fillMaxSize(), onReady = { libreMap, style -> map.attach(libreMap, style) { tap(it) } })

        if (mapped.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Garage.Background.copy(alpha = 0.82f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(40.dp))
                SubHeading("Belum ada titik di peta")
                Text(
                    "Tekan tombol lokasi di Beli di atau Ditemui di saat menambah diecast atau mencatat temuan. Tempatnya akan muncul di sini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Legend(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
    }
}

/** Status is never colour-only: the legend names both marker kinds. */
@Composable
private fun Legend(modifier: Modifier) {
    Row(
        modifier
            .background(Garage.Background.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val small = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(9.dp).background(Garage.Brand, CircleShape))
            Text("Ada pembelian", style = small, color = Garage.MutedForeground)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(9.dp).border(2.dp, Garage.Brand, CircleShape))
            Text("Hanya ditemui", style = small, color = Garage.MutedForeground)
        }
        Text("Ukuran = jumlah", style = small, color = Garage.MutedForeground)
    }
}

/** The tapped marker: what was seen or bought there (web: `PlacePopup`). */
@Composable
private fun PlaceDetails(place: ExplorePlace, onOpen: (Long) -> Unit, onClose: () -> Unit) {
    SectionCard(null) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                SubHeading(place.name, maxLines = 2)
                Text(
                    "${place.visits}× kunjungan · terakhir ${formatDate(place.last)} · ${describeCounts(place.sightings, place.purchases)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Tutup", tint = Garage.MutedForeground)
            }
        }
        place.items.forEach { item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "Buka ${item.name}") { onOpen(item.diecastId) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DiecastPhoto(item.photoFile, null, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (item.kind == EventKind.Purchase) "Dibeli " else "Ditemui ") + formatDayMonth(item.date) +
                            (item.price?.let { " · ${formatRupiah(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
                Icon(
                    painterResource(if (item.kind == EventKind.Purchase) R.drawable.ic_shopping_bag else R.drawable.ic_visibility),
                    contentDescription = if (item.kind == EventKind.Purchase) "Dibeli" else "Ditemui",
                    tint = if (item.kind == EventKind.Purchase) Garage.Brand else Garage.MutedForeground,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (place.count > place.items.size) {
            Text("+${place.count - place.items.size} lainnya", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
        }
    }
}

@Composable
private fun PlacesPanel(places: List<ExplorePlace>, onFocus: (ExplorePlace) -> Unit) {
    SectionCard("Tempat") {
        if (places.isEmpty()) {
            Text("Belum ada data di rentang waktu ini.", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
        }
        places.forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider(color = Garage.Border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = place.hasPoint, onClickLabel = "Lihat di peta") { onFocus(place) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(if (place.hasPoint) Garage.Brand.copy(alpha = 0.12f) else Garage.Muted, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(if (place.purchases > 0) R.drawable.ic_shopping_bag else R.drawable.ic_visibility),
                        contentDescription = null,
                        tint = if (place.hasPoint) Garage.Brand else Garage.MutedForeground,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${place.visits}× kunjungan · " +
                            (if (place.first == place.last) formatDate(place.last) else "${formatDayMonth(place.first)} – ${formatDate(place.last)}") +
                            (if (!place.hasPoint) " · belum ada titik" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    describeCounts(place.sightings, place.purchases),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun TimelinePanel(timeline: List<TimelineDay>) {
    SectionCard("Riwayat jelajah") {
        if (timeline.isEmpty()) {
            Text("Belum ada riwayat.", style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
        }
        Column {
            timeline.forEachIndexed { index, day ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    // The rail: a dot per day, joined by a line.
                    Box(Modifier.width(20.dp).fillMaxHeight()) {
                        if (index < timeline.lastIndex) {
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(top = 10.dp).background(Garage.Border).align(Alignment.TopCenter))
                        }
                        Box(Modifier.padding(top = 5.dp).size(10.dp).background(Garage.Brand, CircleShape).align(Alignment.TopCenter))
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 14.dp)) {
                        Text(formatDayLong(day.date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            day.places.joinToString(" · ") { "${it.name} (${describeCounts(it.sightings, it.purchases)})" },
                            style = MaterialTheme.typography.bodySmall,
                            color = Garage.MutedForeground,
                        )
                    }
                }
            }
        }
    }
}

/** The explore map's markers: one circle per place, filled when something was bought there, a ring when only seen. */
private class ExploreMap {
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var framed: Set<String>? = null

    var ready by mutableStateOf(false)
        private set

    fun attach(map: MapLibreMap, style: Style, onTap: (String?) -> Unit) {
        this.map = map
        val brand = Garage.Brand.toHex()
        source = GeoJsonSource("places", FeatureCollection.fromFeatures(emptyList())).also(style::addSource)
        style.addLayer(
            CircleLayer("places", "places").withProperties(
                PropertyFactory.circleRadius(get("radius")),
                PropertyFactory.circleColor(brand),
                PropertyFactory.circleOpacity(switchCase(eq(get("bought"), true), literal(0.85f), literal(0.15f))),
                PropertyFactory.circleStrokeColor(
                    switchCase(eq(get("selected"), true), literal("#ffffff"), eq(get("bought"), true), literal(Garage.Background.toHex()), literal(brand)),
                ),
                PropertyFactory.circleStrokeWidth(switchCase(eq(get("bought"), true), literal(2f), literal(3f))),
            ),
        )
        map.addOnMapClickListener { point ->
            val hit = map.queryRenderedFeatures(map.projection.toScreenLocation(point), "places").firstOrNull()
            onTap(hit?.getStringProperty("key"))
            true
        }
        ready = true
    }

    fun show(places: List<ExplorePlace>, selected: String?, padding: Int) {
        val source = source ?: return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                places.map { place ->
                    Feature.fromGeometry(Point.fromLngLat(place.lng!!, place.lat!!)).apply {
                        addStringProperty("key", place.key)
                        // Area grows with the number of events (web: 6 + √count × 4).
                        addNumberProperty("radius", 6 + sqrt(place.count.toDouble()) * 4)
                        addBooleanProperty("bought", place.purchases > 0)
                        addBooleanProperty("selected", place.key == selected)
                    }
                },
            ),
        )

        // Frame all markers whenever the set changes (not when one is just selected).
        val keys = places.map { it.key }.toSet()
        if (keys != framed) {
            framed = keys
            frame(places, padding)
        }
    }

    fun focus(place: ExplorePlace) {
        val lat = place.lat ?: return
        val lng = place.lng ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 16.0), 800)
    }

    private fun frame(places: List<ExplorePlace>, padding: Int) {
        val map = map ?: return
        val points = places.map { LatLng(it.lat!!, it.lng!!) }
        when {
            points.isEmpty() -> Unit
            points.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(points.single(), 15.0))
            else -> {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding))
                if (map.cameraPosition.zoom > 15) map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
            }
        }
    }
}
