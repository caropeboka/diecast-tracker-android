package io.github.caropeboka.diecasttracker.ui.check

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.Rarity
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.scan.Verdict
import io.github.caropeboka.diecasttracker.ui.components.Badge
import io.github.caropeboka.diecasttracker.ui.components.DiecastPhoto
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.MoneyInput
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.components.icon
import io.github.caropeboka.diecasttracker.ui.places.LocationField
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatRupiah

/**
 * "Cek di toko" (web: `diecasts/check.tsx`): at the shelf, snap a diecast (or type its name) and see straight away
 * whether it is already in the collection; then +1, "Dapat", or log it as seen here with the shelf price.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    /** The form: from a photo check ([scanItem]) or a typed [name]; wishlist or the default status. */
    onAdd: (scanItem: Long, name: String?, status: DiecastStatus?) -> Unit,
    onAiSettings: () -> Unit,
    viewModel: CheckViewModel = viewModel(factory = CheckViewModel.Factory),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val diecasts by viewModel.diecasts.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), viewModel::onCameraResult)
    val openCamera = {
        try {
            camera.launch(viewModel.cameraTarget())
        } catch (_: ActivityNotFoundException) {
            viewModel.cameraUnavailable()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                CheckEvent.OpenCamera -> openCamera()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Cek di toko") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Lagi di toko? Foto diecast-nya, langsung ketahuan sudah punya atau belum.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )
            }
            item(key = "place") {
                LocationField(viewModel.place, viewModel::changePlace, places, "Toko / lokasi", placeholder = "Nama toko")
            }
            item(key = "camera") {
                if (enabled == false) {
                    AiOff(onAiSettings)
                } else {
                    CheckButton(checking = viewModel.checking, again = results.any { it.photo != null }, enabled = enabled == true, onClick = openCamera)
                }
            }
            item(key = "search") {
                SearchBox(viewModel.query, viewModel::changeQuery, viewModel::search)
            }
            itemsIndexed(results, key = { _, result -> result.key }) { index, result ->
                ResultCard(
                    result = result,
                    diecasts = diecasts,
                    busy = result.key in viewModel.busy,
                    older = index > 0,
                    viewModel = viewModel,
                    onOpen = onOpen,
                    onAdd = { status -> onAdd(result.checkId, result.query, status) },
                )
            }
        }
    }
}

@Composable
private fun AiOff(onAiSettings: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Brand2.copy(alpha = 0.08f), shape)
            .border(1.dp, Garage.Brand2.copy(alpha = 0.45f), shape)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            "Cek dengan foto butuh scan AI. Isi API key di Pengaturan AI — mencari dengan mengetik tetap bisa.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onAiSettings, contentPadding = PaddingValues(0.dp)) { Text("Buka Pengaturan AI →") }
    }
}

