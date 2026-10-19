package io.github.caropeboka.diecasttracker.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.data.places.PlaceValue
import io.github.caropeboka.diecasttracker.ui.components.SuggestInput
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.launch

/**
 * A place name with an optional map point (web: `location-field.tsx`). The button opens the map picker; a point
 * chosen there is remembered for three hours, so the next diecast or sighting starts at the same store.
 */
@Composable
fun LocationField(
    value: PlaceValue,
    onChange: (PlaceValue) -> Unit,
    suggestions: List<String>,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
) {
    val container = LocalContext.current.container
    var picking by rememberSaveable { mutableStateOf(false) }

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SuggestInput(
                value.name,
                { onChange(value.copy(name = it)) },
                suggestions,
                label,
                modifier = Modifier.weight(1f),
                placeholder = placeholder,
                error = error,
            )
            FilledTonalIconButton(
                onClick = { picking = true },
                shape = MaterialTheme.shapes.medium,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Garage.Brand.copy(alpha = 0.12f),
                    contentColor = Garage.Brand,
                ),
                modifier = Modifier.padding(top = 8.dp).size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = "Pilih titik di peta")
            }
        }

        if (value.hasPoint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    coordinates(value.lat!!, value.lng!!, value.accuracy),
                    style = MaterialTheme.typography.labelSmall,
                    color = Garage.MutedForeground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { picking = true }) { Text("ubah titik", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { onChange(value.withoutPoint()) }) {
                    Text("hapus titik", style = MaterialTheme.typography.labelSmall, color = Garage.MutedForeground)
                }
            }
        }
    }

    if (picking) {
        LocationPicker(
            initial = value,
            onDismiss = { picking = false },
            onPick = { place ->
                // Keep what was typed if the picker has no name.
                val picked = place.copy(name = place.name.ifBlank { value.name })
                onChange(picked)
                container.scope.launch { container.prefs.rememberPlace(picked) }
            },
        )
    }
}
