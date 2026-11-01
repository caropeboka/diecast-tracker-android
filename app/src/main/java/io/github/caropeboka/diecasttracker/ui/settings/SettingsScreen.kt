package io.github.caropeboka.diecasttracker.ui.settings

import android.app.Activity
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.locale.AppLanguage
import io.github.caropeboka.diecasttracker.data.locale.AppLocales
import io.github.caropeboka.diecasttracker.ui.components.Heading
import io.github.caropeboka.diecasttracker.ui.components.IconLabel
import io.github.caropeboka.diecasttracker.ui.components.LocalSnackbar
import io.github.caropeboka.diecasttracker.ui.components.SectionCard
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.Currency
import io.github.caropeboka.diecasttracker.util.DateStyle
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.groupThousands
import java.time.LocalDate
import kotlin.math.roundToLong

/** Language, currency, date format, and the way to the AI settings, the backups and the tour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onData: () -> Unit,
    onTour: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val currency by viewModel.currency.collectAsStateWithLifecycle(null)
    val dateStyle by viewModel.dateStyle.collectAsStateWithLifecycle(null)
    val rates by viewModel.rates.collectAsStateWithLifecycle(null)
    var language by remember { mutableStateOf(AppLocales.current(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
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
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(stringResource(R.string.settings_language)) {
                AppLanguage.entries.forEach { option ->
                    val label = if (option == AppLanguage.System) {
                        stringResource(R.string.settings_language_system_now, phoneLanguage(context))
                    } else {
                        option.native
                    }
                    Choice(label, selected = language == option) {
                        if (language == option) return@Choice
                        language = option
                        AppLocales.set(context, option)
                        // Android 13+ recreates the screens itself.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (context as? Activity)?.recreate()
                    }
                }
                Note(stringResource(R.string.settings_language_note))
            }

            SectionCard(stringResource(R.string.settings_currency)) {
                Currency.entries.forEach { option ->
                    Choice(stringResource(option.label), selected = currency == option) { viewModel.setCurrency(option) }
                }
                Note(stringResource(R.string.settings_currency_note))
                val shown = rates
                if (shown != null) {
                    Text(
                        stringResource(R.string.settings_rates, rupiah(shown.usd), rupiah(shown.cny)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Note(stringResource(R.string.settings_rates_source, shown.source, formatDate(runCatching { LocalDate.parse(shown.date) }.getOrNull())))
                } else if (currency != Currency.IDR) {
                    Note(stringResource(R.string.settings_rates_none))
                }
                if (currency != Currency.IDR || shown != null) {
                    OutlinedButton(onClick = viewModel::refreshRates, enabled = !viewModel.refreshing, shape = MaterialTheme.shapes.medium) {
                        if (viewModel.refreshing) {
                            CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.settings_rates_refresh))
                        } else {
                            IconLabel(R.drawable.ic_sync, stringResource(R.string.settings_rates_refresh))
                        }
                    }
                }
            }

            SectionCard(stringResource(R.string.settings_date_format)) {
                val today = LocalDate.now()
                DateStyle.entries.forEach { option ->
                    val example = formatDate(today, option)
                    val label = if (option == DateStyle.Language) stringResource(R.string.settings_date_language, example) else example
                    Choice(label, selected = dateStyle == option) { viewModel.setDateStyle(option) }
                }
            }

            SectionCard(stringResource(R.string.settings_more)) {
                Link(R.drawable.ic_sparkles, stringResource(R.string.settings_ai), stringResource(R.string.settings_ai_note), onAiSettings)
                Link(R.drawable.ic_folder_zip, stringResource(R.string.settings_data), stringResource(R.string.settings_data_note), onData)
                Link(R.drawable.ic_route, stringResource(R.string.settings_tour), stringResource(R.string.settings_tour_note)) {
                    viewModel.replayTour()
                    onTour()
                }
            }
        }
    }
}

private val Currency.label
    get() = when (this) {
        Currency.IDR -> R.string.settings_currency_idr
        Currency.USD -> R.string.settings_currency_usd
        Currency.CNY -> R.string.settings_currency_cny
    }

/** "Rp 18.148" whatever currency is chosen: the rates are always against the Rupiah. */
private fun rupiah(value: Double) = "Rp " + groupThousands(value.roundToLong())

/** The phone's own language, named in itself ("English", "Bahasa Indonesia"). */
internal fun phoneLanguage(context: android.content.Context): String {
    val phone = AppLocales.phone(context)
    return phone.getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Garage.MutedForeground)
}

@Composable
private fun Link(@DrawableRes icon: Int, title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Note(note)
        }
    }
}
