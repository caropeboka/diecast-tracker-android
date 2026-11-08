package io.github.caropeboka.diecasttracker.ui.settings

import android.app.Activity
import android.content.Context
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Where the app's code lives (README, releases, issues). */
private const val SOURCE_URL = "https://github.com/caropeboka/diecast-tracker-android"

/**
 * Every setting in one place: General (language, currency, date format), the AI, the backups, the tour and what
 * this app is. Each category says what it is set to now; tapping one opens its page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGeneral: () -> Unit,
    onAiSettings: () -> Unit,
    onData: () -> Unit,
    onTour: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val uris = LocalUriHandler.current
    val currency by viewModel.currency.collectAsStateWithLifecycle(null)
    val dateStyle by viewModel.dateStyle.collectAsStateWithLifecycle(null)
    val ai by viewModel.aiSetup.collectAsStateWithLifecycle(null)
    val backups by viewModel.backupTimes.collectAsStateWithLifecycle(null)

    SettingsScaffold(stringResource(R.string.settings_title), onBack) {
        SectionCard(null) {
            val language = AppLocales.current(context)
            val general = listOfNotNull(
                if (language == AppLanguage.System) phoneLanguage(context) else language.native,
                currency?.code,
                dateStyle?.let { formatDate(LocalDate.now(), it) },
            ).joinToString(" · ")
            Category(R.drawable.ic_settings, stringResource(R.string.settings_general), general, onClick = onGeneral)
            Divider()

            val chain = ai?.chain
            val aiSummary = when {
                chain == null -> ""
                chain.isEmpty() -> stringResource(R.string.settings_ai_none)
                else -> stringResource(R.string.settings_ai_in_use, chain.joinToString(", ") { it.label })
            }
            Category(R.drawable.ic_sparkles, stringResource(R.string.settings_section_ai), aiSummary, onClick = onAiSettings)
            Divider()

            val last = backups?.last
            val backupSummary = when {
                backups == null -> ""
                last == null -> stringResource(R.string.settings_backup_never)
                else -> stringResource(R.string.settings_backup_last, formatDate(Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDate()))
            }
            Category(R.drawable.ic_folder_zip, stringResource(R.string.settings_data), backupSummary, onClick = onData)
        }

        SectionCard(null) {
            Category(R.drawable.ic_route, stringResource(R.string.settings_tour), stringResource(R.string.settings_tour_note), chevron = false) {
                viewModel.replayTour()
                onTour()
            }
            Divider()
            Category(
                R.drawable.ic_info,
                stringResource(R.string.settings_about),
                stringResource(R.string.settings_about_summary, appVersion(context)),
                trailing = R.drawable.ic_open_in_new,
            ) { runCatching { uris.openUri(SOURCE_URL) } }
        }
    }
}

/** Language, currency (with the day's rates) and date format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val context = LocalContext.current
    val currency by viewModel.currency.collectAsStateWithLifecycle(null)
    val dateStyle by viewModel.dateStyle.collectAsStateWithLifecycle(null)
    val rates by viewModel.rates.collectAsStateWithLifecycle(null)
    var language by remember { mutableStateOf(AppLocales.current(context)) }

    SettingsScaffold(stringResource(R.string.settings_general), onBack) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Heading(title) },
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
            content()
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
internal fun phoneLanguage(context: Context): String {
    val phone = AppLocales.phone(context)
    return phone.getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) }
}

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

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
private fun Divider() = HorizontalDivider(color = Garage.Border)

/** One category: its icon, name and what it is set to now; a chevron when it opens a page of its own. */
@Composable
private fun Category(
    @DrawableRes icon: Int,
    title: String,
    summary: String,
    chevron: Boolean = true,
    @DrawableRes trailing: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Garage.Brand, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) Note(summary)
        }
        when {
            trailing != null -> Icon(painterResource(trailing), contentDescription = null, tint = Garage.MutedForeground, modifier = Modifier.size(18.dp))
            chevron -> Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = Garage.MutedForeground,
                modifier = Modifier.size(20.dp).rotate(-90f),
            )
        }
    }
}
