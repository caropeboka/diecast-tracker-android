package io.github.caropeboka.diecasttracker.ui.explore

import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.data.explore.EventKind
import io.github.caropeboka.diecasttracker.data.explore.ExploreEvent
import io.github.caropeboka.diecasttracker.data.explore.ExplorePlace
import io.github.caropeboka.diecasttracker.data.explore.ExploreRange
import io.github.caropeboka.diecasttracker.data.explore.ExploreStats
import io.github.caropeboka.diecasttracker.data.explore.ExploreType
import io.github.caropeboka.diecasttracker.data.explore.RegionChart
import io.github.caropeboka.diecasttracker.data.explore.RegionRow
import io.github.caropeboka.diecasttracker.data.explore.TimelineDay
import io.github.caropeboka.diecasttracker.data.places.DeviceLocation
import io.github.caropeboka.diecasttracker.data.places.GpsFix
import io.github.caropeboka.diecasttracker.data.places.LocationException
import io.github.caropeboka.diecasttracker.data.places.RegionLevel
import io.github.caropeboka.diecasttracker.ui.MainNavigationBar
import io.github.caropeboka.diecasttracker.ui.MainTab
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.rememberTexts
import io.github.caropeboka.diecasttracker.ui.map.GarageMap
import io.github.caropeboka.diecasttracker.ui.map.toHex
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.formatDayLong
import io.github.caropeboka.diecasttracker.util.formatDayMonth
import io.github.caropeboka.diecasttracker.util.formatMoney
import kotlin.math.hypot
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

/** "2 bought · 3 spotted" */
@Composable
private fun describeCounts(sightings: Int, purchases: Int) = listOfNotNull(
    if (purchases > 0) stringResource(R.string.explore_bought_count, purchases) else null,
    if (sightings > 0) stringResource(R.string.explore_spotted_count, sightings) else null,
).joinToString(" · ")

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
    val area by viewModel.area.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val map = remember { ExploreMap() }
    // The history's days opened to their diecasts (ISO dates), and whether every day shows.
    var openDays by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var allDays by rememberSaveable { mutableStateOf(false) }
    val oldestDay = remember { BringIntoViewRequester() }

    LaunchedEffect(viewModel) { viewModel.lookUpRegions() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.explore_title)) },
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
            if (place.hasPoint) {
                map.focus(place)
                scope.launch { list.animateScrollToItem(MAP_ITEM) }
            }
        }

        val taps = StatTaps(
            places = { scope.launch { list.animateScrollToItem(PLACES_ITEM) } },
            history = { scope.launch { list.animateScrollToItem(HISTORY_ITEM) } },
            since = {
                // The first hunting day, at the end of the whole history, opened.
                data.timeline.lastOrNull()?.let { first ->
                    allDays = true
                    openDays = openDays + first.date.toString()
                    scope.launch {
                        list.animateScrollToItem(HISTORY_ITEM)
                        withFrameNanos { }
                        oldestDay.bringIntoView()
                    }
                }
            },
        )

        LazyColumn(
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Text(
                    stringResource(R.string.explore_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(
                        ExploreType.entries, type, viewModel::setType, label = { stringResource(it.label) },
                        icon = {
                            when (it) {
                                ExploreType.All -> R.drawable.ic_place
                                ExploreType.Sighting -> R.drawable.ic_visibility
                                ExploreType.Purchase -> R.drawable.ic_shopping_bag
                            }
                        },
                    )
                    Segmented(ExploreRange.entries, range, viewModel::setRange, label = { stringResource(it.label) })
                    if (area.isNotEmpty()) AreaFilter(area, onClear = { viewModel.setArea(emptyList()) })
                }
            }
            item { StatTiles(data.stats, taps) }
            item {
                MapCard(map, mapped, selectedKey, onTap = { key -> viewModel.select(key) })
            }
            item { RegionPanel(data.regions, lookup, viewModel::setArea, viewModel::retryRegions) }
            item { PlacesPanel(data.places, ::focus) }
            item {
                TimelinePanel(
                    data.timeline,
                    open = openDays,
                    all = allDays,
                    oldest = oldestDay,
                    onToggle = { day -> openDays = if (day in openDays) openDays - day else openDays + day },
                    onAll = { allDays = !allDays },
                    onOpen = onOpen,
                )
            }
        }

        if (selected != null) {
            ModalBottomSheet(onDismissRequest = { viewModel.select(null) }, containerColor = Garage.Card) {
                PlaceDetails(selected, onOpen)
            }
        }
    }
}

