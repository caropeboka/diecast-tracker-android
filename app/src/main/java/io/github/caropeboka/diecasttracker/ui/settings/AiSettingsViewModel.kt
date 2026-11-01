package io.github.caropeboka.diecasttracker.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import io.github.caropeboka.diecasttracker.data.ai.AiProvider
import io.github.caropeboka.diecasttracker.data.ai.AiSettings
import io.github.caropeboka.diecasttracker.data.ai.AiSetup
import io.github.caropeboka.diecasttracker.data.ai.GeminiClient
import io.github.caropeboka.diecasttracker.data.ai.MODEL_NAME
import io.github.caropeboka.diecasttracker.data.ai.OPEN_MODEL_NAME
import io.github.caropeboka.diecasttracker.data.ai.OpenAiClient
import io.github.caropeboka.diecasttracker.data.ai.ProviderChange
import io.github.caropeboka.diecasttracker.data.ai.ProviderSettings
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface KeyTest {
    data object Idle : KeyTest

    data object Testing : KeyTest

    data class Ok(val models: List<String>) : KeyTest

    data class Failed(val message: String) : KeyTest
}

/** One provider's form; keys typed here are not kept across process death on purpose. */
class ProviderDraft(val provider: AiProvider) {
    var keyInput by mutableStateOf("")
    var model by mutableStateOf(provider.defaultModel)
    var on by mutableStateOf(true)
    var baseUrl by mutableStateOf("")
    var name by mutableStateOf("")
    var test by mutableStateOf<KeyTest>(KeyTest.Idle)
    var modelError by mutableStateOf<String?>(null)
    var urlError by mutableStateOf<String?>(null)
}

