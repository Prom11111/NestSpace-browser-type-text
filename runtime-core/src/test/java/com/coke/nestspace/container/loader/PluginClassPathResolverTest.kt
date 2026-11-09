package com.coke.nestspace.container.loader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginClassPathResolverTest {
    @Test
    fun choosesFirstPluginContainingRequestedClass() {
        val first = File("/plugins/first.apk")
        val second = File("/plugins/second.apk")

        assertEquals(
            second,
            PluginClassPathResolver.choose(
                listOf(first, second),
                contains = { file, className ->
                    file == second && className == "example.PluginActivity"
                },
                className = "example.PluginActivity"
            )
        )
    }

    @Test
    fun returnsNullWhenNoPluginContainsClass() {
        assertNull(
            PluginClassPathResolver.choose(
                listOf(File("/plugins/first.apk")),
                contains = { _, _ -> false },
                className = "example.Missing"
            )
        )
    }

    @Test
    fun findsFlatPluginNativeLibraryDirectory() {
        val root = java.nio.file.Files.createTempDirectory("plugin-lib").toFile()
        try {
            File(root, "libfoo.so").writeText("not-a-real-library")
            val dirs = PluginClassPathResolver.nativeLibraryDirs(
                root,
                supportedAbis = listOf("arm64-v8a")
            )
            assertEquals(listOf(root.absolutePath), dirs)
        } finally {
            root.deleteRecursively()
        }
    }
}
