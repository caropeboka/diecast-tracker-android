package io.github.caropeboka.diecasttracker.data.ai

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

/** The web app's default (`config/services.php`). */
const val DEFAULT_GEMINI_MODEL = "gemini-3.8-flash"

/** Model names as Google writes them; also what the web's settings form accepts. */
val MODEL_NAME = Regex("^[a-z0-9.\\-]+$")

/** Model ids of the OpenAI-style services: `qwen/qwen3.8-27b`, `google/gemma-4-31b-it:free`, `@cf/meta/…`. */
val OPEN_MODEL_NAME = Regex("^[A-Za-z0-9@._:/\\-]+$")

/**
 * The services the app can read photos with. Gemini has its own API; the others speak OpenAI's chat completions.
 * [maxImages] and [maxRequestBytes]: what one request may carry, when the service has a limit.
 */
enum class AiProvider(
    val id: String,
    /** Short, for the progress log; [title] for the settings. */
    val label: String,
    val title: String,
    val baseUrl: String?,
    val defaultModel: String,
    val keyUrl: String?,
    val maxImages: Int? = null,
    val maxRequestBytes: Long? = null,
) {
    Gemini("gemini", "Gemini", "Google Gemini", null, DEFAULT_GEMINI_MODEL, "https://aistudio.google.com/apikey"),

    // Its vision model takes 3 images, and base64 requests up to 4 MB.
    Groq("groq", "Groq", "Groq", "https://api.groq.com/openai/v1", "qwen/qwen3.8-27b", "https://console.groq.com/keys", maxImages = 3, maxRequestBytes = 4L * 1024 * 1024),
    Mistral("mistral", "Mistral", "Mistral", "https://api.mistral.ai/v1", "mistral-small-latest", "https://console.mistral.ai/api-keys"),

    // The free router picks a free model that can do what the request needs (images, JSON), whichever are up.
    OpenRouter("openrouter", "OpenRouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", "https://openrouter.ai/keys"),
    Custom("custom", "Server lain", "OpenAI-compatible", null, "", null),
}

/** One provider as set up: its key, model and whether it may be used. [baseUrl] and [name] are the custom one's. */
data class ProviderSettings(
    val provider: AiProvider,
    val key: String? = null,
    val model: String = provider.defaultModel,
    val on: Boolean = true,
    val baseUrl: String? = null,
    val name: String? = null,
) {
    /** A custom server may need no key (one on the home network, say). */
    val isReady get() = on && model.isNotBlank() && when (provider) {
        AiProvider.Custom -> !baseUrl.isNullOrBlank()
        else -> !key.isNullOrBlank()
    }

    /** "••••9876": the screen never shows the whole key. */
    val keyHint get() = key?.takeIf { it.isNotBlank() }?.let { "••••" + it.takeLast(4) }

    val label: String get() = if (provider == AiProvider.Custom) name?.trim()?.ifEmpty { null } ?: host() ?: provider.label else provider.label

    /** Where its chat completions live, without a trailing slash. */
    val endpoint: String? get() = (provider.baseUrl ?: baseUrl)?.trim()?.trimEnd('/')

    private fun host() = baseUrl?.substringAfter("://")?.substringBefore('/')?.ifEmpty { null }
}

/** Every provider in the order they are tried. */
data class AiSetup(val providers: List<ProviderSettings>) {
    /** The ones that can be used, in order. */
    val chain get() = providers.filter { it.isReady }

    val isEnabled get() = chain.isNotEmpty()

    operator fun get(provider: AiProvider) = providers.first { it.provider == provider }

    companion object {
        /** All providers unset, Gemini first. */
        val EMPTY = AiSetup(AiProvider.entries.map { ProviderSettings(it) })

        /** Only Gemini, with [key]: how the app worked before the others came. */
        fun gemini(key: String?, model: String = DEFAULT_GEMINI_MODEL) = AiSetup(
            AiProvider.entries.map { if (it == AiProvider.Gemini) ProviderSettings(it, key, model) else ProviderSettings(it) },
        )
    }
}

/** Where [GeminiClient] keeps the fallback model list between scans. */
interface ModelListCache {
    suspend fun get(key: String): List<String>?

    suspend fun put(key: String, models: List<String>)
}

/** Providers set aside after running out of quota, until it is back (epoch millis; 0 = not resting). */
interface ProviderRests {
    suspend fun until(provider: AiProvider): Long

    suspend fun rest(provider: AiProvider, until: Long)

    suspend fun clear(provider: AiProvider)
}

// Its own file, so cloud backup can leave the API keys out (res/xml/data_extraction_rules.xml).
private val Context.aiStore by preferencesDataStore("ai")

