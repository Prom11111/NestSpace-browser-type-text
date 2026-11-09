package com.coke.nestspace.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPayloadIdentityTest {
    @Test
    fun acceptsSamePayloadRegardlessOfOrdering() {
        val root = Files.createTempDirectory("apk-payload").toFile()
        try {
            val first = write(root, "first.apk", "first")
            val second = write(root, "second.apk", "second")
            assertTrue(ApkPayloadIdentity.same(listOf(first, second), listOf(second, first)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsChangedOrMissingPayload() {
        val root = Files.createTempDirectory("apk-payload").toFile()
        try {
            val original = write(root, "original.apk", "original")
            val changed = write(root, "changed.apk", "changed")
            assertFalse(ApkPayloadIdentity.same(listOf(changed), listOf(original)))
            assertFalse(ApkPayloadIdentity.same(listOf(original), emptyList()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(root: File, name: String, value: String): File =
        File(root, name).apply { writeText(value) }
}
