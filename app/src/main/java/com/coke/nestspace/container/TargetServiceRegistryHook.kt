package com.coke.nestspace.container

import android.content.Context
import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader

/**
 * Breaks service-manager reentrancy during target process startup.
 *
 * The short-video service implementation queries ShortSeriesApi from inside
 * its own constructor. A normal installed process usually resolves that API
 * first; a fresh virtual process can enter through the implementation and get
 * null. Registering the APK's own implementation before Application.onCreate
 * lets the existing init path continue normally.
 */
internal object TargetServiceRegistryHook {
    private const val TAG = "TargetServiceRegistryHook"
    private const val SERVICE_MANAGER = "com.bytedance.news.common.service.manager.ServiceManager"
    private const val SHORT_SERIES_API = "com.dragon.read.component.shortvideo.api.ShortSeriesApi"
    private const val SHORT_SERIES_IMPL = "com.dragon.read.component.shortvideo.saas.ShortSeriesImpl"
    private const val SHORT_SERIES_DOCKER = "or4.z"

    fun install(
        loader: ClassLoader,
        context: Context,
        initializeDocker: Boolean
    ): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                val api = loader.loadClass(SHORT_SERIES_API)
                val manager = loader.loadClass(SERVICE_MANAGER)
                val implementationClass = loader.loadClass(SHORT_SERIES_IMPL)
                val companion = api.getField("Companion").get(null)
                val lazyField = companion.javaClass.declaredFields.firstOrNull {
                    it.type.name == "kotlin.Lazy"
                }?.apply { isAccessible = true }
                val lazy = lazyField?.get(companion)
                val lazyValueField = lazy?.javaClass?.declaredFields?.firstOrNull {
                    it.name == "_value"
                }?.apply { isAccessible = true }

                val lazyValue = lazyValueField?.get(lazy)
                var service = lazyValue?.takeIf(api::isInstance)
                    ?: manager.getMethod("getServiceCache", Class::class.java)
                        .invoke(null, api)
                        ?.takeIf(api::isInstance)
                if (service == null) {
                    service = implementationClass
                        .getDeclaredConstructor()
                        .newInstance()
                }

                manager.getMethod(
                    "registerService",
                    Class::class.java,
                    Any::class.java
                ).invoke(null, api, service)

                if (initializeDocker) {
                    initializeDocker(
                        loader = loader,
                        implementationClass = implementationClass,
                        service = service,
                        context = context
                    )
                }

                if (lazy != null && lazyValue?.takeIf(api::isInstance) == null) {
                    lazy.javaClass.getMethod("getValue").invoke(lazy)
                }
                manager.getMethod("getServiceCache", Class::class.java)
                    .invoke(null, api) != null
            }
        }.onSuccess { installed ->
            Log.d(TAG, "short-video service registered=$installed")
        }.onFailure { error ->
            if (error is ClassNotFoundException) {
                Log.d(TAG, "service registry hook not applicable")
            } else {
                Log.w(TAG, "service registry hook failed", error)
            }
        }.getOrDefault(false)

    private fun initializeDocker(
        loader: ClassLoader,
        implementationClass: Class<*>,
        service: Any,
        context: Context
    ) {
        if (!implementationClass.isInstance(service)) return
        val dockerField = implementationClass.getDeclaredField("docker").apply {
            isAccessible = true
        }
        if (dockerField.get(service) != null) return

        val dockerClass = loader.loadClass(SHORT_SERIES_DOCKER)
        val docker = dockerClass.getDeclaredConstructor().newInstance()
        implementationClass
            .getMethod("init", Context::class.java, dockerClass)
            .invoke(service, context, docker)
    }
}
