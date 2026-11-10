package com.coke.nestspace.container.loader

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLibsTest {

    @Test
    fun preferredHostAbiWinsOverDeviceAbiOrder() {
        assertEquals(
            "arm64-v8a",
            NativeLibs.chooseAbi(
                setOf("x86_64", "arm64-v8a"),
                "arm64-v8a",
                listOf("x86_64", "arm64-v8a")
            )
        )
        assertEquals(
            "x86_64",
            NativeLibs.chooseAbi(
                setOf("x86_64"),
                "arm64-v8a",
                listOf("x86_64", "arm64-v8a")
            )
        )
    }

    @Test
    fun sharedCacheIsReusedInvalidatedAndHonorsExclusions() {
        val root = Files.createTempDirectory("nestspace-native-libs").toFile()
        try {
            val apk = File(root, "base.apk")
            writeApk(apk)
            val cache = File(root, "cache")

            val first = NativeLibs.extractAbiIfNeeded(
                listOf(apk.absolutePath),
                "arm64-v8a",
                cache,
                setOf("libskip.so")
            ) ?: error("first extraction failed")
            assertTrue(File(first, "libdemo.so").isFile)
            assertFalse(File(first, "libskip.so").exists())

            val sentinel = File(first, "sentinel").apply { writeText("reused") }
            val second = NativeLibs.extractAbiIfNeeded(
                listOf(apk.absolutePath),
                "arm64-v8a",
                cache,
                setOf("libskip.so")
            ) ?: error("cache reuse failed")
            assertEquals(first.canonicalPath, second.canonicalPath)
            assertTrue(sentinel.isFile)

            // An interrupted build can leave a directory without .ready.
            File(first, ".ready").delete()
            val recovered = NativeLibs.extractAbiIfNeeded(
                listOf(apk.absolutePath),
                "arm64-v8a",
                cache,
                setOf("libskip.so")
            ) ?: error("incomplete cache recovery failed")
            assertTrue(File(recovered, "libdemo.so").isFile)

            writeApk(apk, includeSecondLibrary = true)
            apk.setLastModified(apk.lastModified() + 5_000L)
            val updated = NativeLibs.extractAbiIfNeeded(
                listOf(apk.absolutePath),
                "arm64-v8a",
                cache,
                setOf("libskip.so")
            ) ?: error("updated APK extraction failed")
            assertNotEquals(first.canonicalPath, updated.canonicalPath)
            assertTrue(File(updated, "libextra.so").isFile)
        } finally {
            restoreAndDelete(root)
        }
    }

    private fun writeApk(file: File, includeSecondLibrary: Boolean = false) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libdemo.so"))
            zip.write(byteArrayOf(1, 2, 3, 4))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libskip.so"))
            zip.write(byteArrayOf(9, 9, 9))
            zip.closeEntry()

            if (includeSecondLibrary) {
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libextra.so"))
                zip.write(byteArrayOf(5, 6, 7, 8))
                zip.closeEntry()
            }
        }
    }

    private fun restoreAndDelete(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach(::restoreAndDelete)
        }
        file.setWritable(true)
        file.delete()
    }
}
