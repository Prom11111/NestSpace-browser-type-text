package com.coke.nestspace.container

import android.content.ContentProvider
import android.content.pm.ProviderInfo
import android.util.Log

/**
 * Publishes a container-local ContentProvider in ActivityThread's provider map.
 *
 * A virtual app's authorities are not installed in the host PackageManager, so
 * the host ContentResolver would normally send every target URI to
 * system_server and get null back. ActivityThread.acquireExistingProvider()
 * consults mProviderMap before asking AMS, so registering the container's
 * dispatcher there makes target URIs resolve locally while ordinary host
 * authorities (Settings, MediaStore, ...) keep working through the same
 * resolver. That matters because framework code such as TextKeyListener reads
 * Settings through Context.getContentResolver().
 *
 * The registrations are process-global (keyed by authority+user), matching how
 * ActivityThread itself treats providers. destroy() removes them again so a
 * later session can install its own dispatcher under the same authorities.
 */
internal object ActivityThreadProviderRegistry {

    private const val TAG = "ContainerProviderReg"

    private class Registry(
        val providerMap: MutableMap<Any, Any>,
        val keyConstructor: java.lang.reflect.Constructor<*>,
        val recordConstructor: java.lang.reflect.Constructor<*>,
        val holderClass: Class<*>,
        val holderConstructor: java.lang.reflect.Constructor<*>,
        val holderProviderField: java.lang.reflect.Field,
        val holderNoReleaseField: java.lang.reflect.Field,
        val providerClass: Class<*>
    )

    @Volatile
    private var registry: Registry? = null

    private fun registry(): Registry? = registry ?: runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            ?: return null
        val providerMapField = threadClass.getDeclaredField("mProviderMap")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val providerMap = providerMapField.get(thread) as MutableMap<Any, Any>

        val keyClass = Class.forName("android.app.ActivityThread\$ProviderKey")
        val keyConstructor = keyClass
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }

        val providerClass = Class.forName("android.content.IContentProvider")
        val holderClass = Class.forName("android.app.ContentProviderHolder")
        val recordClass = Class.forName("android.app.ActivityThread\$ProviderClientRecord")
        val recordConstructor = recordClass.getDeclaredConstructor(
            Array<String>::class.java,
            providerClass,
            ContentProvider::class.java,
            holderClass
        ).apply { isAccessible = true }

        Registry(
            providerMap = providerMap,
            keyConstructor = keyConstructor,
            recordConstructor = recordConstructor,
            holderClass = holderClass,
            holderConstructor = holderClass
                .getDeclaredConstructor(ProviderInfo::class.java)
                .apply { isAccessible = true },
            holderProviderField = holderClass.getDeclaredField("provider")
                .apply { isAccessible = true },
            holderNoReleaseField = holderClass.getDeclaredField("noReleaseNeeded")
                .apply { isAccessible = true },
            providerClass = providerClass
        )
    }.onFailure {
        Log.w(TAG, "provider registration unavailable", it)
    }.getOrNull()

    fun register(
        provider: ContentProvider,
        providerInfo: ProviderInfo,
        authorities: List<String>,
        userId: Int
    ): List<Any> {
        val reg = registry() ?: return emptyList()
        val transport = runCatching {
            ContentProvider::class.java
                .getDeclaredMethod("getIContentProvider")
                .apply { isAccessible = true }
                .invoke(provider)
        }.onFailure {
            Log.w(TAG, "getIContentProvider failed", it)
        }.getOrNull() ?: return emptyList()

        val holder = reg.holderConstructor.newInstance(providerInfo)
        reg.holderProviderField.set(holder, transport)
        reg.holderNoReleaseField.setBoolean(holder, true)

        val keys = mutableListOf<Any>()
        authorities.forEach { authority ->
            runCatching {
                // Never replace a provider the host process already published
                // (for example a system authority with the same name).
                val key = reg.keyConstructor.newInstance(authority, userId)
                if (reg.providerMap.containsKey(key)) {
                    Log.w(TAG, "authority already registered in host: $authority")
                    return@runCatching
                }
                val record = reg.recordConstructor.newInstance(
                    arrayOf(authority),
                    transport,
                    provider,
                    holder
                )
                reg.providerMap[key] = record
                keys += key
                Log.d(TAG, "registered $authority -> ${providerInfo.name}")
            }.onFailure {
                Log.w(TAG, "register authority failed: $authority", it)
            }
        }
        return keys
    }

    fun unregister(keys: List<Any>) {
        if (keys.isEmpty()) return
        val reg = registry() ?: return
        keys.forEach { key ->
            runCatching { reg.providerMap.remove(key) }
                .onFailure { Log.w(TAG, "unregister failed", it) }
        }
    }
}
