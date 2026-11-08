package com.coke.nestspace.container

import android.app.Application
import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader

/**
 * Completes startup services that the launcher task normally creates before
 * the first Activity. Packed applications may queue these tasks behind other
 * native initialization; starting the Activity early then makes synchronous
 * callers observe an uninitialized RPC layer.
 */
internal object TargetStartupHook {
    private const val TAG = "TargetStartupHook"
    private const val RPC_STARTUP_TASK = "com.dragon.read.app.launch.task.g4"

    fun ensureRpc(loader: ClassLoader, application: Application): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                loader.loadClass(RPC_STARTUP_TASK)
                    .getMethod("c", Application::class.java)
                    .invoke(null, application)
                true
            }
        }.onSuccess {
            Log.d(TAG, "RPC startup task completed")
        }.onFailure { error ->
            if (error is ClassNotFoundException) {
                Log.d(TAG, "RPC startup hook not applicable")
            } else {
                Log.w(TAG, "RPC startup hook failed", error)
            }
        }.getOrDefault(false)
}
