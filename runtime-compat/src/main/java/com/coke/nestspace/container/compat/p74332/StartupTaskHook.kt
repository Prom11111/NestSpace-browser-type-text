package com.coke.nestspace.container.compat.p74332

import android.app.Application
import android.content.Context
import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader

/** Completes target startup tasks required before the first Activity. */
internal object StartupTaskHook {
    private const val TAG = "StartupTaskHook"

    fun ensureRpc(
        profile: ProfileP74332.Classes,
        loader: ClassLoader,
        application: Application
    ): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                profile.rpcStartupTask
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

    fun ensureNetwork(
        profile: ProfileP74332.Classes,
        loader: ClassLoader,
        application: Application
    ): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                initializeAppLog(profile, application)
                val taskClass = profile.networkStartupTask
                val task = taskClass.getDeclaredConstructor()
                    .apply { isAccessible = true }
                    .newInstance()
                taskClass.getMethod("e", Application::class.java)
                    .invoke(task, application)

                profile.imageStartupTask
                    .getMethod("c", Application::class.java)
                    .invoke(null, application)
                val ttnetInit = profile.ttnetInit
                ttnetInit.getMethod("getCronetProvider").invoke(null) != null
            }
        }.onSuccess { installed ->
            Log.d(TAG, "network startup task completed provider=$installed")
        }.onFailure { error ->
            if (error is ClassNotFoundException) {
                Log.d(TAG, "network startup hook not applicable")
            } else {
                Log.w(TAG, "network startup hook failed", error)
            }
        }.getOrDefault(false)

    private fun initializeAppLog(
        profile: ProfileP74332.Classes,
        application: Application
    ): Boolean {
        val singleAppContextClass = profile.singleAppContext
        val singleAppContext = singleAppContextClass
            .getMethod("inst", Context::class.java)
            .invoke(null, application)

        val appLogInitializer = profile.appLogInitializer
        appLogInitializer
            .getMethod(
                "c",
                Context::class.java,
                singleAppContextClass
            )
            .invoke(null, application, singleAppContext)

        val initialized = runCatching {
            appLogInitializer.getField("b").getBoolean(null)
        }.getOrDefault(false)
        Log.d(TAG, "AppLog startup completed initialized=$initialized")
        return initialized
    }
}
