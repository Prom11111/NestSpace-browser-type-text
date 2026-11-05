package com.coke.nestspace.ui.desktop

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persisted launcher layout: ordered apps and folders. */
internal sealed interface SavedDesktopSlot {
    data class App(val appId: String) : SavedDesktopSlot
    data class Folder(
        val id: String,
        val name: String,
        val appIds: List<String>
    ) : SavedDesktopSlot
}

/**
 * Stores the desktop arrangement in SharedPreferences as a small JSON array.
 * Only the arrangement is stored; app metadata is resolved from the current
 * virtual/system app lists on every render, so uninstalls disappear cleanly
 * and newly installed apps are appended by the caller.
 */
internal object DesktopLayoutStore {

    private const val PREFS = "desktop_layout"
    private const val KEY_SLOTS = "slots"

    fun load(context: Context): List<SavedDesktopSlot> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SLOTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    when (obj.optString("type")) {
                        "app" -> obj.optString("id")
                            .takeIf { it.isNotBlank() }
                            ?.let { add(SavedDesktopSlot.App(it)) }

                        "folder" -> {
                            val id = obj.optString("id").takeIf { it.isNotBlank() }
                                ?: continue
                            val name = obj.optString("name", AppLanguage.tr("文件夹", "Folder"))
                                .ifBlank { AppLanguage.tr("文件夹", "Folder") }
                            val apps = obj.optJSONArray("apps")?.let { ids ->
                                buildList {
                                    for (i in 0 until ids.length()) {
                                        ids.optString(i)
                                            .takeIf { it.isNotBlank() }
                                            ?.let(::add)
                                    }
                                }
                            }.orEmpty()
                            add(SavedDesktopSlot.Folder(id, name, apps.distinct()))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, slots: List<SavedDesktopSlot>) {
        val array = JSONArray()
        slots.forEach { slot ->
            val obj = JSONObject()
            when (slot) {
                is SavedDesktopSlot.App -> {
                    obj.put("type", "app")
                    obj.put("id", slot.appId)
                }

                is SavedDesktopSlot.Folder -> {
                    obj.put("type", "folder")
                    obj.put("id", slot.id)
                    obj.put("name", slot.name)
                    obj.put("apps", JSONArray(slot.appIds))
                }
            }
            array.put(obj)
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SLOTS, array.toString())
            .apply()
    }
}
