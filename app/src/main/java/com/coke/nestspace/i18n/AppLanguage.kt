package com.coke.nestspace.i18n

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale

/** How the host UI picks its language. */
enum class AppLanguageMode(val storageValue: String) {
    SYSTEM("system"),
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun from(value: String?): AppLanguageMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

/**
 * Host UI language switching.
 *
 * The UI uses inline bilingual pairs ([tr]) instead of resource lookups so the
 * same helper works from Compose, ViewModels, services and the container error
 * screens. A Compose snapshot int invalidates every composable that rendered a
 * translated string when the setting changes, so no activity recreation is
 * needed for an in-app switch.
 *
 * SYSTEM follows the app's current resources configuration, which tracks the
 * OS locale (and an OS per-app language choice if the user set one there).
 */
object AppLanguage {

    private const val PREFS = "app_language"
    private const val KEY_MODE = "mode"

    @Volatile private var appContext: Context? = null
    @Volatile private var mode: AppLanguageMode = AppLanguageMode.SYSTEM
    private val revisionState = mutableIntStateOf(0)

    val revision: Int get() = revisionState.intValue

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        mode = AppLanguageMode.from(
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, null)
        )
    }

    fun mode(): AppLanguageMode = mode

    fun setMode(context: Context, value: AppLanguageMode) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, value.storageValue)
            .apply()
        mode = value
        revisionState.intValue++
    }

    fun isChinese(): Boolean {
        // Read the snapshot state so Compose observes the setting.
        revisionState.intValue
        return when (mode) {
            AppLanguageMode.CHINESE -> true
            AppLanguageMode.ENGLISH -> false
            AppLanguageMode.SYSTEM -> systemLanguageIsChinese()
        }
    }

    fun tr(zh: String, en: String): String = if (isChinese()) zh else en

    /** Plural-aware helper: Chinese has no plural; English uses one/many. */
    fun trCount(count: Int, zh: String, enOne: String, enMany: String): String =
        if (isChinese()) zh else if (count == 1) enOne else enMany

    fun modeLabel(value: AppLanguageMode): String = when (value) {
        AppLanguageMode.SYSTEM -> tr("跟随系统", "Follow system")
        AppLanguageMode.CHINESE -> "中文"
        AppLanguageMode.ENGLISH -> "English"
    }

    private fun systemLanguageIsChinese(): Boolean {
        val locales = appContext?.resources?.configuration?.locales
        val locale: Locale = locales?.get(0) ?: Locale.getDefault()
        return locale.language == "zh"
    }
}