/** Settings → AI Scan of the web app, with every provider the app can fall back on, in the order they're tried. */
class AiSettingsViewModel(
    saved: SavedStateHandle,
    private val settings: AiSettings,
    private val gemini: GeminiClient,
    private val openAi: OpenAiClient,
    private val messages: Messages,
) : ViewModel() {
    val fromScan = saved.toRoute<AiSettingsRoute>().fromScan

    val current: StateFlow<AiSetup?> = settings.setup.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Providers whose quota ran out, until when. */
    val resting: StateFlow<Map<AiProvider, Long>> = settings.resting.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The order being edited, first tried first. */
    val order = mutableStateListOf<AiProvider>()
    val drafts: Map<AiProvider, ProviderDraft> = AiProvider.entries.associateWith(::ProviderDraft)

    /** The card that is open, if any. */
    var expanded by mutableStateOf<AiProvider?>(null)
        private set
    var saving by mutableStateOf(false)
        private set

    private val _closed = Channel<Unit>(Channel.CONFLATED)
    val closed: Flow<Unit> = _closed.receiveAsFlow()

    init {
        viewModelScope.launch {
            val setup = settings.setup.first()
            order.addAll(setup.providers.map { it.provider })
            setup.providers.forEach { saved ->
                drafts.getValue(saved.provider).apply {
                    model = saved.model
                    on = saved.on
                    baseUrl = saved.baseUrl.orEmpty()
                    name = saved.name.orEmpty()
                }
            }
            if (!setup.isEnabled) expanded = AiProvider.Gemini
        }
    }

    fun toggle(provider: AiProvider) {
        expanded = if (expanded == provider) null else provider
    }

    fun move(provider: AiProvider, by: Int) {
        val from = order.indexOf(provider)
        val to = from + by
        if (from < 0 || to !in order.indices) return
        order.removeAt(from)
        order.add(to, provider)
    }

    fun changeKey(provider: AiProvider, value: String) = drafts.getValue(provider).run {
        keyInput = value.trim()
        if (test !is KeyTest.Testing) test = KeyTest.Idle
    }

    fun changeModel(provider: AiProvider, value: String) = drafts.getValue(provider).run {
        model = value.trim()
        modelError = null
    }

    fun changeUrl(value: String) = drafts.getValue(AiProvider.Custom).run {
        baseUrl = value.trim()
        urlError = null
        if (test !is KeyTest.Testing) test = KeyTest.Idle
    }

    fun changeName(value: String) {
        drafts.getValue(AiProvider.Custom).name = value
    }

    fun changeOn(provider: AiProvider, value: Boolean) {
        drafts.getValue(provider).on = value
    }

    /** Lists the models the typed key (or the saved one) can use. */
    fun testKey(provider: AiProvider) {
        val draft = drafts.getValue(provider)
        val settings = typed(provider)
        if (settings.key.isNullOrBlank() && provider != AiProvider.Custom) {
            draft.test = KeyTest.Failed("Isi API key dulu.")
            return
        }
        if (provider == AiProvider.Custom && !validUrl(draft.baseUrl)) {
            draft.test = KeyTest.Failed("Isi alamat server dulu, mis. https://…/v1.")
            return
        }

        draft.test = KeyTest.Testing
        viewModelScope.launch {
            draft.test = try {
                KeyTest.Ok(if (provider == AiProvider.Gemini) gemini.availableModels(settings.key.orEmpty()) else openAi.availableModels(settings))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                KeyTest.Failed(e.message.orEmpty())
            }
        }
    }

    fun save() {
        if (saving || order.isEmpty()) return
        var firstError: AiProvider? = null
        for (provider in order) {
            if (!validate(provider)) firstError = firstError ?: provider
        }
        if (firstError != null) {
            expanded = firstError
            return
        }

        saving = true
        viewModelScope.launch {
            settings.save(
                order.map { provider ->
                    val draft = drafts.getValue(provider)
                    ProviderChange(provider, draft.model, draft.on, draft.keyInput, draft.baseUrl, draft.name)
                },
            )
            messages.show("Pengaturan AI disimpan.")
            _closed.send(Unit)
        }
    }

    fun removeKey(provider: AiProvider) {
        viewModelScope.launch {
            settings.removeKey(provider)
            drafts.getValue(provider).apply {
                keyInput = ""
                test = KeyTest.Idle
            }
            val left = settings.setup.first().isEnabled
            messages.show("API key ${provider.title} dihapus." + if (left) "" else " AI tidak aktif.")
        }
    }

    /** The provider as typed, with the saved key when none is typed. */
    private fun typed(provider: AiProvider): ProviderSettings {
        val draft = drafts.getValue(provider)
        val savedKey = current.value?.get(provider)?.key
        return ProviderSettings(provider, draft.keyInput.ifEmpty { savedKey }, draft.model, draft.on, draft.baseUrl, draft.name)
    }

    /** A provider in use needs a model (and the custom one a server); an unused one may stay half filled. */
    private fun validate(provider: AiProvider): Boolean {
        val draft = drafts.getValue(provider)
        val typed = typed(provider)
        val used = if (provider == AiProvider.Custom) draft.baseUrl.isNotEmpty() || !typed.key.isNullOrBlank() else !typed.key.isNullOrBlank()
        if (!used) return true

        if (provider == AiProvider.Custom && !validUrl(draft.baseUrl)) {
            draft.urlError = "Alamat harus diawali https:// (atau http://), mis. https://integrate.api.nvidia.com/v1."
        }
        val pattern = if (provider == AiProvider.Gemini) MODEL_NAME else OPEN_MODEL_NAME
        draft.modelError = when {
            draft.model.isEmpty() -> "Isi nama model."
            !pattern.matches(draft.model) && provider == AiProvider.Gemini -> "Nama model hanya boleh huruf kecil, angka, titik, dan tanda minus."
            !pattern.matches(draft.model) -> "Nama model tidak boleh berisi spasi."
            else -> null
        }
        return draft.modelError == null && draft.urlError == null
    }

    private fun validUrl(url: String) = url.toHttpUrlOrNull() != null

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { AiSettingsViewModel(createSavedStateHandle(), aiSettings, gemini, openAi, messages) }
            }
        }
    }
}
