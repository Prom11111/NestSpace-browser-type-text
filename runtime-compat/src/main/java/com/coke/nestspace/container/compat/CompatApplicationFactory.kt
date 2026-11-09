package com.coke.nestspace.container.compat

import android.app.Application
import android.content.Context
import android.util.Log
import com.coke.nestspace.container.ContainerCompat
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.runtime.RuntimeApplicationFactory
import com.coke.nestspace.runtime.RuntimeApplicationSpec

/**
 * Compatibility Application backend. All hardened-Application detection and
 * manual attach rules live here; the generic container session only invokes
 * this factory through runtime-api.
 */
object CompatApplicationFactory : RuntimeApplicationFactory {
    private const val TAG = "CompatAppFactory"

    override fun create(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader,
        containerContext: Context
    ): Application {
        val bootstrap = runCatching {
            ApplicationBootstrapResolver.resolve(spec, loader)
        }.onFailure {
            Log.w(TAG, "Application bootstrap detection failed", it)
        }.getOrDefault(ApplicationBootstrap.ManifestApplication)

        return when (bootstrap) {
            is ApplicationBootstrap.EmbeddedApplication -> runCatching {
                attachRegularApplication(
                    spec,
                    loader,
                    containerContext,
                    bootstrap.className
                )
            }.onFailure {
                Log.w(TAG, "embedded Application init failed: ${bootstrap.className}", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported hardened Application: ${bootstrap.className}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.TinkerApplication -> runCatching {
                attachTinkerApplication(bootstrap, containerContext)
            }.onFailure {
                Log.w(TAG, "Tinker Application init failed", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported Tinker Application: ${bootstrap.wrapperClass.name}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.ApplicationLikePair -> runCatching {
                attachApplicationLikePair(bootstrap, containerContext)
            }.onFailure {
                Log.w(TAG, "ApplicationLike pair attach failed", it)
            }.getOrElse {
                if (bootstrap.allowManifestFallback) {
                    attachRegularApplication(spec, loader, containerContext)
                } else {
                    throw IllegalStateException(
                        "unsupported ApplicationLike pair: " +
                            "${bootstrap.likeClass.name} -> ${bootstrap.realClass.name}",
                        it
                    )
                }
            }
            is ApplicationBootstrap.UnsupportedHardenedApplication ->
                error(
                    "unsupported hardened Application ${bootstrap.className}: " +
                        bootstrap.reason
                )
            ApplicationBootstrap.ManifestApplication ->
                attachRegularApplication(spec, loader, containerContext)
        }
    }

    /**
     * MuteApplication forwards lifecycle callbacks to its delegate. Keep the
     * manifest wrapper as the process Application so framework-only fields
     * copied by Android remain available to startup tasks.
     */
    private fun attachTinkerApplication(
        bootstrap: ApplicationBootstrap.TinkerApplication,
        context: Context
    ): Application {
        val wrapper = bootstrap.wrapperClass.getDeclaredConstructor().newInstance()
        if (context is ContainerContext) {
            context.targetApplication = wrapper
        }
        attachBaseContext(wrapper, context)
        val delegate = findTinkerDelegate(wrapper)
            ?: error("Tinker delegate Application was not created")
        Log.d(TAG, "Tinker delegate attached: ${delegate.javaClass.name}")
        return wrapper
    }

    private fun findTinkerDelegate(wrapper: Application): Application? {
        var current: Class<*>? = wrapper.javaClass
        while (current != null) {
            val fields = current.declaredFields
                .filter { Application::class.java.isAssignableFrom(it.type) }
                .sortedByDescending {
                    it.name.contains("delegate", ignoreCase = true)
                }
            fields.forEach { field ->
                val delegate = runCatching {
                    field.isAccessible = true
                    field.get(wrapper) as? Application
                }.getOrNull()
                if (delegate != null && delegate !== wrapper) return delegate
            }
            current = current.superclass
        }
        return null
    }

    private fun attachApplicationLikePair(
        bootstrap: ApplicationBootstrap.ApplicationLikePair,
        context: Context
    ): Application {
        val base = Application()
        if (context is ContainerContext) {
            context.targetApplication = base
        }
        attachBaseContext(base, context)
        val appLike = ApplicationBootstrapResolver.createApplicationLike(
            bootstrap.likeClass,
            base
        )
        val real = ApplicationBootstrapResolver.createRealApplication(
            bootstrap.realClass,
            base,
            appLike
        )
        if (context is ContainerContext) {
            context.targetApplication = real
        }
        attachBaseContext(real, context)
        ContainerCompat.rememberAppLike(real, appLike)
        Log.d(TAG, "embedded application attached: ${real.javaClass.name}")
        return real
    }

    private fun attachRegularApplication(
        spec: RuntimeApplicationSpec,
        loader: ClassLoader,
        context: Context,
        classNameOverride: String? = null
    ): Application {
        val className = classNameOverride
            ?: spec.applicationInfo.className?.takeIf { it.isNotBlank() }
            ?: Application::class.java.name
        val appClass = loader.loadClass(className).asSubclass(Application::class.java)
        val application = appClass.getDeclaredConstructor().newInstance()
        if (context is ContainerContext) {
            context.targetApplication = application
        }
        attachBaseContext(application, context)
        return application
    }

    private fun attachBaseContext(application: Application, context: Context) {
        var type: Class<*>? = application.javaClass
        while (type != null) {
            val attachBase = runCatching {
                type.getDeclaredMethod("attachBaseContext", Context::class.java)
            }.getOrNull()
            if (attachBase != null) {
                attachBase.isAccessible = true
                attachBase.invoke(application, context)
                return
            }
            type = type.superclass
        }
        error("Application.attachBaseContext not found for ${application.javaClass.name}")
    }
}
