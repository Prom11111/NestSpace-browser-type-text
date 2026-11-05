package com.coke.nestspace.data

import android.content.Context

/** System-app shortcuts shown on the NestSpace desktop. */
object DesktopAppsStore {

    private const val PREFS = "virtual"
    private const val KEY_DESKTOP_SYSTEM_APPS = "desktop_system_apps"

    fun load(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DESKTOP_SYSTEM_APPS, emptySet())
            ?.toSet()
            .orEmpty()

    fun save(context: Context, packages: Set<String>) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DESKTOP_SYSTEM_APPS, packages)
            .apply()
    }
}
