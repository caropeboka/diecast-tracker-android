package io.github.caropeboka.diecasttracker.ui.data

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.backup.BackupImporter
import io.github.caropeboka.diecasttracker.data.backup.ImportState
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class Counts(val diecasts: Int = 0, val photos: Int = 0)

class DataViewModel(repository: DiecastRepository, private val importer: BackupImporter) : ViewModel() {
    val counts: StateFlow<Counts> = combine(repository.observeCount(), repository.observePhotoCount(), ::Counts)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Counts())

    val importState: StateFlow<ImportState> = importer.state

    fun import(uri: Uri, replace: Boolean) = importer.start(uri, replace)

    fun dismissResult() = importer.dismiss()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                DataViewModel(app.container.repository, app.container.backupImporter)
            }
        }
    }
}

/** The zip MIME type varies by source (Drive, file managers, browsers). The importer checks the content anyway. */
private val ZIP_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(
    onBack: () -> Unit,
    viewModel: DataViewModel = viewModel(factory = DataViewModel.Factory),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    var replace by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val running = importState is ImportState.Running

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.import(uri, replace)
            confirmed = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading("Data & backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Kembali")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Koleksimu: ${counts.diecasts} diecast, ${counts.photos} foto.",
                style = MaterialTheme.typography.bodyMedium,
                color = Garage.MutedForeground,
            )

            ImportFeedback(importState, viewModel::dismissResult)

            SectionCard("Pulihkan dari backup web") {
                Text(
                    "Di web app buka Settings → Backup & Data → Download backup (.zip), kirim filenya ke HP " +
                        "(Google Drive, kabel USB, dll.), lalu pilih di sini. Data dan semua fotonya ikut.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                )

                Column(Modifier.selectableGroup()) {
                    ModeOption("Tambahkan ke koleksi sekarang", selected = !replace, enabled = !running) { replace = false }
                    ModeOption("Ganti seluruh koleksi sekarang dengan isi backup", selected = replace, enabled = !running) {
                        replace = true
                    }
                }

                if (replace) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = Garage.Loss.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, Garage.Loss.copy(alpha = 0.4f)),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(confirmed, role = Role.Checkbox, enabled = !running) { confirmed = it }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = confirmed, onCheckedChange = null)
                            Text(
                                "Saya mengerti ${counts.diecasts} diecast yang ada sekarang (beserta fotonya) akan dihapus dan diganti isi backup.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }

                Button(
                    onClick = { picker.launch(ZIP_TYPES) },
                    enabled = !running && (!replace || confirmed),
                    shape = MaterialTheme.shapes.medium,
                    colors = if (replace) {
                        ButtonDefaults.buttonColors(containerColor = Garage.Loss, contentColor = Color(0xFF1A0606))
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconLabel(R.drawable.ic_folder_zip, "Pilih file backup (.zip)")
                }
            }

            Text(
                "Backup dan export dari HP menyusul di tahap 5. Sampai saat itu, web app tetap jadi cadangannya.",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ImportFeedback(state: ImportState, onDismiss: () -> Unit) {
    when (state) {
        ImportState.Idle -> Unit
        is ImportState.Running -> SectionCard(null) {
            Text(
                if (state.total == 0) "Membaca file backup…" else "Memulihkan ${state.done} dari ${state.total} diecast…",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.total == 0) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "Boleh pindah layar; jangan tutup aplikasinya sampai selesai.",
                style = MaterialTheme.typography.bodySmall,
                color = Garage.MutedForeground,
            )
        }
        is ImportState.Failed -> Notice(Garage.Loss, R.drawable.ic_warning, state.message, emptyList(), onDismiss)
        is ImportState.Finished -> {
            val result = state.result
            val title = buildString {
                append("${result.imported} diecast berhasil ")
                append(if (state.replaced) "dipulihkan" else "ditambahkan")
                if (result.skipped.isNotEmpty()) append(", ${result.skipped.size} dilewati:") else append(".")
            }
            val details = listOfNotNull(result.exportedOn?.let { "Backup dibuat ${formatDate(it)}." }) + result.skipped.take(50)
            Notice(
                if (result.skipped.isEmpty()) Garage.Gain else Garage.Brand2,
                if (result.skipped.isEmpty()) R.drawable.ic_check_circle else R.drawable.ic_warning,
                title,
                details,
                onDismiss,
            )
        }
    }
}

@Composable
private fun Notice(color: Color, icon: Int, title: String, lines: List<String>, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            lines.forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = Garage.MutedForeground,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp, end = 8.dp),
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Tutup") }
        }
    }
}
