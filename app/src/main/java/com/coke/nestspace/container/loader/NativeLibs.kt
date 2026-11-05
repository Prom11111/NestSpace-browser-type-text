package com.coke.nestspace.container.loader

import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Native-library support for containerized APKs.
 *
 * Stage B used to pass `null` as DexClassLoader#librarySearchPath, so any app
 * with JNI libraries crashed in Application.onCreate. We now extract the ABI
 * directory that matches the device into the instance data dir (cached) and
 * hand that path to the class loader.
 *
 * ABI compatibility is checked before launch: an arm64-only APK cannot run on
 * an x86_64 emulator, and the user should get a clear error instead of an
 * UnsatisfiedLinkError from deep inside the target Application.
 */
object NativeLibs {

    fun supportedAbi(abis: Set<String>): String? =
        Build.SUPPORTED_ABIS.firstOrNull { it in abis }

    fun abisOf(apkPaths: List<String>): Set<String> =
        apkPaths.flatMap { ApkInspector.nativeAbis(it) }.toSet()

    /**
     * Extracts native `.so` files under lib/<abi>/ from the base + split APKs
     * into [targetDir].
     * Returns null when the APK has no libs or no ABI matches this device.
     */
    fun extractIfNeeded(
        apkPaths: List<String>,
        abis: Set<String>,
        targetDir: File,
        excludedNames: Set<String> = emptySet()
    ): File? {
        val abi = supportedAbi(abis) ?: return null
        val stamp = abi + "|" + apkPaths.joinToString("|") + "|skip=" +
            excludedNames.sorted().joinToString(",")
        val marker = File(targetDir, ".stamp")
        if (marker.isFile && runCatching { marker.readText() }.getOrNull() == stamp) {
            lockDown(targetDir)
            return targetDir.takeIf { hasLibraries(it) }
        }

        targetDir.deleteRecursively()
        if (!targetDir.mkdirs()) return null

        var extracted = false
        apkPaths.forEach { apkPath ->
            runCatching {
                ZipFile(File(apkPath)).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (!entry.name.startsWith("lib/$abi/") ||
                            !entry.name.endsWith(".so")
                        ) {
                            continue
                        }
                        // Flatten to one directory; the loader searches by file name.
                        val name = entry.name.substringAfterLast('/')
                        if (name.isBlank() || name in excludedNames) continue
                        val out = File(targetDir, name)
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        out.setReadable(true, false)
                        out.setExecutable(true, false)
                        // Android 16+ refuses to dlopen writable files.
                        out.setWritable(false, false)
                        extracted = true
                    }
                }
            }
        }

        if (!extracted) return null
        runCatching { marker.writeText(stamp) }
        return targetDir
    }

    private fun hasLibraries(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true

    private fun lockDown(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".so")) {
                runCatching {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                    file.setWritable(false, false)
                }
            }
        }
    }
}
