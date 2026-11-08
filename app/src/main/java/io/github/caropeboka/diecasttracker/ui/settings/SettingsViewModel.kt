package io.github.caropeboka.diecasttracker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.AppContainer
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.util.Currency
import io.github.caropeboka.diecasttracker.util.DateStyle
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs

    val currency = prefs.currency
    val dateStyle = prefs.dateStyle
    val rates = prefs.rates

    /** For the summaries on the settings page. */
    val aiSetup = container.aiSettings.setup
    val backupTimes = prefs.backupTimes

    var refreshing by mutableStateOf(false)
        private set

    fun setCurrency(currency: Currency) {
        viewModelScope.launch { prefs.setCurrency(currency) }
    }

    fun setDateStyle(style: DateStyle) {
        viewModelScope.launch { prefs.setDateStyle(style) }
    }

    fun refreshRates() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            val ok = container.refreshRates(force = true)
            container.messages.show(container.texts(if (ok) R.string.settings_rates_updated else R.string.settings_rates_failed))
            refreshing = false
        }
    }

    fun replayTour() {
        viewModelScope.launch { prefs.setTourDone(false) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                SettingsViewModel(app.container)
            }
        }
    }
}
