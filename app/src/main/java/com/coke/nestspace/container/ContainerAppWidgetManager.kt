package com.coke.nestspace.container

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy

/**
 * Virtual apps run under the host UID, but AppWidgetManager binds widgets to
 * the calling package. The system service rejects a target package it cannot
 * associate with the host UID ("Package X does not belong to <hostUid>"), and
 * apps that install widgets (AMap, for example) hit that from a background
 * thread and crash.
 *
 * Widgets are a launcher-level feature that a virtual app cannot meaningfully
 * own anyway. Give each target context an AppWidgetManager backed by a no-op
 * IAppWidgetService proxy: queries report no widgets, updates are ignored, and
 * nothing crosses into the host's real widget state.
 */
object ContainerAppWidgetManager {

    private const val TAG = "ContainerAppWidget"

    fun create(context: Context): Any? {
        val manager = runCatching {
            val managerClass = Class.forName("android.appwidget.AppWidgetManager")
            val serviceClass = Class.forName("com.android.internal.appwidget.IAppWidgetService")
            val loader = serviceClass.classLoader
                ?: ContainerAppWidgetManager::class.java.classLoader
            val service = Proxy.newProxyInstance(
                loader,
                arrayOf(serviceClass)
            ) { proxy, method, args ->
                when {
                    method.declaringClass == Any::class.java -> when (method.name) {
                        "toString" -> "ContainerAppWidgetService"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.getOrNull(0)
                        else -> null
                    }
                    else -> {
                        Log.v(TAG, "IAppWidgetService.${method.name} -> default")
                        defaultValue(method.returnType)
                    }
                }
            }
            managerClass
                .getDeclaredConstructor(Context::class.java, serviceClass)
                .apply { isAccessible = true }
                .newInstance(context, service)
        }.onFailure {
            Log.w(TAG, "sandboxed AppWidgetManager unavailable, using host service", it)
        }.getOrNull()
        if (manager != null) {
            Log.d(TAG, "sandboxed AppWidgetManager ready for ${context.packageName}")
        }
        return manager
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> when {
            type.isArray -> java.lang.reflect.Array.newInstance(type.componentType, 0)
            type == java.util.List::class.java -> emptyList<Any>()
            type == java.util.Set::class.java -> emptySet<Any>()
            type == java.util.Map::class.java -> emptyMap<Any, Any>()
            type == CharSequence::class.java -> ""
            type == String::class.java -> ""
            else -> null
        }
    }
}