@Composable
private fun CheckButton(checking: Boolean, again: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !checking,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) {
        if (checking) {
            CircularProgressIndicator(Modifier.size(24.dp), color = LocalContentColor.current, strokeWidth = 3.dp)
        } else {
            Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null, Modifier.size(26.dp))
        }
        Spacer(Modifier.size(12.dp))
        Text(
            when {
                checking -> "Mengecek…"
                again -> "Cek diecast lain"
                else -> "Foto diecast"
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        keyboard?.hide()
        onSearch()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Atau ketik namanya (tanpa AI)") },
        placeholder = { Text("mis. Nissan Skyline") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (value.isNotBlank()) {
            { TextButton(onClick = search) { Text("Cari") } }
        } else {
            null
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class VerdictStyle(val title: String, @param:DrawableRes val icon: Int, val color: Color)

/** Status is never colour-only: each verdict has its icon and words. */
private fun Verdict.style() = when (this) {
    Verdict.Owned -> VerdictStyle("Sudah punya", R.drawable.ic_check_circle, Color(0xFF34D399))
    Verdict.Wishlist -> VerdictStyle("Ada di wishlist kamu!", R.drawable.ic_favorite, Color(0xFFFB7185))
    Verdict.Spotted -> VerdictStyle("Pernah kamu temui", R.drawable.ic_visibility, Color(0xFFA78BFA))
    Verdict.New -> VerdictStyle("Belum punya", R.drawable.ic_add, Color(0xFF38BDF8))
    Verdict.Failed -> VerdictStyle("Tidak terbaca", R.drawable.ic_warning, Garage.Brand2)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    result: CheckResult,
    diecasts: Map<Long, DiecastListItem>,
    busy: Boolean,
    older: Boolean,
    viewModel: CheckViewModel,
    onOpen: (Long) -> Unit,
    onAdd: (DiecastStatus?) -> Unit,
) {
    val verdict = result.verdict(diecasts)
    val style = verdict.style()
    val matches = result.matchIds.mapNotNull(diecasts::get)
    val canSpot = !result.done && verdict != Verdict.Failed
    // Something to log as seen here: a new one, or one already wanted or seen (owned ones only get +1).
    val spottable = verdict == Verdict.New || matches.any { it.status == DiecastStatus.Wishlist || it.status == DiecastStatus.Spotted }
    val shape = MaterialTheme.shapes.large

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (older) 0.7f else 1f)
            .background(style.color.copy(alpha = 0.08f), shape)
            .border(2.dp, style.color.copy(alpha = 0.5f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumb = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))
            if (result.photo != null) {
                DiecastPhoto(result.photo, null, thumb, placeholderSize = 32.dp)
            } else {
                Box(thumb.background(Garage.Muted), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(32.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(painterResource(style.icon), contentDescription = null, tint = style.color, modifier = Modifier.size(20.dp))
                    Text(style.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = style.color)
                }
                val name = result.name
                if (name != null) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    details(result)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
                } else {
                    Text(result.error ?: "AI tidak bisa mengenali diecast ini.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (matches.isNotEmpty()) {
            MatchPanel(
                matches = matches,
                canSpot = canSpot,
                busy = busy,
                onOpen = onOpen,
                onAddOne = { viewModel.addOne(result, it) },
                onAcquire = { viewModel.acquire(result, it.id) },
                onSpot = { viewModel.spot(result, it) },
            )
        }

        when {
            result.loggedAt != null -> {
                Note(R.drawable.ic_visibility, "Dicatat sebagai temuan di ${result.loggedAt}.")
                // Logged as a new "Ditemui" one and then bought after all.
                val created = result.createdId?.let(diecasts::get)
                if (created?.status == DiecastStatus.Spotted) {
                    OutlinedButton(onClick = { viewModel.acquire(result, created.id) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_check_circle, "Jadi beli — Dapat")
                    }
                }
            }
            result.saved -> Note(R.drawable.ic_check_circle, "Sudah disimpan lewat form.")
            canSpot && spottable -> MoneyInput(result.price, { viewModel.setPrice(result.key, it) }, "Harga di rak", placeholder = "Opsional")
        }

        if (!result.done) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (verdict == Verdict.New && canSpot) {
                    Button(onClick = { viewModel.spot(result) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Catat temuan")
                        } else {
                            IconLabel(R.drawable.ic_visibility, "Catat temuan")
                        }
                    }
                }
                if (verdict == Verdict.Failed) {
                    OutlinedButton(onClick = { viewModel.retry(result) }, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_sync, if (busy) "Mengecek…" else "Coba lagi")
                    }
                }
                FilledTonalButton(onClick = { onAdd(null) }, shape = MaterialTheme.shapes.medium) {
                    IconLabel(R.drawable.ic_add, if (verdict == Verdict.Owned) "Tambah sebagai item terpisah" else "Tambah ke koleksi")
                }
                if (verdict != Verdict.Wishlist) {
                    OutlinedButton(onClick = { onAdd(DiecastStatus.Wishlist) }, shape = MaterialTheme.shapes.medium) {
                        IconLabel(R.drawable.ic_favorite, "Wishlist")
                    }
                }
            }
        }
    }
}

/** Brand · series · number · rarity, as read; "Dicari dengan mengetik" for a typed search. */
private fun details(result: CheckResult): String? {
    val fields = result.fields ?: return "Dicari dengan mengetik".takeIf { result.query != null }
    return listOfNotNull(
        fields.brand,
        fields.series,
        fields.collectionNumber,
        fields.rarity?.takeIf { it != Rarity.Regular }?.label,
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun Note(@DrawableRes icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Look-alikes in the collection, each with its one-tap actions (web: `MatchList` with actions). */
@Composable
private fun MatchPanel(
    matches: List<DiecastListItem>,
    canSpot: Boolean,
    busy: Boolean,
    onOpen: (Long) -> Unit,
    onAddOne: (DiecastListItem) -> Unit,
    onAcquire: (DiecastListItem) -> Unit,
    onSpot: (DiecastListItem) -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .background(Garage.Card, shape)
            .border(1.dp, Garage.Border, shape)
            .padding(vertical = 4.dp),
    ) {
        Text(
            "Di koleksimu",
            style = MaterialTheme.typography.labelMedium,
            color = Garage.MutedForeground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        matches.forEach { match ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Buka ${match.name}") { onOpen(match.id) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DiecastPhoto(match.coverFile, null, Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)), placeholderSize = 24.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(match.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val small = MaterialTheme.typography.bodySmall
                    listOfNotNull(match.brand, match.series, match.color).joinToString(" · ").takeIf(String::isNotEmpty)?.let {
                        Text(it, style = small, color = Garage.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // What the shelf price is up against.
                    if (match.status == DiecastStatus.Wishlist && match.targetPrice != null) {
                        Text("Target maks ${formatRupiah(match.targetPrice)}", style = small, color = Garage.MutedForeground)
                    }
                    if (match.sightingsCount > 0 && match.sightingsMinPrice != null) {
                        Text("Pernah ditemui mulai ${formatRupiah(match.sightingsMinPrice)}", style = small, color = Garage.MutedForeground)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(
                            text = when (match.status) {
                                DiecastStatus.Owned -> "Punya ${match.quantity}×"
                                DiecastStatus.Spotted -> "Ditemui ${match.sightingsCount}×"
                                else -> match.status.label
                            },
                            background = Garage.Accent,
                            content = Garage.Foreground,
                            icon = match.status.icon(),
                        )
                        Spacer(Modifier.weight(1f))
                        MatchActions(match, canSpot, busy, onAddOne, onAcquire, onSpot)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchActions(
    match: DiecastListItem,
    canSpot: Boolean,
    busy: Boolean,
    onAddOne: (DiecastListItem) -> Unit,
    onAcquire: (DiecastListItem) -> Unit,
    onSpot: (DiecastListItem) -> Unit,
) {
    val small = PaddingValues(horizontal = 12.dp)
    when (match.status) {
        DiecastStatus.Owned -> OutlinedButton(
            onClick = { onAddOne(match) },
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            contentPadding = small,
            modifier = Modifier.height(36.dp).semantics { contentDescription = "Beli satu lagi (+1 jumlah)" },
        ) { Text("+1") }
        DiecastStatus.Wishlist, DiecastStatus.Spotted -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canSpot) {
                OutlinedIconButton(onClick = { onSpot(match) }, enabled = !busy, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_visibility), contentDescription = "Catat temuan di sini", Modifier.size(18.dp))
                }
            }
            Button(onClick = { onAcquire(match) }, enabled = !busy, shape = MaterialTheme.shapes.medium, contentPadding = small, modifier = Modifier.height(36.dp)) {
                IconLabel(R.drawable.ic_check_circle, "Dapat")
            }
        }
        DiecastStatus.Sold -> Unit
    }
}
