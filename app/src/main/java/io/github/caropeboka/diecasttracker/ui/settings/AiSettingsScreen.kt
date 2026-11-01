package io.github.caropeboka.diecasttracker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.ai.ProviderSettings
import io.github.caropeboka.diecasttracker.ui.components.ChoiceInput
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.components.restEnd
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onClose: () -> Unit,
    viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory),
) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    val resting by viewModel.resting.collectAsStateWithLifecycle()
    var confirmRemove by rememberSaveable { mutableStateOf<AiProvider?>(null) }

    LaunchedEffect(viewModel) { viewModel.closed.collect { onClose() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Pengaturan AI") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = Garage.Card, border = BorderStroke(1.dp, Garage.Border)) {
                Button(
                    onClick = viewModel::save,
                    enabled = !viewModel.saving && current != null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Simpan")
                }
            }
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (viewModel.fromScan && current?.isEnabled == false) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Garage.Brand.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Garage.Brand.copy(alpha = 0.4f)),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
                        Text(
                            "Scan belum aktif. Isi API key salah satu AI dulu (Gemini paling mudah). Setelah itu, Scan dengan AI (tombol + di tengah bawah) membuka kamera: foto semua sisinya, lalu ketuk Pindai dengan AI.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Text(
                "AI membaca foto kartu atau mobilnya lalu mengisi form. Fotonya dikirim hanya saat kamu meminta AI membaca. " +
                    "AI dicoba dari atas ke bawah: kalau satu kuotanya habis, sibuk, atau terlalu lama, foto yang sama langsung dikirim ke berikutnya.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )

            val setup = current
            if (setup != null) {
                viewModel.order.forEachIndexed { index, provider ->
                    ProviderCard(
                        index = index,
                        count = viewModel.order.size,
                        draft = viewModel.drafts.getValue(provider),
                        saved = setup[provider],
                        restUntil = resting[provider] ?: 0,
                        expanded = viewModel.expanded == provider,
                        viewModel = viewModel,
                        onRemove = { confirmRemove = provider },
                    )
                }
            }
        }
    }

    confirmRemove?.let { provider ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Hapus API key ${provider.title}?") },
            text = { Text("${provider.title} tidak dipakai sampai kamu mengisi key lagi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeKey(provider)
                }) { Text("Hapus", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun ProviderCard(
    index: Int,
    count: Int,
    draft: ProviderDraft,
    saved: ProviderSettings,
    restUntil: Long,
    expanded: Boolean,
    viewModel: AiSettingsViewModel,
    onRemove: () -> Unit,
) {
    val provider = draft.provider
    val uriHandler = LocalUriHandler.current
    var showKey by rememberSaveable(provider) { mutableStateOf(false) }
    val custom = provider == AiProvider.Custom
    val configured = if (custom) !saved.baseUrl.isNullOrBlank() else saved.keyHint != null
    val active = configured && draft.on
    val status = when {
        !configured && draft.keyInput.isNotEmpty() -> "Key baru belum disimpan"
        !configured -> "Belum diatur"
        !draft.on -> "Dimatikan"
        restUntil > System.currentTimeMillis() -> "Kuota habis sampai ${restEnd(restUntil)}"
        else -> "Aktif · " + (if (custom) saved.label else saved.keyHint)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Garage.Card,
        border = BorderStroke(1.dp, if (expanded) Garage.Brand.copy(alpha = 0.5f) else Garage.Border),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggle(provider) }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = if (active) Garage.Brand else Garage.Muted, modifier = Modifier.size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = if (active) Garage.OnBrand else Garage.MutedForeground)
                    }
                }
                Column(
                    Modifier
                        .padding(start = 12.dp)
                        .weight(1f),
                ) {
                    Text(if (custom && !draft.name.isBlank()) draft.name else provider.title, style = MaterialTheme.typography.titleSmall)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (active) Garage.MutedForeground else Garage.MutedForeground.copy(alpha = 0.7f))
                }
                IconButton(onClick = { viewModel.move(provider, -1) }, enabled = index > 0) {
                    Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = "Naikkan ${provider.title}", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.move(provider, 1) }, enabled = index < count - 1) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = "Turunkan ${provider.title}", modifier = Modifier.size(20.dp))
                }
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = if (expanded) "Tutup" else "Buka",
                    tint = Garage.MutedForeground,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .rotate(if (expanded) 180f else 0f),
                )
            }

            if (expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(note(provider), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)

                    if (configured || draft.keyInput.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pakai ${provider.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = draft.on, onCheckedChange = { viewModel.changeOn(provider, it) })
                        }
                    }

                    if (custom) {
                        TextInput(draft.name, viewModel::changeName, "Nama (opsional)", placeholder = "mis. NVIDIA")
                        TextInput(
                            draft.baseUrl, viewModel::changeUrl, "Alamat server",
                            placeholder = "https://…/v1", error = draft.urlError,
                            keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None,
                        )
                    }

                    val savedKey = saved.keyHint
                    if (savedKey != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Neutral: a saved key is not a tested one.
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
                            Text(
                                "Key tersimpan: $savedKey",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .weight(1f),
                            )
                            TextButton(
                                onClick = onRemove,
                                colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Text("Hapus key")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = draft.keyInput,
                        onValueChange = { viewModel.changeKey(provider, it) },
                        label = { Text(if (savedKey != null) "Ganti API key" else if (custom) "API key (opsional)" else "API key") },
                        placeholder = { Text(keyPlaceholder(provider)) },
                        supportingText = if (savedKey != null) {
                            { Text("Kosongkan untuk tetap memakai key yang tersimpan.") }
                        } else {
                            null
                        },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    painterResource(R.drawable.ic_visibility),
                                    contentDescription = if (showKey) "Sembunyikan key" else "Tampilkan key",
                                    tint = if (showKey) Garage.Brand else Garage.MutedForeground,
                                )
                            }
                        },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val label = if (custom) "Tes server" else "Tes key"
                        OutlinedButton(
                            onClick = { viewModel.testKey(provider) },
                            enabled = draft.test != KeyTest.Testing,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (draft.test == KeyTest.Testing) {
                                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(label)
                            } else {
                                IconLabel(R.drawable.ic_check, label)
                            }
                        }
                        provider.keyUrl?.let { url ->
                            TextButton(onClick = { uriHandler.openUri(url) }, shape = MaterialTheme.shapes.medium) {
                                Text("Buat key di ${if (provider == AiProvider.Gemini) "AI Studio" else provider.title} ↗")
                            }
                        }
                    }

                    when (val test = draft.test) {
                        is KeyTest.Ok -> Text(
                            when {
                                test.models.isEmpty() -> "Tersambung, tapi tidak ada model yang bisa membaca foto."
                                provider == AiProvider.Groq || custom -> "Tersambung. ${test.models.size} model — pilih yang bisa membaca foto (vision)."
                                else -> "Key valid. ${test.models.size} model bisa membaca foto — pilih di bawah."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Garage.Gain,
                        )
                        is KeyTest.Failed -> Text(test.message, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
                        KeyTest.Idle, KeyTest.Testing -> Unit
                    }

                    val tested = (draft.test as? KeyTest.Ok)?.models.orEmpty()
                    if (tested.isNotEmpty()) {
                        ChoiceInput(
                            selected = draft.model,
                            options = (listOf(draft.model) + tested).filter(String::isNotEmpty).distinct(),
                            optionLabel = { it },
                            onSelect = { viewModel.changeModel(provider, it) },
                            label = "Model",
                        )
                    } else {
                        TextInput(
                            draft.model, { viewModel.changeModel(provider, it) }, "Model",
                            placeholder = provider.defaultModel.ifEmpty { "nama model" }, error = draft.modelError,
                            capitalization = KeyboardCapitalization.None,
                        )
                    }
                }
            }
        }
    }
}

