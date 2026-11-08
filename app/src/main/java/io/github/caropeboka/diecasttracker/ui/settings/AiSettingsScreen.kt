package io.github.caropeboka.diecasttracker.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import io.github.caropeboka.diecasttracker.ui.components.rememberTexts
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
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.closed.collect { onClose() } }
    // Keys typed but not saved would go without a word.
    val leave = { if (viewModel.isDirty) confirmLeave = true else onClose() }
    BackHandler(enabled = viewModel.isDirty) { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.settings_ai)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
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
                    Text(stringResource(R.string.save))
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
                            stringResource(R.string.ai_scan_off_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.ai_settings_intro),
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
            title = { Text(stringResource(R.string.ai_remove_key_title, provider.title)) },
            text = { Text(stringResource(R.string.ai_remove_key_text, provider.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    viewModel.removeKey(provider)
                }) { Text(stringResource(R.string.delete), color = Garage.Loss) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
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
                    viewModel.save()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onClose()
                }) { Text(stringResource(R.string.discard), color = Garage.Loss) }
            },
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
    val texts = rememberTexts()
    var showKey by rememberSaveable(provider) { mutableStateOf(false) }
    val custom = provider == AiProvider.Custom
    val configured = when {
        custom -> !saved.baseUrl.isNullOrBlank()
        provider == AiProvider.Cloudflare -> saved.keyHint != null && !saved.account.isNullOrBlank()
        !provider.needsKey -> true
        else -> saved.keyHint != null
    }
    val active = configured && draft.on
    val status = when {
        !configured && draft.keyInput.isNotEmpty() -> stringResource(R.string.ai_status_unsaved)
        !configured -> stringResource(R.string.ai_status_not_set)
        !draft.on -> stringResource(R.string.ai_status_off)
        restUntil > System.currentTimeMillis() -> stringResource(R.string.ai_status_resting, restEnd(texts, restUntil))
        else -> stringResource(R.string.ai_status_on, (if (custom) saved.label else if (!provider.needsKey) stringResource(R.string.ai_no_key_needed) else saved.keyHint).orEmpty())
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
                    Icon(painterResource(R.drawable.ic_arrow_upward), contentDescription = stringResource(R.string.ai_move_up, provider.title), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { viewModel.move(provider, 1) }, enabled = index < count - 1) {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = stringResource(R.string.ai_move_down, provider.title), modifier = Modifier.size(20.dp))
                }
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(if (expanded) R.string.close else R.string.ai_open),
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
                    Text(stringResource(note(provider)), style = MaterialTheme.typography.bodyMedium, color = Garage.MutedForeground)

                    if (configured || draft.keyInput.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ai_use, provider.title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = draft.on, onCheckedChange = { viewModel.changeOn(provider, it) })
                        }
                    }

                    if (custom) {
                        TextInput(draft.name, viewModel::changeName, stringResource(R.string.ai_custom_name), placeholder = stringResource(R.string.ai_custom_name_placeholder))
                        TextInput(
                            draft.baseUrl, viewModel::changeUrl, stringResource(R.string.ai_server_address),
                            placeholder = "https://…/v1", error = draft.urlError,
                            keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None,
                        )
                    }

                    if (provider == AiProvider.Cloudflare) {
                        TextInput(
                            draft.account, { viewModel.changeAccount(it) }, stringResource(R.string.ai_cloudflare_account),
                            placeholder = "0123456789abcdef…", error = draft.accountError, capitalization = KeyboardCapitalization.None,
                        )
                    }

                    val savedKey = saved.keyHint
                    if (provider.needsKey && savedKey != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Neutral: a saved key is not a tested one.
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
                            Text(
                                stringResource(R.string.ai_saved_key, savedKey),
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
                                Text(stringResource(R.string.ai_remove_key))
                            }
                        }
                    }

                    if (provider.needsKey) OutlinedTextField(
                        value = draft.keyInput,
                        onValueChange = { viewModel.changeKey(provider, it) },
                        label = { Text(stringResource(if (savedKey != null) R.string.ai_replace_key else if (custom) R.string.ai_key_optional else R.string.ai_key)) },
                        placeholder = { Text(keyPlaceholder(provider)) },
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val label = stringResource(if (custom || !provider.needsKey) R.string.ai_test_server else R.string.ai_test_key)
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
                                Text(stringResource(R.string.ai_make_key, if (provider == AiProvider.Gemini) "AI Studio" else provider.title))
                            }
                        }
                    }

                    when (val test = draft.test) {
                        is KeyTest.Ok -> Text(
                            when {
                                test.models.isEmpty() -> stringResource(R.string.ai_test_no_vision)
                                provider !in VISION_LISTED -> pluralStringResource(R.plurals.ai_test_models_pick, test.models.size, test.models.size)
                                else -> pluralStringResource(R.plurals.ai_test_models_vision, test.models.size, test.models.size)
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
                            label = stringResource(R.string.ai_model),
                        )
                    } else {
                        TextInput(
                            draft.model, { viewModel.changeModel(provider, it) }, stringResource(R.string.ai_model),
                            placeholder = provider.defaultModel.ifEmpty { stringResource(R.string.ai_model_placeholder) }, error = draft.modelError,
                            capitalization = KeyboardCapitalization.None,
                        )
                    }
                }
            }
        }
    }
}

/** Whose model lists say which models read photos; the others' are picked by name, so the collector checks. */
private val VISION_LISTED = setOf(AiProvider.Gemini, AiProvider.Mistral, AiProvider.OpenRouter)

@StringRes
private fun note(provider: AiProvider) = when (provider) {
    AiProvider.Gemini -> R.string.ai_note_gemini
    AiProvider.Groq -> R.string.ai_note_groq
    AiProvider.Mistral -> R.string.ai_note_mistral
    AiProvider.OpenRouter -> R.string.ai_note_openrouter
    AiProvider.Cloudflare -> R.string.ai_note_cloudflare
    AiProvider.Nvidia -> R.string.ai_note_nvidia
    AiProvider.Zai -> R.string.ai_note_zai
    AiProvider.Ovh -> R.string.ai_note_ovh
    AiProvider.Custom -> R.string.ai_note_custom
}

@Composable
private fun keyPlaceholder(provider: AiProvider) = when (provider) {
    AiProvider.Gemini -> "AIza…"
    AiProvider.Groq -> "gsk_…"
    AiProvider.OpenRouter -> "sk-or-…"
    AiProvider.Nvidia -> "nvapi-…"
    AiProvider.Custom -> stringResource(R.string.ai_custom_key_placeholder)
    AiProvider.Mistral, AiProvider.Cloudflare, AiProvider.Zai, AiProvider.Ovh -> ""
}
