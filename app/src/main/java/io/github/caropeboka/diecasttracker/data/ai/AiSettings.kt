package io.github.caropeboka.diecasttracker.data.ai

import android.content.Context
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

data class GeminiSettings(val key: String? = null, val model: String = DEFAULT_GEMINI_MODEL) {
    val isEnabled get() = !key.isNullOrBlank()

    /** "••••9876": the screen never shows the whole key. */
    val keyHint get() = key?.let { "••••" + it.takeLast(4) }
}

/** Where [GeminiClient] keeps the fallback model list between scans. */
interface ModelListCache {
    suspend fun get(key: String): List<String>?

    suspend fun put(key: String, models: List<String>)
}

// Its own file, so cloud backup can leave the API key out (res/xml/data_extraction_rules.xml).
private val Context.aiStore by preferencesDataStore("ai")

/** Gemini key and model (web: Settings → AI Scan), plus the cached fallback models. */
class AiSettings(context: Context, private val clock: () -> Long = System::currentTimeMillis) : ModelListCache {
    private val store = context.aiStore

    val gemini: Flow<GeminiSettings> = store.data.map {
        GeminiSettings(key = it[GEMINI_KEY], model = it[GEMINI_MODEL] ?: DEFAULT_GEMINI_MODEL)
    }

    /** A blank [newKey] keeps the saved key, like the web form. */
    suspend fun save(model: String, newKey: String?, removeKey: Boolean) {
        store.edit {
            it[GEMINI_MODEL] = model.trim()
            when {
                removeKey -> it.remove(GEMINI_KEY)
                !newKey.isNullOrBlank() -> it[GEMINI_KEY] = newKey.trim()
            }
        }
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

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val FALLBACKS = stringPreferencesKey("gemini_fallbacks")
        val FALLBACKS_KEY = stringPreferencesKey("gemini_fallbacks_key")
        val FALLBACKS_AT = longPreferencesKey("gemini_fallbacks_at")
        const val FALLBACKS_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