// The list's items: intro, filters, stats, the map, regions, places, history.
private const val MAP_ITEM = 3
private const val PLACES_ITEM = 5
private const val HISTORY_ITEM = 6

/** Days shown in the history before "Show all". */
private const val HISTORY_DAYS = 30

/** Where the stat tiles lead: the places list, the history, the first hunting day. */
private class StatTaps(val places: () -> Unit, val history: () -> Unit, val since: () -> Unit)

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
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
private fun StatTiles(stats: ExploreStats, taps: StatTaps) {
    val any = stats.days > 0
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(
                R.drawable.ic_place, stringResource(R.string.explore_places_explored), stats.places.toString(),
                stringResource(R.string.explore_mapped, stats.mapped), Modifier.weight(1f), taps.places.takeIf { any },
            )
            StatTile(
                R.drawable.ic_car, stringResource(R.string.explore_diecasts), stats.diecasts.toString(),
                describeCounts(stats.sightings, stats.purchases).ifEmpty { "—" }, Modifier.weight(1f), taps.history.takeIf { any },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            StatTile(
                R.drawable.ic_calendar, stringResource(R.string.explore_days), stats.days.toString(),
                stats.top?.let { stringResource(R.string.explore_most_often, it) }, Modifier.weight(1f), taps.history.takeIf { any },
            )
            StatTile(
                R.drawable.ic_route, stringResource(R.string.explore_since), stats.first?.let(::formatDate) ?: "—",
                stats.last?.let { stringResource(R.string.explore_last, formatDate(it)) }, Modifier.weight(1f), taps.since.takeIf { any },
            )
        }
    }
}

