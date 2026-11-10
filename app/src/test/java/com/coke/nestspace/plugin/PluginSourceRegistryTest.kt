package com.coke.nestspace.plugin

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PluginSourceRegistryTest {

    @After
    fun cleanup() {
        PluginSourceRegistry.clear()
    }

    @Test
    fun disabledSourcePluginKeepsManifestWithoutRuntimeAttachment() {
        val directory = Files.createTempDirectory(
            "nestspace-disabled-source"
        ).toFile()
        try {
            val manifest = PluginManifestParser.parse(
                """
                {
                  "manifest_version": 1,
                  "id": "test.disabled.source",
                  "name": "Disabled source",
                  "version": "1.0.0",
                  "api_version": 2,
                  "entry_class": "test.DisabledPlugin",
                  "permissions": []
                }
                """.trimIndent()
            )

            PluginSourceRegistry.register(
                SourcePluginRecord(
                    manifest = manifest,
                    directory = directory,
                    plugin = null,
                    host = null
                )
            )

            assertTrue(PluginSourceRegistry.contains(manifest.id))
            assertFalse(PluginSourceRegistry.isLoaded(manifest.id))
            assertEquals(
                listOf(manifest.id),
                PluginSourceRegistry.manifests().map { it.id }
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
