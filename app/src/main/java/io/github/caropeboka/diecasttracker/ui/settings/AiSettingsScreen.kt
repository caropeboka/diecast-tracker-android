package io.github.caropeboka.diecasttracker.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import io.github.caropeboka.diecasttracker.data.ai.DEFAULT_GEMINI_MODEL
import io.github.caropeboka.diecasttracker.ui.components.ChoiceInput
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.TextInput
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage

private const val KEY_URL = "https://aistudio.google.com/apikey"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onClose: () -> Unit,
    viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory),
) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val savedKey = current?.keyHint

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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (viewModel.fromScan && savedKey == null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Garage.Brand.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Garage.Brand.copy(alpha = 0.4f)),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(18.dp))
                        Text(
                            "Scan belum aktif. Isi API key Gemini dulu, setelah itu tombol Scan langsung membaca foto kartu.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Text(
                "Scan AI membaca foto kartu atau mobilnya lalu mengisi form: nama, merek, seri, nomor, tahun, kelangkaan. " +
                    "Fotonya dikirim ke Google Gemini hanya saat scan.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )

            SectionCard("Google Gemini") {
                Text(
                    "Ada kuota gratis harian. Cocok untuk mulai.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )

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
                            onClick = { confirmRemove = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text("Hapus key")
                        }
                    }
                } else if (current != null) {
                    Text("Belum ada API key.", style = MaterialTheme.typography.bodyMedium)
                }

                OutlinedTextField(
                    value = viewModel.keyInput,
                    onValueChange = viewModel::changeKey,
                    label = { Text(if (savedKey != null) "Ganti API key" else "API key") },
                    placeholder = { Text("AIza…") },
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
                    OutlinedButton(
                        onClick = viewModel::testKey,
                        enabled = viewModel.test != KeyTest.Testing,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (viewModel.test == KeyTest.Testing) {
                            CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("Tes key")
                        } else {
                            IconLabel(R.drawable.ic_check, "Tes key")
                        }
                    }
                    TextButton(onClick = { uriHandler.openUri(KEY_URL) }, shape = MaterialTheme.shapes.medium) {
                        Text("Buat key di AI Studio ↗")
                    }
                }

                when (val test = viewModel.test) {
                    is KeyTest.Ok -> Text(
                        if (test.models.isEmpty()) "Key valid, tapi tidak ada model Gemini yang bisa membaca foto." else "Key valid. ${test.models.size} model bisa dipakai — pilih di bawah.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.Gain,
                    )
                    is KeyTest.Failed -> Text(test.message, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
                    KeyTest.Idle, KeyTest.Testing -> Unit
                }

                val tested = (viewModel.test as? KeyTest.Ok)?.models.orEmpty()
                if (tested.isNotEmpty()) {
                    ChoiceInput(
                        selected = viewModel.model,
                        options = (listOf(viewModel.model) + tested).filter(String::isNotEmpty).distinct(),
                        optionLabel = { it },
                        onSelect = viewModel::changeModel,
                        label = "Model",
                    )
                } else {
                    TextInput(
                        viewModel.model, viewModel::changeModel, "Model",
                        placeholder = DEFAULT_GEMINI_MODEL, error = viewModel.modelError,
                        capitalization = KeyboardCapitalization.None,
                    )
                }
                Text(
                    "Kalau model ini sibuk atau kuota gratisnya habis, model Gemini lain dicoba otomatis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                )
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Hapus API key?") },
            text = { Text("Scan AI tidak aktif sampai kamu mengisi key lagi.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeKey()
                }) { Text("Hapus", color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Batal") } },
        )
    }
}
