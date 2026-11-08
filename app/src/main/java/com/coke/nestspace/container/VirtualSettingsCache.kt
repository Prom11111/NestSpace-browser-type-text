package com.coke.nestspace.container

import android.provider.Settings
import android.util.Log
import java.lang.reflect.Field

/**
 * Settings.Secure caches values in a process-wide static NameValueCache.
 * NestSpace can read the host Android ID before a target instance starts,
 * which would otherwise pin every virtual app to the host SSAID even after
 * its target ContentResolver is installed.
 */
internal object VirtualSettingsCache {
    private const val TAG = "VirtualSettingsCache"

    fun seedAndroidId(context: ContainerContext): Boolean =
        runCatching {
            val cache = secureCache()
            clearCache(cache)
            val value = Settings.Secure.getString(
                context.targetContentResolver,
                Settings.Secure.ANDROID_ID
            )
            Log.d(TAG, "target androidId=$value package=${context.packageName}")
            !value.isNullOrBlank()
        }.onFailure {
            Log.w(TAG, "settings cache seed failed", it)
        }.getOrDefault(false)

    private fun secureCache(): Any {
        val field = Settings.Secure::class.java.getDeclaredField("sNameValueCache")
        field.isAccessible = true
        return field.get(null) ?: error("Settings.Secure cache unavailable")
    }

    private fun clearCache(cache: Any) {
        declaredField(cache.javaClass, "mValues").get(cache)
            ?.let { (it as? MutableMap<*, *>)?.clear() }
        declaredField(cache.javaClass, "mGenerationTrackers").get(cache)
            ?.let { (it as? MutableMap<*, *>)?.clear() }

        val holder = declaredField(cache.javaClass, "mProviderHolder").get(cache)
            ?: return
        declaredField(holder.javaClass, "mContentProvider").set(holder, null)
    }

    private fun declaredField(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching {
                return current.getDeclaredField(name).apply { isAccessible = true }
            }
            current = current.superclass
        }
        error("Field $name not found on ${type.name}")
    }
}
