package com.coke.nestspace.host

import android.content.Context
import com.coke.nestspace.data.AppSettingsEvents

/**
 * Opt-in switch for controlling the real device through root.
 *
 * The tools are only exposed while this is enabled; the UI only allows
 * enabling it after [RootShell.status] reports a working su binary.
 */
object HostControlSettings {

    private const val PREFS = "host_control"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        AppSettingsEvents.notifyChanged()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
