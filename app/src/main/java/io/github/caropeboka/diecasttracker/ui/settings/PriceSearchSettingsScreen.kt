package io.github.caropeboka.diecasttracker.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.ai.AiSettings
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.price.SerpApiAccount
import io.github.caropeboka.diecasttracker.data.price.ShoppingSearch
import io.github.caropeboka.diecasttracker.data.price.ShoppingSearchException
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LogoTile
import io.github.caropeboka.diecasttracker.ui.components.Logos
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.components.fieldColors
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "••••1234": a key is never shown whole. */
internal fun keyHint(key: String) = "••••" + key.takeLast(4)

/** How a key test went. */
sealed interface SerpApiTest {
    data object Idle : SerpApiTest

    data object Testing : SerpApiTest

    data class Ok(val account: SerpApiAccount) : SerpApiTest

    data class Failed(val message: String) : SerpApiTest
}

/** The SerpApi key for the automatic price check: save, test (free: the account API), remove. */
class PriceSearchSettingsViewModel(
    private val settings: AiSettings,
    private val shopping: ShoppingSearch,
    private val messages: Messages,
    private val texts: Texts,
) : ViewModel() {
    val savedKey: StateFlow<String?> = settings.serpApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var keyInput by mutableStateOf("")
        private set
    var test by mutableStateOf<SerpApiTest>(SerpApiTest.Idle)
        private set

    /** A key typed but not saved. */
    val isDirty get() = keyInput.isNotBlank()

    fun changeKey(text: String) {
        keyInput = text
        if (test !is SerpApiTest.Testing) test = SerpApiTest.Idle
    }

    /** The typed key, or the saved one when nothing is typed. */
    fun test() {
        if (test is SerpApiTest.Testing) return
        test = SerpApiTest.Testing
        viewModelScope.launch {
            test = try {
                SerpApiTest.Ok(shopping.account(keyInput.ifBlank { null }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ShoppingSearchException) {
                SerpApiTest.Failed(e.message.orEmpty())
            }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        val key = keyInput.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            settings.setSerpApiKey(key)
            keyInput = ""
            messages.show(texts(R.string.serpapi_saved))
            onSaved()
        }
    }

    fun remove() {
        viewModelScope.launch {
            settings.setSerpApiKey(null)
            test = SerpApiTest.Idle
            messages.show(texts(R.string.serpapi_removed))
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { PriceSearchSettingsViewModel(aiSettings, shopping, messages, texts) }
            }
        }
    }
}

/** "Cek harga otomatis": what it does and costs, the SerpApi key, and what is sent. */
@Composable
fun PriceSearchSettingsScreen(
    onBack: () -> Unit,
    viewModel: PriceSearchSettingsViewModel = viewModel(factory = PriceSearchSettingsViewModel.Factory),
) {
    val uris = LocalUriHandler.current
    val saved by viewModel.savedKey.collectAsStateWithLifecycle()
    var showKey by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    // A key typed but not saved would go without a word, as in the AI settings.
    val leave = { if (viewModel.isDirty) confirmLeave = true else onBack() }
    BackHandler(enabled = viewModel.isDirty) { confirmLeave = true }

    SettingsScaffold(stringResource(R.string.settings_price_auto), leave) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LogoTile(Logos.serpApi, 40.dp, Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.serpapi_intro), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.serpapi_plan), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)

        SectionCard(stringResource(R.string.serpapi_key)) {
            val savedKey = saved
            if (savedKey != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Neutral: a saved key is not a tested one.
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(R.string.ai_saved_key, keyHint(savedKey)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    TextButton(
                        onClick = { confirmRemove = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Garage.Loss),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(stringResource(R.string.ai_remove_key)) }
                }
            }

            OutlinedTextField(
                value = viewModel.keyInput,
                onValueChange = viewModel::changeKey,
                label = { Text(stringResource(if (savedKey != null) R.string.ai_replace_key else R.string.ai_key)) },
                supportingText = if (savedKey != null) {
                    { Text(stringResource(R.string.ai_keep_key_hint)) }
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
                            contentDescription = stringResource(if (showKey) R.string.ai_hide_key else R.string.ai_show_key),
                            tint = if (showKey) Garage.Brand else Garage.MutedForeground,
                        )
                    }
                },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val testing = viewModel.test == SerpApiTest.Testing
                OutlinedButton(
                    onClick = viewModel::test,
                    enabled = !testing && (savedKey != null || viewModel.keyInput.isNotBlank()),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.ai_test_key))
                    } else {
                        IconLabel(R.drawable.ic_check, stringResource(R.string.ai_test_key))
                    }
                }
                TextButton(onClick = { runCatching { uris.openUri(ShoppingSearch.KEY_URL) } }, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.ai_make_key, "serpapi.com"))
                }
            }

            when (val test = viewModel.test) {
                is SerpApiTest.Ok -> {
                    val left = test.account.left
                    Text(
                        if (left != null) {
                            pluralStringResource(R.plurals.serpapi_test_left, left, test.account.plan ?: "SerpApi", left)
                        } else {
                            stringResource(R.string.serpapi_test_ok)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Garage.Gain,
                    )
                }
                is SerpApiTest.Failed -> Text(test.message, style = MaterialTheme.typography.bodySmall, color = Garage.Loss)
                SerpApiTest.Idle, SerpApiTest.Testing -> Unit
            }

            Button(
                onClick = { viewModel.save() },
                enabled = viewModel.keyInput.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.save)) }
        }

        Text(stringResource(R.string.serpapi_privacy), style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.ai_remove_key_title, "SerpApi")) },
            text = { Text(stringResource(R.string.serpapi_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.remove()
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.edit_discard_title)) },
            text = { Text(stringResource(R.string.ai_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    viewModel.save(onSaved = onBack)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text(stringResource(R.string.discard), color = Garage.Loss) }
            },
        )
    }
}
