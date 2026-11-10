package com.coke.nestspace.container.identity

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.util.Log
import com.coke.nestspace.runtime.RuntimeHostIdentity
import com.coke.nestspace.R
import java.io.File

/**
 * The real host package. Context identity inside a container may be rewritten
 * to the target app, so never derive the host package from a Context.
 */
object HostIdentity {
    val packageName: String get() = RuntimeHostIdentity.packageName

    @Volatile
    private var capturedApplication: Application? = null

    @Volatile
    private var capturedResources: Resources? = null

    @Volatile
    private var capturedExternalFilesDir: File? = null

    @Volatile
    private var capturedExternalCacheDir: File? = null

    /**
     * Slot processes alternate between target and host Resource identities.
     * Freeze the values before a virtual session can publish its Application
     * and LoadedApk over the process-wide host fields.
     */
    fun captureProcess(context: Context) {
        val appContext = context.applicationContext
        if (capturedApplication == null) {
            capturedApplication = appContext as? Application
        }
        if (capturedResources == null) {
            val current = appContext.resources
            capturedResources = if (isHostResources(current)) {
                current
            } else {
                buildHostResources(appContext).also { resources ->
                    if (!isHostResources(resources)) {
                        Log.e("HostIdentity", "unable to recover host Resources")
                    }
                }
            }
            Log.d(
                "HostIdentity",
                "host resources captured=${isHostResources(capturedResources!!)} " +
                    "source=${appContext.applicationInfo.sourceDir}"
            )
        }
        if (capturedExternalFilesDir == null) {
            capturedExternalFilesDir = runCatching {
                appContext.getExternalFilesDir(null)
            }.getOrNull()
        }
        if (capturedExternalCacheDir == null) {
            capturedExternalCacheDir = runCatching {
                appContext.getExternalCacheDir()
            }.getOrNull()
        }
    }

    val application: Application?
        get() = capturedApplication

    val resources: Resources?
        get() = capturedResources

    val externalFilesDir: File?
        get() = capturedExternalFilesDir

    val externalCacheDir: File?
        get() = capturedExternalCacheDir

    private fun isHostResources(resources: Resources): Boolean =
        runCatching {
            resources.getResourcePackageName(R.style.Theme_Virtual_Container) == packageName
        }.getOrDefault(false)

    private fun buildHostResources(context: Context): Resources {
        val manager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java
            .getMethod("addAssetPath", String::class.java)
        val paths = buildList {
            add("/system/framework/framework-res.apk")
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.let(::addAll)
        }
        paths.forEach { path ->
            val cookie = addAssetPath.invoke(manager, path) as Int
            if (cookie == 0) Log.w("HostIdentity", "host asset path rejected: $path")
        }
        return Resources(
            manager,
            context.resources.displayMetrics,
            context.resources.configuration
        )
    }
}
