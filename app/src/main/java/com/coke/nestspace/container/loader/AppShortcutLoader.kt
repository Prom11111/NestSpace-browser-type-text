package com.coke.nestspace.container.loader

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.coke.nestspace.container.HiddenApiCompat
import com.coke.nestspace.data.AppShortcut
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the static shortcuts an APK declares through
 * `android.app.shortcuts` on its launcher Activity.
 *
 * The package is not installed, so resources are loaded from the APK's asset
 * path. Dynamic shortcuts created by the target at runtime are handled by
 * [com.coke.nestspace.container.shortcut.ContainerShortcutManager] and merged
 * by the engine at menu time.
 */
internal object AppShortcutLoader {

    private const val TAG = "AppShortcutLoader"
    private const val NS = "http://schemas.android.com/apk/res/android"
    private const val MAX_SHORTCUTS = 8
    private val cache = ConcurrentHashMap<String, List<AppShortcut>>()

    fun load(
        context: Context,
        apkPath: String,
        packageName: String,
        launcherActivity: String,
        cacheKey: String
    ): List<AppShortcut> = cache[cacheKey] ?: run {
        val shortcuts = runCatching {
            parse(context, apkPath, packageName, launcherActivity)
        }.onFailure {
            Log.w(TAG, "shortcut parse failed: $packageName", it)
        }.getOrDefault(emptyList())
        cache[cacheKey] = shortcuts
        shortcuts
    }

    private fun parse(
        context: Context,
        apkPath: String,
        packageName: String,
        launcherActivity: String
    ): List<AppShortcut> {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(
            apkPath,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        ) ?: return emptyList()
        val appInfo = info.applicationInfo ?: return emptyList()
        appInfo.sourceDir = apkPath
        appInfo.publicSourceDir = apkPath
        val activities = info.activities.orEmpty()
        val hasShortcuts = { activity: android.content.pm.ActivityInfo ->
            activity.metaData?.containsKey("android.app.shortcuts") == true
        }
        val holder = activities.firstOrNull { it.name == launcherActivity && hasShortcuts(it) }
            ?: activities.firstOrNull { hasShortcuts(it) }
            ?: return emptyList()
        val resourceId = holder.metaData?.getInt("android.app.shortcuts") ?: return emptyList()
        if (resourceId == 0) return emptyList()
        val resources = resourcesFor(context, appInfo, apkPath) ?: return emptyList()

        val result = mutableListOf<AppShortcut>()
        val parser = resources.getXml(resourceId)
        var shortcutId: String? = null
        var label: String? = null
        var iconRes = 0
        var enabled = true
        var intents = mutableListOf<PendingIntent>()

        fun flush() {
            val id = shortcutId
            if (id.isNullOrBlank() || !enabled) {
                shortcutId = null
                label = null
                iconRes = 0
                intents = mutableListOf()
                return
            }
            val chosen = intents.firstOrNull {
                it.targetPackage == null || it.targetPackage == packageName
            } ?: run {
                shortcutId = null
                label = null
                iconRes = 0
                intents = mutableListOf()
                return
            }
            val target = chosen.targetActivity?.takeIf { it.isNotBlank() }
                ?: launcherActivity
            val labelText = label?.takeIf { it.isNotBlank() } ?: id
            result += AppShortcut(
                id = id,
                label = labelText,
                icon = icon(resources, iconRes),
                targetActivity = target,
                action = chosen.action,
                data = chosen.data,
                mimeType = chosen.mimeType,
                categories = chosen.categories.toList(),
                extras = chosen.extras
            )
            shortcutId = null
            label = null
            iconRes = 0
            intents = mutableListOf()
        }

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "shortcut" -> {
                        shortcutId = parser.getAttributeValue(NS, "shortcutId")
                            ?: parser.getAttributeValue(null, "shortcutId")
                        iconRes = parser.getAttributeResourceValue(NS, "icon", 0)
                        enabled = parser.getAttributeBooleanValue(NS, "enabled", true)
                        label = readText(
                            parser.getAttributeResourceValue(NS, "shortcutShortLabel", 0),
                            parser.getAttributeResourceValue(NS, "shortcutLongLabel", 0),
                            parser.getAttributeValue(NS, "shortcutShortLabel"),
                            parser.getAttributeValue(NS, "shortcutLongLabel"),
                            resources
                        )
                    }

                    "intent" -> {
                        val targetPackage = parser.getAttributeValue(NS, "targetPackage")
                        val targetActivity = parser.getAttributeValue(NS, "targetClass")
                            ?: parser.getAttributeValue(NS, "targetActivity")
                        intents += PendingIntent(
                            action = parser.getAttributeValue(NS, "action"),
                            targetPackage = targetPackage,
                            targetActivity = targetActivity,
                            data = parser.getAttributeValue(NS, "data"),
                            mimeType = parser.getAttributeValue(NS, "mimeType")
                        )
                    }

                    "category" -> {
                        val name = parser.getAttributeValue(NS, "name")
                            ?: parser.getAttributeValue(null, "name")
                        if (!name.isNullOrBlank()) intents.lastOrNull()?.categories?.add(name)
                    }

                    "extra" -> {
                        val name = parser.getAttributeValue(NS, "name")
                        val value = parser.getAttributeValue(NS, "value")
                        if (!name.isNullOrBlank() && value != null) {
                            intents.lastOrNull()?.extras?.putString(name, value)
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "shortcut") {
                flush()
                if (result.size >= MAX_SHORTCUTS) break
            }
        }
        parser.close()
        return result
    }

    private fun readText(
        shortRes: Int,
        longRes: Int,
        shortRaw: String?,
        longRaw: String?,
        resources: Resources
    ): String? {
        val res = shortRes.takeIf { it != 0 } ?: longRes.takeIf { it != 0 }
        if (res != null) {
            runCatching { resources.getText(res).toString() }.getOrNull()?.let { return it }
        }
        return shortRaw?.takeIf { it.isNotBlank() } ?: longRaw?.takeIf { it.isNotBlank() }
    }

    private fun resourcesFor(
        context: Context,
        appInfo: android.content.pm.ApplicationInfo,
        apkPath: String
    ): Resources? {
        runCatching { context.packageManager.getResourcesForApplication(appInfo) }
            .getOrNull()?.let { return it }
        HiddenApiCompat.ensure()
        return runCatching {
            val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            if ((addAssetPath.invoke(assets, apkPath) as? Int ?: 0) == 0) return null
            @Suppress("DEPRECATION")
            Resources(assets, context.resources.displayMetrics, context.resources.configuration)
        }.getOrNull()
    }

    private fun icon(resources: Resources, resId: Int): ImageBitmap? {
        if (resId == 0) return null
        val drawable: Drawable = runCatching { resources.getDrawable(resId, null) }
            .getOrNull() ?: return null
        return runCatching {
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }

    private class PendingIntent(
        val action: String?,
        val targetPackage: String?,
        val targetActivity: String?,
        val data: String?,
        val mimeType: String?,
        val categories: MutableList<String> = mutableListOf(),
        val extras: Bundle = Bundle()
    )
}
