package io.github.caropeboka.diecasttracker.data.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import java.util.Locale

/** The languages the app speaks; [System] follows the phone (English when the phone's language isn't one of them). */
enum class AppLanguage(val tag: String?, val native: String) {
    System(null, ""),
    English("en", "English"),
    Indonesian("id", "Bahasa Indonesia"),
    Chinese("zh-CN", "简体中文"),
}

/**
 * The app's own language, apart from the phone's: Android 13 and later keep it themselves (and offer it in the
 * phone's settings, `res/xml/locales_config.xml`); older versions get it from [wrap] around every context.
 */
object AppLocales {
    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales?.toLanguageTags() }.getOrNull()
        } else {
            store(context).getString(KEY, null)
        }
        return of(tag)
    }

    /** Switches the language; Android 13+ recreates the screens itself, older versions need [android.app.Activity.recreate]. */
    fun set(context: Context, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            store(context).edit { if (language.tag == null) remove(KEY) else putString(KEY, language.tag) }
        }
    }

    /** Android 12 and older: [base] in the chosen language (the activity's and the application's). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = store(base).getString(KEY, null) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }

    /** The phone's own language. On Android 13+ even the system resources carry the app's choice, so ask the system. */
    fun phone(context: Context): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0) ?: Locale.getDefault()
        } else {
            Resources.getSystem().configuration.locales[0]
        }

    /** The language the screens are in now: the chosen one, or the phone's when it's one the app has. */
    fun shown(context: Context): AppLanguage {
        current(context).takeIf { it != AppLanguage.System }?.let { return it }
        val phone = context.resources.configuration.locales[0]
        return when (phone.language) {
            "in", "id" -> AppLanguage.Indonesian
            "zh" -> AppLanguage.Chinese
            else -> AppLanguage.English
        }
    }

    private fun of(tags: String?): AppLanguage {
        val first = tags?.substringBefore(',')?.takeIf { it.isNotBlank() } ?: return AppLanguage.System
        val language = Locale.forLanguageTag(first).language
        return when {
            language == "in" || language == "id" -> AppLanguage.Indonesian
            language == "zh" -> AppLanguage.Chinese
            language == "en" -> AppLanguage.English
            else -> AppLanguage.System
        }
    }

    private fun store(context: Context) = context.getSharedPreferences("locale", Context.MODE_PRIVATE)

    private const val KEY = "app_language"
}

/**
 * Texts for code outside the screens (AI errors, price check reasons, import problems), in the app's language.
 * Screens use `stringResource` instead.
 */
class Texts private constructor(private val context: Context, private val fixed: Boolean) {
    constructor(context: Context) : this(context, fixed = false)

    operator fun invoke(@StringRes id: Int, vararg args: Any): String = resources().getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = resources().getQuantityString(id, count, *args)

    /** The same texts in each of the app's languages: to recognise something the app wrote in another one. */
    fun everyLanguage(): List<Texts> = AppLanguage.entries.mapNotNull { it.tag }.map { tag ->
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        Texts(context.createConfigurationContext(configuration), fixed = true)
    }

    // Android 13+ applies the app's language to the application context too; before that the choice may have
    // changed since the process started.
    private fun resources() =
        if (fixed || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.resources else AppLocales.wrap(context).resources
}
