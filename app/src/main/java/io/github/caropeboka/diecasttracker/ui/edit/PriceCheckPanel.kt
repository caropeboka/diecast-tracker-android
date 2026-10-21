package io.github.caropeboka.diecasttracker.ui.edit

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastStatus
import io.github.caropeboka.diecasttracker.data.PhotoStore
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.db.DiecastEntity
import io.github.caropeboka.diecasttracker.data.db.PriceCheckEntity
import io.github.caropeboka.diecasttracker.data.price.ListingStatus
import io.github.caropeboka.diecasttracker.data.price.MAX_PRICE_SCREENSHOTS
import io.github.caropeboka.diecasttracker.data.price.PriceAccuracy
import io.github.caropeboka.diecasttracker.data.price.PriceChecker
import io.github.caropeboka.diecasttracker.data.price.PriceListing
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.formatRupiah
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId

/** A saved diecast's market price checks: search, screenshots, the AI's reading, and the price to use. */
class PriceCheckViewModel(
    private val saved: SavedStateHandle,
    private val checker: PriceChecker,
    private val photos: PhotoStore,
    private val messages: Messages,
    diecastId: Long,
) : ViewModel() {
    /** Newest first; null while loading. */
    val checks: StateFlow<List<PriceCheckEntity>?> =
        checker.observe(diecastId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val enabled: StateFlow<Boolean> = checker.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The steps (search, screenshots) are showing. */
    val open: StateFlow<Boolean> = saved.getStateFlow(OPEN, false)

    /** Null until edited: the screen fills in [PriceChecker.searchQuery]. */
    val query: StateFlow<String?> = saved.getStateFlow(QUERY, null)

    /** Picked screenshots (content URIs), read only when the check runs. */
    val picked: StateFlow<List<String>> = saved.getStateFlow(PICKED, emptyList())

    var analyzing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var applyingId by mutableStateOf<Long?>(null)
        private set

    fun setOpen(value: Boolean) {
        saved[OPEN] = value
        error = null
    }

    fun setQuery(text: String) {
        saved[QUERY] = text
    }

    fun addScreenshots(uris: List<Uri>) {
        val all = (picked.value + uris.map(Uri::toString)).distinct()
        saved[PICKED] = ArrayList(all.take(MAX_PRICE_SCREENSHOTS))
        error = if (all.size > MAX_PRICE_SCREENSHOTS) "Maksimal $MAX_PRICE_SCREENSHOTS screenshot sekali cek." else null
    }

    fun removeScreenshot(index: Int) {
        saved[PICKED] = ArrayList(picked.value.filterIndexed { i, _ -> i != index })
    }

    fun analyze(diecast: DiecastEntity) {
        val uris = picked.value
        if (analyzing || uris.isEmpty()) return

        analyzing = true
        error = null
        viewModelScope.launch {
            try {
                val images = try {
                    withContext(Dispatchers.IO) { uris.map { photos.screenshot(it.toUri()) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    throw AiException("Screenshot tidak bisa dibuka lagi. Hapus lalu pilih ulang dari galeri.")
                }
                checker.check(diecast, images)
                saved[PICKED] = ArrayList<String>()
                saved[OPEN] = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                error = e.message
            } catch (e: Exception) {
                error = "Gagal membaca screenshot: ${e.message ?: e::class.simpleName}"
            } finally {
                analyzing = false
            }
        }
    }

    /** Its suggested price becomes the market value right away; [onApplied] gets the saved diecast. */
    fun apply(check: PriceCheckEntity, onApplied: (DiecastEntity) -> Unit) {
        if (applyingId != null) return
        applyingId = check.id
        viewModelScope.launch {
            try {
                val diecast = checker.apply(check)
                onApplied(diecast)
                messages.show("Harga pasaran diset ${formatRupiah(diecast.marketValue)}.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show("Gagal memakai harga: ${e.message ?: e::class.simpleName}")
            } finally {
                applyingId = null
            }
        }
    }

    fun delete(check: PriceCheckEntity) {
        viewModelScope.launch { checker.delete(check.id) }
    }

    companion object {
        private const val OPEN = "price_open"
        private const val QUERY = "price_query"
        private const val PICKED = "price_picked"

        fun factory(diecastId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { PriceCheckViewModel(createSavedStateHandle(), priceChecker, photos, messages, diecastId) }
            }
        }
    }
}

/**
 * "Cek harga pasaran" (web: `price-check-panel.tsx`): search the diecast on Tokopedia/Shopee, screenshot the results,
 * and the AI reads every listing; the app works out the median. Only for saved diecasts.
 */
@Composable
fun PriceCheckPanel(diecast: DiecastEntity, onApplied: (DiecastEntity) -> Unit, onAiSettings: () -> Unit) {
    val viewModel: PriceCheckViewModel = viewModel(key = "price-check-${diecast.id}", factory = PriceCheckViewModel.factory(diecast.id))
    val checks by viewModel.checks.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    var showOlder by rememberSaveable { mutableStateOf(false) }
    val all = checks.orEmpty()

    SectionCard(
        "Cek harga pasaran",
        action = if (!open && enabled) {
            { TextButton(onClick = { viewModel.setOpen(true) }) { IconLabel(R.drawable.ic_search, if (all.isEmpty()) "Cek harga" else "Cek ulang") } }
        } else {
            null
        },
    ) {
        when {
            !enabled -> {
                Hint("Cek harga memakai AI untuk membaca screenshot marketplace. Isi API key Gemini dulu di Pengaturan AI.")
                OutlinedButton(onClick = onAiSettings, shape = MaterialTheme.shapes.medium) { IconLabel(R.drawable.ic_sparkles, "Pengaturan AI") }
            }
            open -> Steps(viewModel, diecast)
            all.isEmpty() -> Hint(
                "Cari diecast ini di Tokopedia/Shopee, screenshot hasilnya, lalu AI akan membaca semua listing dan menghitung harga pasarannya.",
            )
        }

        all.firstOrNull()?.let { latest ->
            CheckResult(latest, diecast, viewModel.applyingId == latest.id, { viewModel.apply(latest, onApplied) }, { viewModel.delete(latest) }, compact = false)
        }

        // The web keeps the last 5 in view.
        val older = all.drop(1).take(4)
        if (older.isNotEmpty()) {
            TextButton(onClick = { showOlder = !showOlder }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showOlder) "Sembunyikan cek sebelumnya" else "Lihat ${older.size} cek sebelumnya")
            }
            if (showOlder) {
                older.forEach { check ->
                    CheckResult(check, diecast, viewModel.applyingId == check.id, { viewModel.apply(check, onApplied) }, { viewModel.delete(check) }, compact = true)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)
}

@Composable
private fun Steps(viewModel: PriceCheckViewModel, diecast: DiecastEntity) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val picked by viewModel.picked.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val text = query ?: PriceChecker.searchQuery(diecast)
    val encoded = URLEncoder.encode(text, "UTF-8")
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PRICE_SCREENSHOTS), viewModel::addScreenshots)

    fun open(url: String) = runCatching { uriHandler.openUri(url) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("1. Cari di marketplace", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = text,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text("Kata kunci pencarian") },
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { open("https://www.tokopedia.com/search?st=product&q=$encoded") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) { IconLabel(R.drawable.ic_open_in_new, "Tokopedia") }
            OutlinedButton(
                onClick = { open("https://shopee.co.id/search?keyword=$encoded") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) { IconLabel(R.drawable.ic_open_in_new, "Shopee") }
        }
        Text(
            "Tips: urutkan \"Terlaris\", lalu screenshot 2–4 layar sambil scroll ke bawah.",
            style = MaterialTheme.typography.bodySmall,
            color = Garage.MutedForeground,
        )

        Text("2. Pilih screenshot (${picked.size}/$MAX_PRICE_SCREENSHOTS)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(picked, key = { _, uri -> uri }) { index, uri ->
                Box(Modifier.size(width = 64.dp, height = 112.dp).clip(RoundedCornerShape(6.dp)).background(Garage.Muted)) {
                    AsyncImage(model = uri, contentDescription = "Screenshot ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                            .clickable(enabled = !viewModel.analyzing, onClickLabel = "Hapus screenshot") { viewModel.removeScreenshot(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            if (picked.size < MAX_PRICE_SCREENSHOTS) {
                item {
                    Column(
                        Modifier
                            .size(width = 64.dp, height = 112.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, Garage.Border, RoundedCornerShape(6.dp))
                            .clickable(enabled = !viewModel.analyzing) {
                                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_photo_library), contentDescription = null, tint = Garage.MutedForeground)
                        Text("Tambah", style = MaterialTheme.typography.labelSmall, color = Garage.MutedForeground)
                    }
                }
            }
        }

        viewModel.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Loss) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.analyze(diecast) },
                enabled = picked.isNotEmpty() && !viewModel.analyzing,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                if (viewModel.analyzing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("AI membaca ${picked.size} screenshot…", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                } else {
                    IconLabel(R.drawable.ic_sparkles, "Hitung harga pasaran")
                }
            }
            TextButton(onClick = { viewModel.setOpen(false) }, enabled = !viewModel.analyzing) { Text("Batal") }
        }
    }
}

