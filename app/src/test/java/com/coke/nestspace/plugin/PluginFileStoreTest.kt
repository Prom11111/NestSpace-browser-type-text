package com.coke.nestspace.plugin

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutionException
import java.nio.file.Files

class PluginFileStoreTest {

    private lateinit var root: File
    private lateinit var storage: PluginFileStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nestspace-plugin-storage").toFile()
        storage = PluginFileStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun writesReadsAndListsText() {
        storage.writeText("notes/example.txt", "first").get()

        assertEquals("first", storage.readText("notes/example.txt", 32).get())
        assertEquals(
            "notes/example.txt",
            storage.list("").get().single().path
        )
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun rejectsAbsoluteAndParentTraversalPaths() {
        assertFailure<IllegalArgumentException> {
            storage.writeText("/outside.txt", "blocked").get()
        }
        assertFailure<IllegalArgumentException> {
            storage.writeText("../outside.txt", "blocked").get()
        }
        assertFailure<IllegalArgumentException> {
            storage.writeText("nested\\..\\outside.txt", "blocked").get()
        }
        assertFalse(File(root.parentFile, "outside.txt").exists())
    }

    @Test
    fun refusesReadsAboveRequestedLimit() {
        storage.writeBytes("large.bin", ByteArray(32) { it.toByte() }).get()

        assertFailure<IllegalArgumentException> {
            storage.readBytes("large.bin", 16).get()
        }
        assertArrayEquals(
            ByteArray(32) { it.toByte() },
            storage.readBytes("large.bin", 32).get()
        )
    }

    @Test
    fun deleteRemovesOnlyFilesInsidePluginStorage() {
        storage.writeText("remove.txt", "value").get()
        storage.delete("remove.txt").get()

        assertFalse(File(root, "remove.txt").exists())
        assertFailure<IllegalArgumentException> {
            storage.delete("../outside.txt").get()
        }
    }

    @Test
    fun pluginStorageNamespaceCannotCollideAfterSanitizingIds() {
        val first = PluginFileStore.storageNamespace("example/plugin")
        val second = PluginFileStore.storageNamespace("example_plugin")

        assertEquals(64, first.length)
        assertEquals(first, PluginFileStore.storageNamespace("example/plugin"))
        assertFalse(first == second)
    }

    private inline fun <reified T : Throwable> assertFailure(
        noinline block: () -> Unit
    ) {
        val error = assertThrows(ExecutionException::class.java, block)
        check(error.cause is T) {
            "expected ${T::class.java.simpleName}, got ${error.cause}"
        }
    }
}
