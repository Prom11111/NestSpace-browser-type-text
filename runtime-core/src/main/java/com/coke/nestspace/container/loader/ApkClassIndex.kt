package com.coke.nestspace.container.loader

import android.util.Log
import dalvik.system.DexFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide class-name index for an installed APK and its splits.
 *
 * Compatibility resolution and AppComponentFactory fallback both need the
 * same class list. Enumerating every DEX twice on the launch path is both
 * unnecessarily expensive and avoidable because the installed APK paths are
 * immutable for the lifetime of a slot process.
 */
@Suppress("DEPRECATION")
object ApkClassIndex {

    private const val TAG = "ApkClassIndex"
    private val cache = ConcurrentHashMap<String, List<String>>()

    fun classNames(apkPaths: List<String>): List<String> {
        val key = apkPaths.joinToString(separator = "\u0000")
        cache[key]?.let { return it }
        val names = buildList {
            apkPaths.forEach { path ->
                val dex = runCatching { DexFile(path) }.getOrElse {
                    Log.w(TAG, "open dex failed: $path", it)
                    return@forEach
                }
                try {
                    addAll(dex.entries().asSequence())
                } catch (t: Throwable) {
                    Log.w(TAG, "enumerate dex failed: $path", t)
                } finally {
                    runCatching { dex.close() }
                }
            }
        }.distinct()
        return cache.putIfAbsent(key, names) ?: names
    }
}
