package com.coke.nestspace.container

import android.app.Application
import android.app.AppComponentFactory
import android.content.pm.ApplicationInfo
import android.util.Log
import dalvik.system.DexFile

/**
 * Support for APKs that embed a second Application bootstrap behind a native
 * security wrapper. Detection is based on class capabilities rather than the
 * target package name.
 */
internal object ContainerCompat {

    private const val TAG = "ContainerCompat"
    const val NIS_WRAPPER = "com.netease.nis.wrapper.MyApplication"
    const val EMBEDDED_APPLICATION_LIKE =
        "com.netease.cloudmusic.CloudMusicApplicationLike"
    const val EMBEDDED_PATCH_APPLICATION_LIKE =
        "com.netease.cloudmusic.core.patch.PatchApplicationLike"
    const val EMBEDDED_REAL_APPLICATION =
        "com.netease.cloudmusic.core.patch.RealApplication"

    private val appLikes = java.util.WeakHashMap<Application, Any>()

    /**
     * Native-protected APKs can expose an AppComponentFactory whose
     * instantiateClassLoader() hook adds encrypted runtime DEX files before
     * the Application is created. Android invokes that hook during normal
     * APK loading, but a manually attached virtual Application never reaches
     * it. Invoke the factory explicitly when present so classes that only
     * exist in the runtime DEX are available to the target.
     */
    fun installRuntimeClassLoader(
        spec: ContainerLaunchSpec,
        loader: ClassLoader,
        appInfo: ApplicationInfo
    ): ClassLoader {
        val factoryClass = findAppComponentFactory(spec, loader) ?: return loader
        return runCatching {
            val factory = factoryClass.getDeclaredConstructor().newInstance()
            val installed = factoryClass
                .getMethod(
                    "instantiateClassLoader",
                    ClassLoader::class.java,
                    ApplicationInfo::class.java
                )
                // Some factories rewrite ApplicationInfo.className while
                // setting up the runtime loader. Keep the launch spec's
                // manifest ApplicationInfo intact for the attachment step.
                .invoke(factory, loader, ApplicationInfo(appInfo)) as? ClassLoader
                ?: loader
            Log.d(
                TAG,
                "runtime class loader ${factoryClass.name}: same=${installed === loader}"
            )
            installed
        }.onFailure {
            Log.w(TAG, "runtime class loader hook failed: ${factoryClass.name}", it)
        }.getOrDefault(loader)
    }

    fun findEmbeddedApplication(
        spec: ContainerLaunchSpec,
        loader: ClassLoader
    ): Class<out Application>? {
        if (spec.applicationInfo.className != NIS_WRAPPER) return null
        val packagePrefix = spec.packageName + "."
        val candidates = classNames(
            listOf(spec.baseApkPath) + spec.splitApkPaths
        )
            .asSequence()
            .filter { it.startsWith(packagePrefix) && '$' !in it }
            .filter { it.substringAfterLast('.').contains("Application") }
            .distinct()
            .sortedWith(compareBy({ it.length }, { it }))
            .toList()
        for (name in candidates) {
            val candidate = runCatching {
                loader.loadClass(name).asSubclass(Application::class.java)
            }.getOrNull() ?: continue
            val modifier = candidate.modifiers
            if (java.lang.reflect.Modifier.isAbstract(modifier) ||
                !java.lang.reflect.Modifier.isPublic(modifier)
            ) {
                continue
            }
            if (candidate.declaredConstructors.none { it.parameterTypes.isEmpty() }) continue
            Log.d(TAG, "embedded application candidate: $name")
            return candidate
        }
        Log.w(TAG, "no no-arg embedded Application for ${spec.packageName}")
        return null
    }

    private fun classNames(apkPaths: List<String>): List<String> {
        val names = mutableListOf<String>()
        apkPaths.forEach { path ->
            val dex = runCatching { DexFile(path) }.getOrNull() ?: return@forEach
            try {
                names += dex.entries().asSequence()
            } finally {
                runCatching { dex.close() }
            }
        }
        return names
    }

    private fun findAppComponentFactory(
        spec: ContainerLaunchSpec,
        loader: ClassLoader
    ): Class<out AppComponentFactory>? {
        val apkPaths = listOf(spec.baseApkPath) + spec.splitApkPaths
        val applicationPackage = spec.applicationInfo.className
            ?.substringBeforeLast('.', spec.packageName)
            ?: spec.packageName
        val candidates = sequenceOf(
            spec.applicationInfo.appComponentFactory
        ).filterNotNull() + classNames(apkPaths).asSequence()
            .filter { it.startsWith("$applicationPackage.") }
            .distinct()
        for (name in candidates) {
            val candidate = runCatching {
                loader.loadClass(name).asSubclass(AppComponentFactory::class.java)
            }.getOrNull() ?: continue
            if (candidate.classLoader !== loader) continue
            val modifier = candidate.modifiers
            if (java.lang.reflect.Modifier.isAbstract(modifier) ||
                !java.lang.reflect.Modifier.isPublic(modifier) ||
                candidate.declaredConstructors.none { it.parameterTypes.isEmpty() } ||
                candidate.declaredMethods.none { it.name == "instantiateClassLoader" }
            ) {
                continue
            }
            Log.d(TAG, "AppComponentFactory candidate: $name")
            return candidate
        }
        return null
    }

    fun hasEmbeddedApplicationPair(loader: ClassLoader): Boolean = runCatching {
        loader.loadClass(EMBEDDED_APPLICATION_LIKE)
        loader.loadClass(EMBEDDED_PATCH_APPLICATION_LIKE)
        loader.loadClass(EMBEDDED_REAL_APPLICATION)
        true
    }.getOrDefault(false)

    /** Keep the Tinker ApplicationLike until the session context is wired. */
    fun rememberAppLike(application: Application, like: Any) {
        synchronized(appLikes) { appLikes[application] = like }
    }

    fun takeAppLike(application: Application): Any? =
        synchronized(appLikes) { appLikes.remove(application) }
}
