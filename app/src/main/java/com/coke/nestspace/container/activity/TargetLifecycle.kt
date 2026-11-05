package com.coke.nestspace.container.activity

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Bridges the hidden ActivityThread perform* lifecycle methods and the public
 * Instrumentation helpers. Calling only the public onStart/onResume/onStop
 * callbacks skips FragmentManager and Application.ActivityLifecycleCallbacks.
 */
internal object TargetLifecycle {

    private const val TAG = "TargetLifecycle"

    fun perform(target: Activity, name: String, vararg candidates: Array<Any?>): Boolean {
        for (args in candidates) {
            val method = Activity::class.java.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == args.size &&
                    method.parameterTypes.indices.all { index ->
                        val type = method.parameterTypes[index]
                        when {
                            type == java.lang.Boolean.TYPE -> args[index] is Boolean
                            type == java.lang.Integer.TYPE -> args[index] is Int
                            type == java.lang.Long.TYPE -> args[index] is Long
                            else -> args[index] == null || type.isInstance(args[index])
                        }
                    }
            } ?: continue
            return try {
                method.isAccessible = true
                method.invoke(target, *args)
                Log.d(TAG, "$name via ${method.parameterTypes.joinToString { it.simpleName }}")
                true
            } catch (t: InvocationTargetException) {
                Log.w(TAG, "$name failed", t.targetException)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "$name invoke failed", t)
                true
            }
        }
        return false
    }

    fun restore(target: Activity, instrumentation: Instrumentation?, state: Bundle) {
        if (instrumentation == null) return
        runCatching {
            instrumentation.callActivityOnRestoreInstanceState(target, state)
        }.onFailure {
            Log.w(TAG, "onRestoreInstanceState failed", it)
            restoreFallback(target, state)
        }
    }

    fun postCreate(target: Activity, instrumentation: Instrumentation?, state: Bundle?) {
        if (instrumentation == null) return
        runCatching {
            Instrumentation::class.java
                .getDeclaredMethod(
                    "callActivityOnPostCreate",
                    Activity::class.java,
                    Bundle::class.java
                )
                .apply { isAccessible = true }
                .invoke(instrumentation, target, state)
        }.onFailure {
            Log.w(TAG, "onPostCreate failed", it)
            postCreateFallback(target, state)
        }
    }

    fun postResume(target: Activity) {
        var cls: Class<*>? = target.javaClass
        while (cls != null && cls != Activity::class.java) {
            val method = runCatching { cls!!.getDeclaredMethod("onPostResume") }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(target)
                return
            }
            cls = cls.superclass
        }
        Activity::class.java
            .getDeclaredMethod("onPostResume")
            .apply { isAccessible = true }
            .invoke(target)
    }

    fun attach(target: Activity) {
        runCatching { target.onAttachedToWindow() }
            .onFailure { Log.w(TAG, "onAttachedToWindow failed", it) }
    }

    fun detach(target: Activity) {
        runCatching { target.onDetachedFromWindow() }
            .onFailure { Log.w(TAG, "onDetachedFromWindow failed", it) }
    }

    private fun restoreFallback(target: Activity, state: Bundle) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val method = runCatching {
                cls!!.getDeclaredMethod("onRestoreInstanceState", Bundle::class.java)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(target, state)
                return
            }
            cls = cls.superclass
        }
    }

    private fun postCreateFallback(target: Activity, state: Bundle?) {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val method = runCatching {
                cls!!.getDeclaredMethod("onPostCreate", Bundle::class.java)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(target, state)
                return
            }
            cls = cls.superclass
        }
    }
}