/** The providers' keys, models and order (web: Settings → AI Scan), the cached Gemini fallbacks and the rests. */
class AiSettings(context: Context, private val clock: () -> Long = System::currentTimeMillis) : ModelListCache, ProviderRests {
    private val store = context.aiStore

    val setup: Flow<AiSetup> = store.data.map { prefs ->
        val order = prefs[ORDER]?.split(',')?.mapNotNull { id -> AiProvider.entries.find { it.id == id } }.orEmpty()
        (order + AiProvider.entries).distinct().map { provider -> prefs.read(provider) }.let(::AiSetup)
    }

    /** Until when each provider rests (0 = not resting), for the settings screen. */
    val resting: Flow<Map<AiProvider, Long>> = store.data.map { prefs -> AiProvider.entries.associateWith { prefs[rest(it)] ?: 0 } }

    /** Saves every provider and their order; a blank new key keeps the saved one, like the web form. */
    suspend fun save(changes: List<ProviderChange>) {
        store.edit { prefs ->
            prefs[ORDER] = changes.joinToString(",") { it.provider.id }
            for (change in changes) {
                val provider = change.provider
                prefs[model(provider)] = change.model.trim()
                prefs[on(provider)] = change.on
                if (!change.newKey.isNullOrBlank()) prefs[key(provider)] = change.newKey.trim()
                if (provider == AiProvider.Custom) {
                    prefs.put(CUSTOM_URL, change.baseUrl)
                    prefs.put(CUSTOM_NAME, change.name)
                }
            }
        }
    }

    suspend fun removeKey(provider: AiProvider) {
        store.edit { it.remove(key(provider)) }
    }

    /** The web caches the model list for 12 hours per key; so does this. */
    override suspend fun get(key: String): List<String>? {
        val prefs = store.data.first()
        val fresh = clock() - (prefs[FALLBACKS_AT] ?: 0) < FALLBACKS_TTL_MS
        return prefs[FALLBACKS]
            ?.takeIf { fresh && prefs[FALLBACKS_KEY] == hash(key) }
            ?.split(',')
            ?.filter(String::isNotEmpty)
    }

    override suspend fun put(key: String, models: List<String>) {
        store.edit {
            it[FALLBACKS] = models.joinToString(",")
            it[FALLBACKS_KEY] = hash(key)
            it[FALLBACKS_AT] = clock()
        }
    }

    override suspend fun until(provider: AiProvider): Long = store.data.first()[rest(provider)] ?: 0

    override suspend fun rest(provider: AiProvider, until: Long) {
        store.edit { it[rest(provider)] = until }
    }

    override suspend fun clear(provider: AiProvider) {
        store.edit { it.remove(rest(provider)) }
    }

    private fun Preferences.read(provider: AiProvider) = ProviderSettings(
        provider = provider,
        key = this[key(provider)],
        model = this[model(provider)] ?: provider.defaultModel,
        on = this[on(provider)] ?: true,
        baseUrl = if (provider == AiProvider.Custom) this[CUSTOM_URL] else null,
        name = if (provider == AiProvider.Custom) this[CUSTOM_NAME] else null,
    )

    private fun MutablePreferences.put(key: Preferences.Key<String>, value: String?) {
        val text = value?.trim()
        if (text.isNullOrEmpty()) remove(key) else this[key] = text
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        // Gemini keeps the names it had before the other providers came, so a saved key stays.
        fun key(provider: AiProvider) = stringPreferencesKey("${provider.id}_api_key")

        fun model(provider: AiProvider) = stringPreferencesKey("${provider.id}_model")

        fun on(provider: AiProvider) = booleanPreferencesKey("${provider.id}_on")

        fun rest(provider: AiProvider) = longPreferencesKey("${provider.id}_rest_until")

        val ORDER = stringPreferencesKey("provider_order")
        val CUSTOM_URL = stringPreferencesKey("custom_base_url")
        val CUSTOM_NAME = stringPreferencesKey("custom_name")
        val FALLBACKS = stringPreferencesKey("gemini_fallbacks")
        val FALLBACKS_KEY = stringPreferencesKey("gemini_fallbacks_key")
        val FALLBACKS_AT = longPreferencesKey("gemini_fallbacks_at")
        const val FALLBACKS_TTL_MS = 12 * 60 * 60 * 1000L
    }
}

/** What the settings screen saves for one provider; [newKey] blank keeps the saved key. */
data class ProviderChange(
    val provider: AiProvider,
    val model: String,
    val on: Boolean,
    val newKey: String? = null,
    val baseUrl: String? = null,
    val name: String? = null,
)
