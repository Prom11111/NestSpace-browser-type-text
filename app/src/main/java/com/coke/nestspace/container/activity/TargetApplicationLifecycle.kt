package com.coke.nestspace.container.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Forwards target Activity lifecycle events to a wrapped Application.
 *
 * Tinker-style apps install a wrapper Application whose lifecycle callbacks
 * are delegated to an inner Application instance. The framework dispatches
 * Activity lifecycle only to the wrapper (`Activity.getApplication()`), while
 * `registerActivityLifecycleCallbacks` was forwarded to the inner instance.
 * The inner Application therefore never sees an Activity and keeps reporting
 * the process as backgrounded, which disables playback timers and other
 * foreground-only work.
 *
 * Regular apps have no Application-typed delegate field, so this is a no-op
 * for them and the framework's normal dispatch remains in charge.
 */
internal object TargetApplicationLifecycle {

    private const val TAG = "TargetAppLifecycle"
    private const val MAX_DEPTH = 3
    private val delegateFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val callbackMethods = ConcurrentHashMap<String, Optional<Method>>()
    private val callbacksField: Field? by lazy {
        runCatching {
            Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
                .apply { isAccessible = true }
        }.getOrNull()
    }
    private val callbackInterface: Class<*>? by lazy {
        runCatching {
            Class.forName("android.app.Application\$ActivityLifecycleCallbacks")
        }.getOrNull()
    }

    fun dispatch(
        application: Application,
        event: String,
        activity: Activity,
        bundle: Bundle? = null
    ) {
        val visited = HashSet<Application>()
        var current: Application? = application
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            visited += current
            val delegate = findDelegate(current)
            if (delegate == null || delegate in visited) return
            invoke(delegate, event, activity, bundle)
            current = delegate
            depth++
        }
    }

    private fun findDelegate(application: Application): Application? {
        for (field in delegateFieldList(application.javaClass)) {
            val value = runCatching {
                field.get(application) as? Application
            }.getOrNull()
            if (value != null && value !== application) return value
        }
        return null
    }

    private fun delegateFieldList(type: Class<*>): List<Field> =
        delegateFields.computeIfAbsent(type) {
            buildList {
                var cls: Class<*>? = type
                while (cls != null && cls != Application::class.java) {
                    cls.declaredFields.forEach { field ->
                        if (Application::class.java.isAssignableFrom(field.type)) {
                            runCatching {
                                field.isAccessible = true
                                add(field)
                            }
                        }
                    }
                    cls = cls.superclass
                }
            }
        }

    private fun invoke(
        application: Application,
        event: String,
        activity: Activity,
        bundle: Bundle?
    ) {
        val callbackName = callbackMethodName(event) ?: return
        val callbacks = runCatching {
            (callbacksField
                ?: error("ActivityLifecycleCallbacks field unavailable"))
                .get(application) as? Collection<*>
        }.getOrNull()
            ?.let { values -> runCatching { values.toList() }.getOrNull() }
            ?: return
        val hasBundle = callbackName == "onActivityCreated" ||
            callbackName == "onActivitySaveInstanceState"
        val iface = callbackInterface ?: return
        callbacks.forEach { callback ->
            if (callback == null) return@forEach
            runCatching {
                val method = callbackMethods.computeIfAbsent(
                    if (hasBundle) "$callbackName:bundle" else "$callbackName"
                ) {
                    Optional.ofNullable(
                        if (hasBundle) {
                            iface.getMethod(
                                callbackName,
                                Activity::class.java,
                                Bundle::class.java
                            )
                        } else {
                            iface.getMethod(callbackName, Activity::class.java)
                        }
                    )
                }.orElse(null)
                    ?: error("$callbackName unavailable")
                if (hasBundle) {
                    method.invoke(callback, activity, bundle)
                } else {
                    method.invoke(callback, activity)
                }
            }.onFailure {
                Log.w(TAG, "$callbackName failed on ${callback.javaClass.name}", it)
            }
        }
    }

    private fun callbackMethodName(event: String): String? = when (event) {
        "dispatchActivityCreated" -> "onActivityCreated"
        "dispatchActivityStarted" -> "onActivityStarted"
        "dispatchActivityResumed" -> "onActivityResumed"
        "dispatchActivityPaused" -> "onActivityPaused"
        "dispatchActivityStopped" -> "onActivityStopped"
        "dispatchActivitySaveInstanceState" -> "onActivitySaveInstanceState"
        "dispatchActivityDestroyed" -> "onActivityDestroyed"
        else -> null
    }
}
