package com.coke.nestspace.container

import android.content.Context
import android.graphics.Color
import java.io.File

/**
 * A downward-fading band at the top of every visible container app. It helps
 * users tell a virtualized app apart from a real one; its color either follows
 * the target's status bar or uses a fixed color.
 *
 * Stored as plain files because StageA hosts run in :cN processes, where
 * SharedPreferences keeps a stale per-process cache after the setting is
 * toggled in the main process.
 */
object ContainerIndicatorSettings {

    enum class ColorMode { AUTO, FIXED }

    private const val ENABLED_FILE = "container_indicator_enabled"
    private const val COLOR_FILE = "container_indicator_color"
    private const val AUTO = "auto"
    private const val FIXED_PREFIX = "fixed:"
    const val DEFAULT_COLOR = 0xFF4A6CF7.toInt()

    fun isEnabled(context: Context): Boolean = runCatching {
        File(context.applicationContext.filesDir, ENABLED_FILE).readText().trim() != "0"
    }.getOrDefault(true)

    fun setEnabled(context: Context, enabled: Boolean) {
        write(context, ENABLED_FILE, if (enabled) "1" else "0")
    }

    fun colorMode(context: Context): ColorMode {
        val value = runCatching {
            File(context.applicationContext.filesDir, COLOR_FILE).readText().trim()
        }.getOrDefault("")
        return if (value.startsWith(FIXED_PREFIX)) ColorMode.FIXED else ColorMode.AUTO
    }

    fun fixedColor(context: Context): Int {
        val value = runCatching {
            File(context.applicationContext.filesDir, COLOR_FILE).readText().trim()
        }.getOrDefault("")
        if (!value.startsWith(FIXED_PREFIX)) return DEFAULT_COLOR
        return runCatching {
            value.removePrefix(FIXED_PREFIX).toLong(16).toInt()
        }.getOrDefault(DEFAULT_COLOR)
    }

    fun setAuto(context: Context) {
        write(context, COLOR_FILE, AUTO)
    }

    fun setFixedColor(context: Context, color: Int) {
        write(context, COLOR_FILE, FIXED_PREFIX + Integer.toHexString(color))
    }

    private fun write(context: Context, name: String, value: String) {
        runCatching {
            File(context.applicationContext.filesDir, name).writeText(value)
        }
    }

    /** Palette offered in Appearance settings. */
    val palette: List<Int> = listOf(
        0xFF4A6CF7.toInt(),
        0xFF7C4DFF.toInt(),
        0xFF2E7D32.toInt(),
        0xFFF57C00.toInt(),
        0xFFD32F2F.toInt(),
        0xFFE91E63.toInt(),
        Color.BLACK,
        0xFF607D8B.toInt()
    )
}
