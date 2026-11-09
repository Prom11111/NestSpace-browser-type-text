package com.coke.nestspace.container.compat.p74332

import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader

/** Seeds process and account caches required by this exact APK profile. */
internal object ProcessIdentitySeedHook {
    private const val TAG = "ProcessIdentitySeedHook"
    private const val PROCESS_NAME_FIELD = "sCurProcessName"
    private const val ACCOUNT_CONFIG_FIELD = "b"
    private const val MIGRATION_CACHE_FIELD = "e"
    private const val COMPONENT_DISABLED = 2

    fun install(
        profile: ProfileP74332.Classes,
        loader: ClassLoader,
        processName: String
    ): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                val toolUtils = profile.toolUtils
                val field = toolUtils.getDeclaredField(PROCESS_NAME_FIELD)
                    .apply { isAccessible = true }
                field.set(null, processName)
                val processSeeded = field.get(null) == processName

                val accountState = profile.accountState
                val accountConfigField = accountState.getDeclaredField(ACCOUNT_CONFIG_FIELD)
                    .apply { isAccessible = true }
                if (accountConfigField.get(null) == null) {
                    val config = profile.accountConfig
                        .getDeclaredConstructor()
                        .newInstance()
                    accountConfigField.set(null, config)
                }

                val migrationState = profile.migrationState
                val migrationCache = migrationState.getDeclaredField(MIGRATION_CACHE_FIELD)
                    .apply { isAccessible = true }
                migrationCache.setInt(null, COMPONENT_DISABLED)

                processSeeded &&
                    accountConfigField.get(null) != null &&
                    migrationCache.getInt(null) == COMPONENT_DISABLED
            }
        }.onSuccess { installed ->
            Log.d(TAG, "process cache seeded=$installed name=$processName")
        }.onFailure { error ->
            if (error is ClassNotFoundException) {
                Log.d(TAG, "process cache hook not applicable")
            } else {
                Log.w(TAG, "process cache seed failed", error)
            }
        }.getOrDefault(false)
}
