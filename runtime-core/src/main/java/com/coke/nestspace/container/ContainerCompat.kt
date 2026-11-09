package com.coke.nestspace.container

import android.app.Application
import android.app.AppComponentFactory
import android.content.pm.ApplicationInfo
import android.util.Log
import com.coke.nestspace.container.loader.ApkClassIndex

/**
 * Support for APKs that embed a second Application bootstrap behind a native
 * security wrapper. Detection is based on class capabilities rather than the
 * target package name.
 */
object ContainerCompat {

    private const val TAG = "ContainerCompat"

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

    private fun findAppComponentFactory(
        spec: ContainerLaunchSpec,
        loader: ClassLoader
    ): Class<out AppComponentFactory>? {
        val apkPaths = listOf(spec.baseApkPath) + spec.splitApkPaths
        val applicationPackage = spec.applicationInfo.className
            ?.substringBeforeLast('.', spec.packageName)
            ?: spec.packageName
        val declaredFactory = spec.applicationInfo.appComponentFactory
            ?.takeIf { it.isNotBlank() }
        if (declaredFactory != null) {
            factoryClass(loader, declaredFactory)?.let { return it }
        }
        val candidates = ApkClassIndex.classNames(apkPaths).asSequence()
            .filter { it.startsWith("$applicationPackage.") }
        for (name in candidates) {
            factoryClass(loader, name)?.let { return it }
        }
        return null
    }

    private fun factoryClass(
        loader: ClassLoader,
        name: String
    ): Class<out AppComponentFactory>? {
        val candidate = runCatching {
            loader.loadClass(name).asSubclass(AppComponentFactory::class.java)
        }.getOrNull() ?: return null
        if (candidate.classLoader !== loader) return null
        val modifier = candidate.modifiers
        if (java.lang.reflect.Modifier.isAbstract(modifier) ||
            !java.lang.reflect.Modifier.isPublic(modifier) ||
            candidate.declaredConstructors.none { it.parameterTypes.isEmpty() } ||
            candidate.declaredMethods.none { it.name == "instantiateClassLoader" }
        ) {
            return null
        }
        Log.d(TAG, "AppComponentFactory candidate: $name")
        return candidate
    }

    /** Keep the Tinker ApplicationLike until the session context is wired. */
    fun rememberAppLike(application: Application, like: Any) {
        synchronized(appLikes) { appLikes[application] = like }
    }

    fun takeAppLike(application: Application): Any? =
        synchronized(appLikes) { appLikes.remove(application) }
}
