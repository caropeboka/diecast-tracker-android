package io.github.caropeboka.diecasttracker.ui.data

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.backup.AutoBackupSettings
import io.github.caropeboka.diecasttracker.data.backup.AutoBackupState
import io.github.caropeboka.diecasttracker.data.backup.BackupPlaces
import io.github.caropeboka.diecasttracker.data.backup.PlaceKind
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.formatDate
import java.time.Instant
import java.time.ZoneId

/** The automatic backup's settings with the names of its places, read from their apps. */
data class AutoBackupView(
    val settings: AutoBackupSettings = AutoBackupSettings(),
    val folderName: String? = null,
    val copyName: String? = null,
    /** The app that holds the copy, e.g. "Dropbox": only for a copy neither on the phone nor on Drive. */
    val copyApp: String? = null,
) {
    val copyKind: PlaceKind? get() = settings.copy?.let(BackupPlaces::kind)
}

/** Data & backup's "Automatic backup": its two places, the weekly switch, how the last one went and "Back up now". */
@Composable
internal fun AutoBackupCard(
    view: AutoBackupView,
    state: AutoBackupState,
    busy: Boolean,
    onOn: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onRemoveFolder: () -> Unit,
    onPickCopy: () -> Unit,
    onRemoveCopy: () -> Unit,
    onNow: () -> Unit,
) {
    val settings = view.settings
    val running = state as? AutoBackupState.Running
    SectionCard(stringResource(R.string.auto_backup_title)) {
        Text(stringResource(R.string.auto_backup_text), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)

        Place(
            icon = R.drawable.ic_folder_zip,
            title = stringResource(R.string.auto_backup_folder),
            chosen = settings.folder?.let { view.folderName ?: shortName(it) },
            hint = stringResource(R.string.auto_backup_folder_hint),
            pick = stringResource(R.string.auto_backup_pick_folder),
            enabled = running == null,
            onPick = onPickFolder,
            onRemove = onRemoveFolder,
        )
        Place(
            icon = R.drawable.ic_drive_file_move,
            title = stringResource(R.string.auto_backup_copy),
            chosen = settings.copy?.let { uri ->
                val where = when (view.copyKind) {
                    PlaceKind.Phone -> stringResource(R.string.auto_backup_on_phone)
                    PlaceKind.Drive -> stringResource(R.string.auto_backup_google_drive)
                    else -> view.copyApp
                }
                listOfNotNull(where, view.copyName ?: shortName(uri)).joinToString(" · ")
            },
            warning = if (view.copyKind == PlaceKind.Phone) stringResource(R.string.auto_backup_copy_on_phone) else null,
            hint = stringResource(R.string.auto_backup_copy_hint),
            pick = stringResource(R.string.auto_backup_pick_copy),
            enabled = running == null,
            onPick = onPickCopy,
            onRemove = onRemoveCopy,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(settings.on, enabled = settings.hasPlace, role = Role.Switch, onValueChange = onOn)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.auto_backup_switch), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = settings.on, onCheckedChange = null, enabled = settings.hasPlace)
        }

        Text(
            settings.lastAt?.let {
                stringResource(R.string.auto_backup_last, formatDate(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()))
            } ?: stringResource(R.string.auto_backup_never),
            style = MaterialTheme.typography.bodyMedium,
        )

        settings.error?.let { error ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Garage.Loss.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Garage.Loss.copy(alpha = 0.45f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Garage.Loss, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(R.string.auto_backup_failed), style = MaterialTheme.typography.titleSmall)
                        error.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground) }
                    }
                }
            }
        }

        if (running != null) {
            Text(
                when {
                    running.copying -> stringResource(R.string.auto_backup_copying)
                    running.total == 0 -> stringResource(R.string.data_preparing_backup)
                    else -> stringResource(R.string.data_copying_photos, running.done, running.total)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (running.total == 0 || running.copying) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { running.done.toFloat() / running.total }, modifier = Modifier.fillMaxWidth())
            }
        }

        OutlinedButton(
            onClick = onNow,
            enabled = settings.hasPlace && running == null && !busy,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconLabel(R.drawable.ic_sync, stringResource(R.string.auto_backup_now))
        }
    }
}

/** A place: its name or "Not chosen", a [warning] about it, what it is for, and pick / change / remove. */
@Composable
private fun Place(
    @DrawableRes icon: Int,
    title: String,
    chosen: String?,
    hint: String,
    warning: String? = null,
    pick: String,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (chosen != null) Garage.Brand else Garage.MutedForeground,
            modifier = Modifier.padding(top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                chosen ?: stringResource(R.string.auto_backup_not_chosen),
                style = MaterialTheme.typography.bodyMedium,
                color = if (chosen != null) MaterialTheme.colorScheme.onSurface else Garage.MutedForeground,
            )
            warning?.let {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Garage.Loss, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Garage.Loss, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground, modifier = Modifier.padding(top = 2.dp))
            Row {
                TextButton(onClick = onPick, enabled = enabled) {
                    Text(if (chosen == null) pick else stringResource(R.string.auto_backup_change))
                }
                if (chosen != null) {
                    TextButton(onClick = onRemove, enabled = enabled) { Text(stringResource(R.string.auto_backup_remove)) }
                }
            }
        }
    }
}

/** What the address says when the place's app doesn't: "primary:Documents/Diecast Tracker" → "Documents/Diecast Tracker". */
private fun shortName(uri: String): String =
    uri.toUri().lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: uri
