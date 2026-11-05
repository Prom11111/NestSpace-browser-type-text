package com.coke.nestspace.assistant

import android.content.Context

/**
 * Persistent settings for the iPhone-style assistive dot.
 *
 * The dot uses one color over the NestSpace host UI and another while a
 * virtual app is in front, so the user can tell which surface the tap will
 * bring the assistant over.
 */
object FloatingDotSettings {

    /** Host UI (browser, voice assistant, system apps): warm orange by default. */
    const val DEFAULT_HOST_COLOR = 0xFFFF7A45.toInt()

    /** Virtual container and apps inside it: blue by default. */
    const val DEFAULT_CONTAINER_COLOR = 0xFF4C7DFF.toInt()

    val HOST_PRESETS = listOf(
        DEFAULT_HOST_COLOR,
        DEFAULT_CONTAINER_COLOR,
        0xFF7C4DFF.toInt(),
        0xFF00BFA5.toInt(),
        0xFFFF4081.toInt(),
        0xFF546E7A.toInt()
    )

    val CONTAINER_PRESETS = listOf(
        DEFAULT_CONTAINER_COLOR,
        DEFAULT_HOST_COLOR,
        0xFFEF5350.toInt(),
        0xFF43A047.toInt(),
        0xFFF9A825.toInt(),
        0xFF8E24AA.toInt()
    )

    data class Config(
        val enabled: Boolean,
        val hostColor: Int,
        val containerColor: Int,
        val x: Int,
        val y: Int,
        val speechLanguage: String = SPEECH_LANG_ZH,
        /** 10..100; 100 is fully opaque. */
        val opacity: Int = DEFAULT_OPACITY
    )

    const val SPEECH_LANG_ZH = "zh"
    const val SPEECH_LANG_EN = "en"
    const val MIN_OPACITY = 10
    const val MAX_OPACITY = 100
    const val DEFAULT_OPACITY = MAX_OPACITY
    const val OPACITY_STEP = 10

    private const val PREFS = "floating_dot"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_HOST_COLOR = "host_color"
    private const val KEY_CONTAINER_COLOR = "container_color"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_SPEECH_LANGUAGE = "speech_language"
    private const val KEY_OPACITY = "opacity"

    fun load(context: Context): Config {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            hostColor = prefs.getInt(KEY_HOST_COLOR, DEFAULT_HOST_COLOR),
            containerColor = prefs.getInt(KEY_CONTAINER_COLOR, DEFAULT_CONTAINER_COLOR),
            x = prefs.getInt(KEY_X, -1),
            y = prefs.getInt(KEY_Y, -1),
            speechLanguage = prefs.getString(KEY_SPEECH_LANGUAGE, SPEECH_LANG_ZH)
                ?: SPEECH_LANG_ZH,
            opacity = prefs.getInt(KEY_OPACITY, DEFAULT_OPACITY)
                .coerceIn(MIN_OPACITY, MAX_OPACITY)
        )
    }

    fun save(context: Context, config: Config) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putInt(KEY_HOST_COLOR, config.hostColor)
            .putInt(KEY_CONTAINER_COLOR, config.containerColor)
            .putInt(KEY_X, config.x)
            .putInt(KEY_Y, config.y)
            .putString(KEY_SPEECH_LANGUAGE, config.speechLanguage)
            .putInt(KEY_OPACITY, config.opacity.coerceIn(MIN_OPACITY, MAX_OPACITY))
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        save(context, load(context).copy(enabled = enabled))
    }

    fun setColors(context: Context, hostColor: Int, containerColor: Int) {
        save(context, load(context).copy(hostColor = hostColor, containerColor = containerColor))
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    fun colorLabel(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
