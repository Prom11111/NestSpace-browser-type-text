package com.coke.nestspace.container

import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader

/**
 * Seeds SDK process-name caches before the target Application starts.
 *
 * ActivityManager snapshots can be rewritten by SystemServiceIdentity, but
 * several ByteDance SDKs cache the result in a static field before that
 * service is queried. Their main-process gate then rejects the container and
 * skips account, token, and device-registration initialization.
 */
internal object TargetProcessIdentityHook {
    private const val TAG = "TargetProcessIdentityHook"
    private const val TOOL_UTILS_CLASS = "com.ss.android.common.util.ToolUtils"
    private const val PROCESS_NAME_FIELD = "sCurProcessName"
    private const val ACCOUNT_STATE_CLASS = "s28.l"
    private const val ACCOUNT_CONFIG_FIELD = "b"
    private const val ACCOUNT_CONFIG_CLASS = "com.dragon.read.base.h0"
    private const val MIGRATION_STATE_CLASS = "j90.a"
    private const val MIGRATION_CACHE_FIELD = "e"
    private const val COMPONENT_DISABLED = 2

    fun install(loader: ClassLoader, processName: String): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                val toolUtils = loader.loadClass(TOOL_UTILS_CLASS)
                val field = toolUtils.getDeclaredField(PROCESS_NAME_FIELD)
                    .apply { isAccessible = true }
                field.set(null, processName)
                val processSeeded = field.get(null) == processName

                // Account request interceptors run from the device SDK before
                // the target's lower-priority TTAccount initializer thread.
                // Seed the SDK's own config object so the first registration
                // request uses the same account stack as later requests.
                val accountState = loader.loadClass(ACCOUNT_STATE_CLASS)
                val accountConfigField = accountState.getDeclaredField(ACCOUNT_CONFIG_FIELD)
                    .apply { isAccessible = true }
                if (accountConfigField.get(null) == null) {
                    val config = loader.loadClass(ACCOUNT_CONFIG_CLASS)
                        .getDeclaredConstructor()
                        .newInstance()
                    accountConfigField.set(null, config)
                }

                // Device registration's legacy migration probe caches the
                // PackageManager component state during provider startup.
                // At that point the virtual package can still report DEFAULT
                // even though the per-instance preference records DISABLED,
                // which makes every cold start regenerate clientudid/cdid.
                val migrationState = loader.loadClass(MIGRATION_STATE_CLASS)
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