@Composable
private fun StatTile(@DrawableRes icon: Int, label: String, value: String, hint: String?, modifier: Modifier, onClick: (() -> Unit)?) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column(
            Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
    val density = LocalDensity.current
    val padding = with(density) { 40.dp.roundToPx() }
    val slop = with(density) { 18.dp.toPx() }
    val container = LocalContext.current.container
    val texts = rememberTexts()
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }

    fun locate() {
        if (locating) return
        locating = true
        scope.launch {
            try {
                map.showMe(container.location.current())
            } catch (e: LocationException) {
                container.messages.show(e.message.orEmpty())
            } finally {
                locating = false
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else container.messages.show(texts(R.string.location_denied))
    }

    LaunchedEffect(map.ready, mapped, selected) { map.show(mapped, selected, padding) }

    Box(
        Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(shape)
            .border(1.dp, Garage.Border, shape),
    ) {
        GarageMap(Modifier.fillMaxSize(), onReady = { libreMap, style -> map.attach(libreMap, style, slop) { tap(it) } })

        if (mapped.isEmpty() && map.me == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Garage.Background.copy(alpha = 0.82f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                Icon(painterResource(R.drawable.ic_map), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(40.dp))
                SubHeading(stringResource(R.string.explore_no_points))
                Text(
                    stringResource(R.string.explore_no_points_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Legend(Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        SmallFloatingActionButton(
            onClick = { if (container.location.hasPermission()) locate() else permission.launch(DeviceLocation.PERMISSIONS) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            containerColor = Garage.Card,
            contentColor = Garage.Brand,
        ) {
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = stringResource(R.string.place_my_location), Modifier.size(20.dp))
            }
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
            Text(stringResource(R.string.explore_legend_bought), style = small, color = Garage.MutedForeground)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(9.dp).border(2.dp, Garage.Brand, CircleShape))
            Text(stringResource(R.string.explore_legend_spotted), style = small, color = Garage.MutedForeground)
        }
        Text(stringResource(R.string.explore_legend_size), style = small, color = Garage.MutedForeground)
    }
}

/** A tapped marker or place: what was seen or bought there (web: `PlacePopup`). */
@Composable
private fun PlaceDetails(place: ExplorePlace, onOpen: (Long) -> Unit) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            SubHeading(place.name, maxLines = 2)
            Text(
                pluralStringResource(R.plurals.explore_visits_last, place.visits, place.visits, formatDate(place.last), describeCounts(place.sightings, place.purchases)),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
            if (!place.hasPoint) {
                Text(stringResource(R.string.explore_no_point), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            }
        }
        place.items.forEach { item ->
            EventRow(
                item,
                stringResource(if (item.kind == EventKind.Purchase) R.string.explore_bought_on else R.string.explore_spotted_on, formatDayMonth(item.date)),
                onOpen,
            )
        }
        if (place.count > place.items.size) {
            Text(stringResource(R.string.explore_more, place.count - place.items.size), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
        }
    }
}

/** A diecast seen or bought: its photo, name, [what] (when or where) and the price; a tap opens it. */
@Composable
private fun EventRow(item: ExploreEvent, what: String, onOpen: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClickLabel = stringResource(R.string.open_named, item.name)) { onOpen(item.diecastId) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiecastPhoto(item.photoFile, null, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 20.dp)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                what + (item.price?.let { " · " + formatMoney(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painterResource(if (item.kind == EventKind.Purchase) R.drawable.ic_shopping_bag else R.drawable.ic_visibility),
            contentDescription = stringResource(if (item.kind == EventKind.Purchase) R.string.explore_bought else R.string.status_spotted),
            tint = if (item.kind == EventKind.Purchase) Garage.Brand else Garage.MutedForeground,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** The region everything on the page is narrowed to, with a way back to all. */
@Composable
private fun AreaFilter(area: List<String>, onClear: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Garage.Brand.copy(alpha = 0.12f), shape)
            .border(1.dp, Garage.Brand.copy(alpha = 0.5f), shape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_place), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.explore_region_filter, area.joinToString(" › ")),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onClear) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.explore_region_clear), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Hunting per region, one level at a time (provinsi → kabupaten/kota → kecamatan → desa): a bar per area, bought and
 * spotted stacked on one ramp like the Dex meters. Tapping one narrows the whole page to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RegionPanel(chart: RegionChart, lookup: RegionLookup, onArea: (List<String>) -> Unit, onRetry: () -> Unit) {
    var all by rememberSaveable(chart.area) { mutableStateOf(false) }
    val muted = MaterialTheme.typography.bodySmall

    SectionCard(stringResource((chart.level ?: RegionLevel.Village).by)) {
        if (chart.area.isNotEmpty()) {
            FlowRow(verticalArrangement = Arrangement.Center) {
                val crumbs = listOf(stringResource(R.string.explore_regions_all)) + chart.area
                crumbs.forEachIndexed { i, crumb ->
                    if (i > 0) Text(" › ", style = MaterialTheme.typography.labelLarge, color = Garage.MutedForeground, modifier = Modifier.padding(vertical = 6.dp))
                    val current = i == crumbs.lastIndex
                    Text(
                        crumb,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.onSurface else Garage.Brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .then(if (current) Modifier else Modifier.clickable { onArea(chart.area.take(i)) })
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                    )
                }
            }
        }

        when {
            chart.level == null -> Text(stringResource(R.string.explore_region_only, chart.area.last()), style = muted, color = Garage.MutedForeground)
            chart.rows.isEmpty() && !lookup.running -> Text(
                stringResource(if (chart.area.isEmpty()) R.string.explore_region_none else R.string.explore_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )
        }

        if (chart.level != null && chart.rows.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(Garage.MeterBar, stringResource(R.string.explore_bought))
                LegendSwatch(Garage.MeterSoft, stringResource(R.string.status_spotted))
            }
            val max = chart.rows.maxOf { it.count }
            val shown = if (all) chart.rows else chart.rows.take(REGION_ROWS)
            shown.forEach { row ->
                RegionBar(
                    row,
                    max,
                    clickLabel = stringResource(if (chart.level == RegionLevel.Village) R.string.explore_region_pick else R.string.explore_region_open),
                    onClick = row.name?.let { name -> { onArea(chart.area + name) } },
                )
            }
            if (chart.rows.size > REGION_ROWS) {
                TextButton(onClick = { all = !all }) {
                    Text(if (all) stringResource(R.string.explore_region_fewer) else pluralStringResource(R.plurals.explore_region_all, chart.rows.size, chart.rows.size))
                }
            }
        }

        if (chart.area.isEmpty() && chart.unmapped > 0) {
            Text(pluralStringResource(R.plurals.explore_region_unmapped, chart.unmapped, chart.unmapped), style = muted, color = Garage.MutedForeground)
        }
        when {
            lookup.running -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.explore_region_looking, lookup.done, lookup.total), style = muted, color = Garage.MutedForeground)
                LinearProgressIndicator(
                    progress = { lookup.done.toFloat() / lookup.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = Garage.MeterBar,
                    trackColor = Garage.MeterTrack,
                )
            }
            lookup.offline && chart.pending > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.explore_region_offline), style = muted, color = Garage.MutedForeground, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

private const val REGION_ROWS = 6

@Composable
private fun RegionBar(row: RegionRow, max: Int, clickLabel: String, onClick: (() -> Unit)?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.name ?: stringResource(R.string.explore_region_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.name == null) Garage.MutedForeground else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Text(row.count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (onClick != null) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = Garage.MutedForeground,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(-90f),
                )
            }
        }
        // Bought, then spotted, on the scale of the busiest area; small gaps between the pieces.
        Row(Modifier.fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (row.purchases > 0) Box(Modifier.weight(row.purchases.toFloat()).fillMaxHeight().background(Garage.MeterBar, RoundedCornerShape(4.dp)))
            if (row.sightings > 0) Box(Modifier.weight(row.sightings.toFloat()).fillMaxHeight().background(Garage.MeterSoft, RoundedCornerShape(4.dp)))
            if (max > row.count) Spacer(Modifier.weight((max - row.count).toFloat()))
        }
        Text(
            pluralStringResource(R.plurals.explore_region_places, row.places, row.places) + " · " + describeCounts(row.sightings, row.purchases),
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(2.dp)))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlacesPanel(places: List<ExplorePlace>, onFocus: (ExplorePlace) -> Unit) {
    SectionCard(stringResource(R.string.explore_places)) {
        if (places.isEmpty()) {
            Text(stringResource(R.string.explore_no_data), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
        }
        places.forEachIndexed { index, place ->
            if (index > 0) HorizontalDivider(color = Garage.Border)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.explore_details)) { onFocus(place) }
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
                        pluralStringResource(R.plurals.explore_visits, place.visits, place.visits) + " · " +
                            (if (place.first == place.last) formatDate(place.last) else "${formatDayMonth(place.first)} – ${formatDate(place.last)}") +
                            (if (!place.hasPoint) " · " + stringResource(R.string.explore_no_point) else ""),
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

/**
 * The hunting days, newest first, the latest [HISTORY_DAYS] until [all]; a tapped day opens to what was seen and bought
 * where. [oldest] is on the first hunting day, to scroll to it.
 */
@Composable
private fun TimelinePanel(
    timeline: List<TimelineDay>,
    open: Set<String>,
    all: Boolean,
    oldest: BringIntoViewRequester,
    onToggle: (String) -> Unit,
    onAll: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val shown = if (all) timeline else timeline.take(HISTORY_DAYS)
    SectionCard(stringResource(R.string.explore_timeline)) {
        if (timeline.isEmpty()) {
            Text(stringResource(R.string.explore_no_history), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
        }
        Column {
            shown.forEachIndexed { index, day ->
                val key = day.date.toString()
                val opened = key in open
                Row(Modifier.height(IntrinsicSize.Min).then(if (day === timeline.last()) Modifier.bringIntoViewRequester(oldest) else Modifier)) {
                    // The rail: a dot per day, joined by a line.
                    Box(Modifier.width(20.dp).fillMaxHeight()) {
                        if (index < shown.lastIndex) {
                            Box(Modifier.width(1.dp).fillMaxHeight().padding(top = 10.dp).background(Garage.Border).align(Alignment.TopCenter))
                        }
                        Box(Modifier.padding(top = 5.dp).size(10.dp).background(Garage.Brand, CircleShape).align(Alignment.TopCenter))
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 14.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClickLabel = stringResource(R.string.explore_day_open)) { onToggle(key) },
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(formatDayLong(day.date), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    day.places.map { it.name + " (" + describeCounts(it.sightings, it.purchases) + ")" }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Garage.MutedForeground,
                                )
                            }
                            Icon(
                                painterResource(R.drawable.ic_expand_more),
                                contentDescription = null,
                                tint = Garage.MutedForeground,
                                modifier = Modifier.size(20.dp).rotate(if (opened) 180f else 0f),
                            )
                        }
                        if (opened) {
                            Column(Modifier.padding(top = 4.dp)) {
                                day.items.forEach { item ->
                                    val place = item.place?.takeIf(String::isNotBlank) ?: stringResource(R.string.place_unnamed)
                                    EventRow(
                                        item,
                                        stringResource(if (item.kind == EventKind.Purchase) R.string.explore_bought_at else R.string.explore_spotted_at, place),
                                        onOpen,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (timeline.size > HISTORY_DAYS) {
            TextButton(onClick = onAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    if (all) stringResource(R.string.explore_history_fewer) else pluralStringResource(R.plurals.explore_history_all, timeline.size, timeline.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The explore map's markers: one circle per place, filled when something was bought there, a ring when only seen. */
private class ExploreMap {
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var meSource: GeoJsonSource? = null
    private var framed: Set<String>? = null

    var ready by mutableStateOf(false)
        private set

    /** Where the phone was when "My location" was last tapped. */
    var me by mutableStateOf<GpsFix?>(null)
        private set

    /** [slop]: how far from a marker (px) a tap still counts, as markers can be smaller than a finger. */
    fun attach(map: MapLibreMap, style: Style, slop: Float, onTap: (String?) -> Unit) {
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
        // The phone's position: a blue dot in a halo, above the markers.
        meSource = GeoJsonSource("me", FeatureCollection.fromFeatures(emptyList())).also(style::addSource)
        style.addLayer(
            CircleLayer("me-halo", "me").withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(ME),
                PropertyFactory.circleOpacity(0.18f),
            ),
        )
        style.addLayer(
            CircleLayer("me", "me").withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(ME),
                PropertyFactory.circleStrokeColor("#ffffff"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        me?.let(::placeMe)
        map.addOnMapClickListener { point ->
            val at = map.projection.toScreenLocation(point)
            val hit = map.queryRenderedFeatures(RectF(at.x - slop, at.y - slop, at.x + slop, at.y + slop), "places")
                .minByOrNull { feature ->
                    val spot = feature.geometry() as? Point ?: return@minByOrNull Float.MAX_VALUE
                    val screen = map.projection.toScreenLocation(LatLng(spot.latitude(), spot.longitude()))
                    hypot(screen.x - at.x, screen.y - at.y)
                }
            onTap(hit?.getStringProperty("key"))
            true
        }
        ready = true
    }

    fun showMe(fix: GpsFix) {
        me = fix
        placeMe(fix)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lng), 15.0), 800)
    }

    private fun placeMe(fix: GpsFix) {
        meSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(fix.lng, fix.lat)))
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

    private companion object {
        const val ME = "#4c8dff"
    }
}
