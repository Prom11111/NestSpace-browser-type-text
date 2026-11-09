package com.coke.nestspace.container.activity

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the hidden ActivityThread perform* lifecycle methods and the public
 * Instrumentation helpers. Calling only the public onStart/onResume/onStop
 * callbacks skips FragmentManager and Application.ActivityLifecycleCallbacks.
 */
internal object TargetLifecycle {

    private const val TAG = "TargetLifecycle"
    private val methods = ConcurrentHashMap<MethodKey, Optional<Method>>()
    private val nullArgumentType = Any::class.java

    private data class MethodKey(
        val purpose: String,
        val owner: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>
    )

    fun perform(target: Activity, name: String, vararg candidates: Array<Any?>): Boolean {
        for (args in candidates) {
            val method = cachedMethod(
                MethodKey(
                    purpose = "perform",
                    owner = Activity::class.java,
                    name = name,
                    parameterTypes = args.map { it?.javaClass ?: nullArgumentType }
                )
            ) {
                Activity::class.java.declaredMethods.firstOrNull { candidate ->
                    candidate.name == name &&
                        candidate.parameterTypes.size == args.size &&
                        candidate.parameterTypes.indices.all { index ->
                            val type = candidate.parameterTypes[index]
                            when {
                                type == java.lang.Boolean.TYPE -> args[index] is Boolean
                                type == java.lang.Integer.TYPE -> args[index] is Int
                                type == java.lang.Long.TYPE -> args[index] is Long
                                else -> args[index] == null || type.isInstance(args[index])
                            }
                        }
                }
            } ?: continue
            return try {
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
        val method = cachedMethod(
            MethodKey(
                purpose = "postCreate",
                owner = Instrumentation::class.java,
                name = "callActivityOnPostCreate",
                parameterTypes = listOf(Activity::class.java, Bundle::class.java)
            )
        ) {
            Instrumentation::class.java.getDeclaredMethod(
                "callActivityOnPostCreate",
                Activity::class.java,
                Bundle::class.java
            )
        }
        if (method == null) {
            postCreateFallback(target, state)
            return
        }
        // Method.invoke returns null for a void method. Do not treat that as a
        // resolution failure and retry the target callback. Activities that
        // install a view wrapper in onPostCreate can create a parent cycle
        // when that callback runs twice.
        runCatching {
            method.invoke(instrumentation, target, state)
        }.onFailure {
            Log.w(TAG, "onPostCreate failed", it)
        }
    }

    fun postResume(target: Activity) {
        val method = cachedMethod(
            MethodKey(
                purpose = "postResume",
                owner = target.javaClass,
                name = "onPostResume",
                parameterTypes = emptyList()
            )
        ) {
            findDeclaredInHierarchy(
                target.javaClass,
                "onPostResume",
                emptyArray()
            )
        }
            ?: error("Activity.onPostResume unavailable")
        method.invoke(target)
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
        val method = cachedMethod(
            MethodKey(
                purpose = "restore",
                owner = target.javaClass,
                name = "onRestoreInstanceState",
                parameterTypes = listOf(Bundle::class.java)
            )
        ) {
            findDeclaredInHierarchy(
                target.javaClass,
                "onRestoreInstanceState",
                arrayOf(Bundle::class.java)
            )
        } ?: return
        method.invoke(target, state)
    }

    private fun postCreateFallback(target: Activity, state: Bundle?) {
        val method = cachedMethod(
            MethodKey(
                purpose = "postCreateFallback",
                owner = target.javaClass,
                name = "onPostCreate",
                parameterTypes = listOf(Bundle::class.java)
            )
        ) {
            findDeclaredInHierarchy(
                target.javaClass,
                "onPostCreate",
                arrayOf(Bundle::class.java)
            )
        } ?: return
        method.invoke(target, state)
    }

    private fun findDeclaredInHierarchy(
        owner: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>
    ): Method? {
        var cls: Class<*>? = owner
        while (cls != null) {
            // Class.getDeclaredMethods() resolves every signature in the
            // class. Vendor apps can ship Activity subclasses that reference
            // optional SDK types absent from this APK, which turns a simple
            // lifecycle lookup into NoClassDefFoundError. Exact signature
            // lookup avoids resolving unrelated methods.
            val method = runCatching {
                cls.getDeclaredMethod(name, *parameterTypes)
            }.getOrNull()
            if (method != null) return method
            cls = cls.superclass
        }
        return null
    }

    private fun cachedMethod(
        key: MethodKey,
        resolver: () -> Method?
    ): Method? = methods.computeIfAbsent(key) {
        Optional.ofNullable(
            resolver()?.apply { isAccessible = true }
        )
    }.orElse(null)
}