private fun PriceAccuracy.color() = when (this) {
    PriceAccuracy.High -> Garage.Gain
    PriceAccuracy.Medium -> Color(0xFF3987E5)
    PriceAccuracy.Low -> Garage.Brand2
    PriceAccuracy.Insufficient -> Garage.Loss
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CheckResult(
    check: PriceCheckEntity,
    diecast: DiecastEntity,
    applying: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    compact: Boolean,
) {
    var showListings by rememberSaveable(check.id) { mutableStateOf(!compact) }
    val stats = check.stats
    val counted = check.listings.count { it.status == ListingStatus.Included }
    val target = diecast.targetPrice?.takeIf { diecast.status == DiecastStatus.Wishlist }
    val day = Instant.ofEpochMilli(check.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (compact) Garage.Muted.copy(alpha = 0.4f) else Garage.Card,
        border = BorderStroke(1.dp, Garage.Border),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Dicek ${formatDate(day)} · ${check.listings.size} listing terbaca dari ${check.screenshots} screenshot",
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(check.accuracy.label, check.accuracy.color())
                        if (check.appliedAt != null) Pill("Dipakai", Garage.MutedForeground)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = "Hapus hasil cek ini", tint = Garage.MutedForeground)
                }
            }

            if (stats != null) {
                Column {
                    Text("Median (${stats.count} listing)", style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
                    Text(formatRupiah(stats.median), style = MaterialTheme.typography.headlineSmall)
                    Text("Rentang wajar: ${formatRupiah(stats.q1)} – ${formatRupiah(stats.q3)}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Termurah ${formatRupiah(stats.min)} · termahal ${formatRupiah(stats.max)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.MutedForeground,
                    )
                }
                if (target != null) {
                    val realistic = target >= stats.median
                    Text(
                        "Target kamu ${formatRupiah(target)} — " +
                            if (realistic) "realistis, di atas median pasar." else "di bawah median, perlu sabar cari promo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (realistic) Garage.Gain else Garage.Brand2,
                    )
                }
            } else {
                Text("Belum ada listing yang cocok.", style = MaterialTheme.typography.bodyMedium)
            }

            check.reasons.forEach { Bullet(it, Garage.MutedForeground) }
            check.tips.forEach { Bullet(it, Garage.Brand2) }
            check.aiNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = Garage.MutedForeground)
            }

            if (stats != null) {
                val label = "Pakai ${formatRupiah(stats.suggested)} sebagai harga pasaran"
                if (check.appliedAt == null) {
                    Button(onClick = onApply, enabled = !applying, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(onClick = onApply, enabled = !applying, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (check.listings.isNotEmpty()) {
                TextButton(onClick = { showListings = !showListings }, contentPadding = PaddingValues(0.dp)) {
                    Text("${if (showListings) "Sembunyikan" else "Lihat"} rincian ${check.listings.size} listing ($counted dihitung)")
                }
                if (showListings) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        check.listings.forEach { ListingRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (color == Garage.MutedForeground) Garage.Foreground else color)
    }
}

@Composable
private fun ListingRow(listing: PriceListing) {
    val counted = listing.status == ListingStatus.Included
    val (icon, tint, state) = when (listing.status) {
        ListingStatus.Included -> Triple(R.drawable.ic_check_circle, Garage.Gain, "Dihitung")
        ListingStatus.Outlier -> Triple(R.drawable.ic_warning, Garage.Brand2, "Tidak dihitung")
        ListingStatus.Excluded -> Triple(R.drawable.ic_block, Garage.MutedForeground, "Tidak dihitung")
    }
    val meta = listOfNotNull(
        listing.shop,
        listing.location,
        listing.sold?.takeIf { it > 0 }?.let { "$it+ terjual" },
        "Iklan".takeIf { listing.isAd },
        listing.marketplace.takeIf { it != "tokopedia" },
    ).joinToString(" · ")

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), contentDescription = state, tint = tint, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listing.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (counted) Garage.Foreground else Garage.MutedForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Garage.MutedForeground)
            if (!counted) {
                Text(
                    "Tidak dihitung: ${listing.excludedReason ?: "Tidak jelas"}" + listing.reason?.let { " — $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Garage.MutedForeground,
                )
            }
        }
        Text(
            formatRupiah(listing.price) + if (listing.isPriceRange) "+" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (counted) FontWeight.Medium else FontWeight.Normal,
            color = if (counted) Garage.Foreground else Garage.MutedForeground,
            textDecoration = if (counted) null else TextDecoration.LineThrough,
        )
    }
}
