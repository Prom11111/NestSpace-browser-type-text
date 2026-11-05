package com.coke.nestspace.container.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Virtual IShortcutService for one container process.
 *
 * Dynamic shortcuts published by target code are kept in this process and
 * mirrored to a small file so the main-process desktop can show them. Rate
 * limiting, pinning and icon file descriptors are intentionally local/no-op
 * because the target package is not installed for the host UID.
 */
internal class ContainerShortcutManager(
    private val context: Context,
    private val packageName: String,
    private val instanceId: String,
    private val storeRoot: File
) {
    private val lock = Any()
    private val dynamic = mutableListOf<ShortcutInfo>()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            dynamic += DynamicShortcutStore.load(storeRoot, packageName, instanceId)
                .mapNotNull { restore(it) }
        }
    }

    fun service(): Any {
        val iface = Class.forName("android.content.pm.IShortcutService")
        return Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
                    val values = args.orEmpty()
                    return when (method.name) {
                        "setDynamicShortcuts" -> {
                            replace(shortcutInfos(values.getOrNull(1)))
                            true
                        }

                        "addDynamicShortcuts" -> {
                            add(shortcutInfos(values.getOrNull(1)))
                            true
                        }

                        "updateShortcuts" -> {
                            update(shortcutInfos(values.getOrNull(1)))
                            true
                        }

                        "removeDynamicShortcuts" -> {
                            remove((values.getOrNull(1) as? List<*>)?.filterIsInstance<String>().orEmpty())
                            null
                        }

                        "removeAllDynamicShortcuts" -> {
                            replace(emptyList())
                            null
                        }

                        "pushDynamicShortcut" -> {
                            (values.getOrNull(1) as? ShortcutInfo)?.let { add(listOf(it)) }
                            null
                        }

                        "getDynamicShortcuts", "getShortcuts", "getPinnedShortcuts" ->
                            slice(snapshot())

                        "getManifestShortcuts" -> slice(emptyList())
                        "getShortcutIconFd", "getShortcutIconUri" -> null
                        "getShortcutIconResId" -> 0
                        "getMaxShortcutCountPerActivity" -> 5
                        "getRemainingCallCount" -> Int.MAX_VALUE
                        "isRateLimitingActive" -> false
                        "hasShortcutHostPermission" -> true
                        "getIconMaxWidth", "getIconMaxHeight" -> 96
                        "requestPinShortcut" -> false
                        "createShortcutResultIntent" -> Intent()
                        else -> defaultValue(method.returnType)
                    }
                }
            }
        )
    }

    private fun snapshot(): List<ShortcutInfo> = synchronized(lock) { dynamic.toList() }

    private fun replace(items: List<ShortcutInfo>) {
        synchronized(lock) {
            dynamic.clear()
            dynamic += items
        }
        persist()
    }

    private fun add(items: List<ShortcutInfo>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.forEach { incoming ->
                val index = dynamic.indexOfFirst { it.id == incoming.id }
                if (index >= 0) dynamic[index] = incoming else dynamic += incoming
            }
        }
        persist()
    }

    private fun update(items: List<ShortcutInfo>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.forEach { incoming ->
                val index = dynamic.indexOfFirst { it.id == incoming.id }
                if (index >= 0) dynamic[index] = incoming
            }
        }
        persist()
    }

    private fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) { dynamic.removeAll { it.id in ids } }
        persist()
    }

    private fun persist() {
        val records = synchronized(lock) {
            dynamic.mapNotNull { info ->
                val intent = info.intents?.firstOrNull() ?: return@mapNotNull null
                val uri = runCatching {
                    intent.toUri(Intent.URI_INTENT_SCHEME)
                }.getOrNull() ?: return@mapNotNull null
                val label = info.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.longLabel?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.id
                DynamicShortcutStore.Record(
                    id = info.id,
                    label = label,
                    intentUri = uri,
                    targetActivity = intent.component?.className,
                    enabled = info.isEnabled,
                    rank = info.rank
                )
            }
        }
        DynamicShortcutStore.save(storeRoot, packageName, instanceId, records)
    }

    private fun restore(record: DynamicShortcutStore.Record): ShortcutInfo? = runCatching {
        val intent = Intent.parseUri(record.intentUri, Intent.URI_INTENT_SCHEME)
        ShortcutInfo.Builder(context, record.id)
            .setShortLabel(record.label)
            .setLongLabel(record.label)
            .setIntent(intent)
            .setRank(record.rank)
            .build()
    }.getOrNull()

    private fun shortcutInfos(value: Any?): List<ShortcutInfo> {
        val list = when {
            value is List<*> -> value
            value != null -> runCatching {
                value.javaClass.getMethod("getList").invoke(value) as? List<*>
            }.getOrNull()
            else -> null
        } ?: return emptyList()
        return list.filterIsInstance<ShortcutInfo>()
    }

    private fun slice(items: List<ShortcutInfo>): Any? = runCatching {
        val cls = Class.forName("android.content.pm.ParceledListSlice")
        cls.getConstructor(List::class.java).newInstance(items)
    }.onFailure { Log.w(TAG, "cannot build ParceledListSlice", it) }.getOrNull()

    private fun defaultValue(type: Class<*>): Any? = when {
        type == java.lang.Void.TYPE -> null
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Float.TYPE -> 0f
        type == java.lang.Double.TYPE -> 0.0
        type == java.lang.String::class.java -> ""
        type == List::class.java -> emptyList<Any>()
        type == Intent::class.java -> Intent()
        else -> null
    }

    private companion object {
        const val TAG = "ContainerShortcuts"
    }
}