private fun note(provider: AiProvider) = when (provider) {
    AiProvider.Gemini -> "Kuota gratis harian dari Google. Kalau satu model sibuk atau kuotanya habis, model Gemini lain dicoba dulu sebelum pindah ke AI berikutnya."
    AiProvider.Groq -> "Gratis dan sangat cepat (sekitar 1.000 permintaan sehari). Sekali kirim paling banyak 3 foto; foto lainnya tidak ikut."
    AiProvider.Mistral -> "Gratis lewat paket Experiment (perlu verifikasi nomor HP). Di paket gratis, foto yang dikirim boleh dipakai Mistral untuk melatih modelnya."
    AiProvider.OpenRouter -> "Model gratis: 50 permintaan sehari (1.000 sehari setelah sekali isi kredit \$10). Model openrouter/free memilih sendiri model gratis yang bisa membaca foto."
    AiProvider.Custom -> "Layanan lain yang memakai format API OpenAI. Contoh alamat — NVIDIA NIM: https://integrate.api.nvidia.com/v1, " +
        "Cloudflare Workers AI: https://api.cloudflare.com/client/v4/accounts/<ACCOUNT_ID>/ai/v1. Pilih model yang bisa membaca foto."
}

private fun keyPlaceholder(provider: AiProvider) = when (provider) {
    AiProvider.Gemini -> "AIza…"
    AiProvider.Groq -> "gsk_…"
    AiProvider.OpenRouter -> "sk-or-…"
    AiProvider.Custom -> "Kosongkan kalau servernya tanpa key"
    AiProvider.Mistral -> ""
}
