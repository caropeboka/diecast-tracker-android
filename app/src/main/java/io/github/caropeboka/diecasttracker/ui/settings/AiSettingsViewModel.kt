package io.github.caropeboka.diecasttracker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.data.ai.AiException
import io.github.caropeboka.diecasttracker.data.ai.AiSettings
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.GeminiSettings
import io.github.caropeboka.diecasttracker.data.ai.MODEL_NAME
import io.github.caropeboka.diecasttracker.ui.AiSettingsRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface KeyTest {
    data object Idle : KeyTest

    data object Testing : KeyTest

    data class Ok(val models: List<String>) : KeyTest

    data class Failed(val message: String) : KeyTest
}

/** Settings → AI Scan of the web app, Gemini only for now. */
class AiSettingsViewModel(
    saved: SavedStateHandle,
    private val settings: AiSettings,
    private val gemini: GeminiClient,
    private val messages: Messages,
) : ViewModel() {
    val fromScan = saved.toRoute<AiSettingsRoute>().fromScan

    val current: StateFlow<GeminiSettings?> = settings.gemini.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** A new key; blank keeps the saved one. Not kept across process death on purpose. */
    var keyInput by mutableStateOf("")
        private set
    var model by mutableStateOf("")
        private set
    var modelError by mutableStateOf<String?>(null)
        private set
    var test by mutableStateOf<KeyTest>(KeyTest.Idle)
        private set
    var saving by mutableStateOf(false)
        private set

    private val _closed = Channel<Unit>(Channel.CONFLATED)
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    init {
        viewModelScope.launch { model = settings.gemini.first().model }
    }

    fun changeKey(value: String) {
        keyInput = value.trim()
        if (test !is KeyTest.Testing) test = KeyTest.Idle
    }

    fun changeModel(value: String) {
        model = value.trim()
        modelError = null
    }

    /** Lists the models the typed key (or the saved one) can use. */
    fun testKey() {
        val key = keyInput.ifEmpty { current.value?.key.orEmpty() }
        if (key.isEmpty()) {
            test = KeyTest.Failed("Isi API key dulu.")
            return
        }

        test = KeyTest.Testing
        viewModelScope.launch {
            test = try {
                KeyTest.Ok(gemini.availableModels(key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                KeyTest.Failed(e.message.orEmpty())
            }
        }
    }

    fun save() {
        if (saving) return
        if (!MODEL_NAME.matches(model)) {
            modelError = if (model.isEmpty()) "Isi nama model." else "Nama model hanya boleh huruf kecil, angka, titik, dan tanda minus."
            return
        }

        saving = true
        viewModelScope.launch {
            settings.save(model, keyInput, removeKey = false)
            messages.show("Pengaturan AI disimpan.")
            _closed.send(Unit)
        }
    }

    fun removeKey() {
        viewModelScope.launch {
            settings.save(current.value?.model ?: model, newKey = null, removeKey = true)
            keyInput = ""
            test = KeyTest.Idle
            messages.show("API key dihapus. Scan AI tidak aktif.")
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { AiSettingsViewModel(createSavedStateHandle(), aiSettings, gemini, messages) }
            }
        }
    }
}
