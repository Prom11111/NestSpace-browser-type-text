package com.coke.nestspace.container.loader

import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.UUID
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

    private const val TAG = "NativeLibs"
    private const val READY = ".ready"

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
        cacheRoot: File,
        excludedNames: Set<String> = emptySet()
    ): File? {
        val abi = supportedAbi(abis) ?: return null
        return extractAbiIfNeeded(apkPaths, abi, cacheRoot, excludedNames)
    }

    /**
     * Internal ABI-specific entry point that can be unit tested without
     * depending on the host device's supported ABI list.
     */
    internal fun extractAbiIfNeeded(
        apkPaths: List<String>,
        abi: String,
        cacheRoot: File,
        excludedNames: Set<String> = emptySet()
    ): File? {
        if (!cacheRoot.mkdirs() && !cacheRoot.isDirectory) return null
        val fingerprint = fingerprint(apkPaths, abi, excludedNames)
        val targetDir = File(cacheRoot, fingerprint)
        val marker = File(targetDir, READY)
        if (marker.isFile && hasLibraries(targetDir)) {
            lockDown(targetDir)
            return targetDir
        }

        // Build in a process-unique directory. Two instances can cold-start at
        // the same time without observing a partially extracted cache.
        val tempDir = File(
            cacheRoot,
            ".build-$fingerprint-${UUID.randomUUID()}"
        )
        tempDir.deleteRecursively()
        if (!tempDir.mkdirs()) return null

        var extracted = false
        try {
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
                            // Flatten to one directory; the loader searches by
                            // file name. Later splits intentionally win if a
                            // file name occurs in more than one APK.
                            val name = entry.name.substringAfterLast('/')
                            if (name.isBlank() || name in excludedNames) continue
                            val out = File(tempDir, name)
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
                }.onFailure { Log.w(TAG, "read native libs from $apkPath failed", it) }
            }
            if (!extracted) {
                tempDir.deleteRecursively()
                return null
            }
            lockDown(tempDir)
            marker(tempDir).writeText("ready")

            if (tempDir.renameTo(targetDir)) {
                lockDown(targetDir)
                return targetDir
            }

            // Another process may have completed the same cache entry first.
            tempDir.deleteRecursively()
            if (File(targetDir, READY).isFile && hasLibraries(targetDir)) {
                lockDown(targetDir)
                return targetDir
            }
            return null
        } catch (t: Throwable) {
            tempDir.deleteRecursively()
            throw t
        }
    }

    private fun hasLibraries(dir: File): Boolean =
        dir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true

    private fun fingerprint(
        apkPaths: List<String>,
        abi: String,
        excludedNames: Set<String>
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(abi.toByteArray())
        digest.update(0)
        excludedNames.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update(0)
        }
        apkPaths.forEach { path ->
            val file = File(path)
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(file.length().toString().toByteArray())
            digest.update(0)
            digest.update(file.lastModified().toString().toByteArray())
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun marker(dir: File): File = File(dir, READY)

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
