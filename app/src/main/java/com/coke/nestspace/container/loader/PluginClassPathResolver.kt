package com.coke.nestspace.container.loader

import android.util.Log
import dalvik.system.DexFile
import java.io.File

/**
 * Explicit subprocess components can be declared in a downloaded plugin APK
 * rather than the base APK. Resolve only the plugin that actually contains the
 * requested class; mounting every installed plugin would duplicate libraries
 * and expose unrelated classes to the target process.
 */
object PluginClassPathResolver {
    private const val TAG = "PluginClassPath"

    data class Mount(
        val apkPaths: List<String>,
        val nativeLibraryDirs: List<String>
    ) {
        val mounted: Boolean get() = apkPaths.isNotEmpty()

        companion object {
            val EMPTY = Mount(emptyList(), emptyList())
        }
    }

    fun resolveForComponent(
        dataRoot: File,
        className: String,
        supportedAbis: List<String> = emptyList()
    ): Mount {
        if (className.isBlank()) return Mount.EMPTY
        val pluginRoot = File(dataRoot, "files/plugins")
        if (!pluginRoot.isDirectory) return Mount.EMPTY
        val candidates = pluginRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .toList()
        val match = candidates.firstOrNull { apkContains(apk = it, className = className) }
        if (match == null) {
            Log.d(TAG, "plugin component not found class=$className candidates=${candidates.size}")
            return Mount.EMPTY
        }
        val versionRoot = match.parentFile?.parentFile
        val libRoot = versionRoot?.let { File(it, "lib") }
        val nativeDirs = nativeLibraryDirs(libRoot, supportedAbis)
        Log.d(
            TAG,
            "plugin component resolved class=$className apk=${match.name} " +
                "nativeDirs=${nativeDirs.size}"
        )
        return Mount(
            apkPaths = listOf(match.absolutePath),
            nativeLibraryDirs = nativeDirs
        )
    }

    internal fun choose(
        candidates: List<File>,
        contains: (File, String) -> Boolean,
        className: String
    ): File? = candidates.firstOrNull { contains(it, className) }

    internal fun nativeLibraryDirs(
        libRoot: File?,
        supportedAbis: List<String>
    ): List<String> {
        if (libRoot == null || !libRoot.isDirectory) return emptyList()
        val candidates = buildList {
            if (hasNativeLibraries(libRoot)) add(libRoot)
            supportedAbis.forEach { abi ->
                File(libRoot, abi).takeIf(::hasNativeLibraries)?.let(::add)
            }
        }
        return candidates.map { it.absolutePath }.distinct()
    }

    private fun hasNativeLibraries(dir: File): Boolean = dir.isDirectory &&
        dir.listFiles()?.any {
            it.isFile && it.extension.equals("so", ignoreCase = true) && it.canRead()
        } == true

    private fun apkContains(apk: File, className: String): Boolean {
        if (apk.length() <= 0L) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val dex = DexFile(apk.absolutePath)
            try {
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement() == className) return true
                }
                false
            } finally {
                dex.close()
            }
        }.onFailure {
            Log.w(TAG, "plugin dex inspect failed apk=${apk.name}", it)
        }.getOrDefault(false)
    }
}
