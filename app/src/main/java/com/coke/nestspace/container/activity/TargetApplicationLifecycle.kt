package com.coke.nestspace.container.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log

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
        var cls: Class<*>? = application.javaClass
        while (cls != null && cls != Application::class.java) {
            for (field in cls.declaredFields) {
                if (!Application::class.java.isAssignableFrom(field.type)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(application) as? Application
                }.getOrNull()
                if (value != null && value !== application) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun invoke(
        application: Application,
        event: String,
        activity: Activity,
        bundle: Bundle?
    ) {
        val callbackName = callbackMethodName(event) ?: return
        val callbacks = runCatching {
            Application::class.java
                .getDeclaredField("mActivityLifecycleCallbacks")
                .apply { isAccessible = true }
                .get(application) as? Collection<*>
        }.getOrNull() ?: return
        val hasBundle = callbackName == "onActivityCreated" ||
            callbackName == "onActivitySaveInstanceState"
        val callbackInterface = Class.forName(
            "android.app.Application\$ActivityLifecycleCallbacks"
        )
        callbacks.forEach { callback ->
            if (callback == null) return@forEach
            runCatching {
                val method = if (hasBundle) {
                    callbackInterface.getMethod(
                        callbackName,
                        Activity::class.java,
                        Bundle::class.java
                    )
                } else {
                    callbackInterface.getMethod(callbackName, Activity::class.java)
                }
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
