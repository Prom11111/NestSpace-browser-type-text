package com.coke.nestspace.container.compat.p74332

import android.content.Context
import android.util.Log
import com.coke.nestspace.container.loader.withContainerClassLoader
import java.util.concurrent.ConcurrentHashMap

/** Breaks a target service-manager reentrancy cycle for this profile. */
internal object ServiceRegistrySeedHook {
    private const val TAG = "ServiceRegistrySeedHook"

    fun install(
        profile: ProfileP74332.Classes,
        loader: ClassLoader,
        context: Context,
        initializeDocker: Boolean
    ): Boolean =
        runCatching {
            withContainerClassLoader(loader) {
                val api = profile.shortSeriesApi
                val manager = profile.serviceManager
                val implementationClass = profile.shortSeriesImpl
                val companion = api.getField("Companion").get(null)
                val lazyField = companion.javaClass.declaredFields.firstOrNull {
                    it.type.name == "kotlin.Lazy"
                }?.apply { isAccessible = true }
                val lazy = lazyField?.get(companion)
                val lazyValueField = lazy?.javaClass?.declaredFields?.firstOrNull {
                    it.name == "_value"
                }?.apply { isAccessible = true }

                val lazyValue = lazyValueField?.get(lazy)
                val cachedService = manager
                    .getMethod("getServiceCache", Class::class.java)
                    .invoke(null, api)
                var service = sequenceOf(lazyValue, cachedService)
                    .firstOrNull(implementationClass::isInstance)
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

                // The generated API delegate may already be cached before the
                // implementation has initialized its Docker. Point the lazy
                // back at the concrete implementation so later Activity lookups
                // use the fully initialized service.
                if (lazy != null && lazyValueField != null) {
                    lazyValueField.set(lazy, service)
                }

                if (initializeDocker) {
                    initializeDocker(
                        profile = profile,
                        loader = loader,
                        implementationClass = implementationClass,
                        service = service,
                        context = context
                    )
                }

                if (lazy != null && lazyValueField == null) {
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
        profile: ProfileP74332.Classes,
        loader: ClassLoader,
        implementationClass: Class<*>,
        service: Any,
        context: Context
    ) {
        if (!implementationClass.isInstance(service)) return
        val dockerField = implementationClass.getDeclaredField("docker").apply {
            isAccessible = true
        }
        val dockerClass = profile.shortSeriesDocker
        val existing = dockerField.get(service)
        val docker = existing ?: dockerClass.getDeclaredConstructor().newInstance()

        // The target's default Docker constructor registers most bindings, but
        // a partially initialized service cache can leave the device/feature
        // binding absent. Re-register it idempotently before the video Activity
        // performs a keyed lookup.
        dockerClass.getMethod(
            "a",
            Class::class.java,
            Class::class.java
        ).invoke(
            docker,
            profile.shortSeriesDevice,
            profile.shortSeriesDeviceImpl
        )
        val bindings = runCatching {
            val lazyField = dockerClass.getDeclaredField("a").apply { isAccessible = true }
            val lazy = lazyField.get(docker)
            val valueField = lazy.javaClass.getDeclaredField("_value").apply {
                isAccessible = true
            }
            val current = valueField.get(lazy) as? Map<*, *>
            if (current != null) {
                valueField.set(lazy, classLoaderAgnosticMap(current))
            }
            current?.size ?: 0
        }.getOrDefault("unavailable")
        Log.d(TAG, "docker=${docker.javaClass.name} bindings=$bindings")

        if (existing == null) {
            implementationClass
                .getMethod("init", Context::class.java, dockerClass)
                .invoke(service, context, docker)
        }
    }

    private fun classLoaderAgnosticMap(
        source: Map<*, *>
    ): ConcurrentHashMap<Any, Any> = ClassLoaderAgnosticBindings(source)

    private class ClassLoaderAgnosticBindings(
        source: Map<*, *>
    ) : ConcurrentHashMap<Any, Any>() {
        init {
            source.forEach { (key, value) ->
                if (key != null && value != null) {
                    put(key, value)
                }
            }
        }

        override fun get(key: Any): Any? {
            val exact = super.get(key)
            if (exact != null || key !is Class<*>) return exact
            return entries.firstOrNull {
                (it.key as? Class<*>)?.name == key.name
            }?.value
        }

        override fun containsKey(key: Any): Boolean =
            super.containsKey(key) || (
                key is Class<*> &&
                    entries.any { (it.key as? Class<*>)?.name == key.name }
                )
    }
}
