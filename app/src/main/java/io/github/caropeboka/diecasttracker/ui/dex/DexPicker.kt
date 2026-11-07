package io.github.caropeboka.diecasttracker.ui.dex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.container
import io.github.caropeboka.diecasttracker.data.dex.DexRef
import io.github.caropeboka.diecasttracker.ui.components.SubHeading
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.delay

/**
 * Searches every list in the Dex for a diecast's entry, for when the automatic match is wrong or missing. [start]
 * is the first search (the diecast's name); [brand]'s lists come first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DexPickerSheet(start: String, brand: String?, year: Int?, onPick: (DexRef) -> Unit, onDismiss: () -> Unit) {
    val dex = LocalContext.current.container.dex
    var query by rememberSaveable { mutableStateOf(start) }
    // Keeps the last results while the next search waits for typing to pause.
    val results by produceState<List<DexRef>?>(null, query) {
        delay(250)
        value = dex.search(query, brand, year)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Garage.Card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SubHeading(stringResource(R.string.dex_pick_title))
            Text(stringResource(R.string.dex_pick_hint), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.dex_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, Modifier.size(20.dp)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            val list = results
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    stringResource(if (query.isBlank()) R.string.dex_pick_empty else R.string.dex_pick_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Garage.MutedForeground,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.row.item.id }) { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DexEntryCard(ref, Modifier.weight(1f))
                            Button(onClick = { onPick(ref) }, shape = MaterialTheme.shapes.medium) { Text(stringResource(R.string.dex_pick_choose)) }
                        }
                    }
                }
            }
        }
    }
}
