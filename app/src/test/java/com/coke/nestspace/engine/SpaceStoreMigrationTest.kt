package com.coke.nestspace.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpaceStoreMigrationTest {
    @Test
    fun readsVersionOneInstanceWithEnvironmentDefaults() {
        val app = StoredApp.fromJson(
            JSONObject(
                """
                {
                  "space_id": "virtual-1",
                  "package": "com.example.app",
                  "label": "Example",
                  "version_name": "1.0",
                  "version_code": 1,
                  "launcher_activity": "com.example.app.Main",
                  "base_apk": "/data/example.apk",
                  "split_apks": [],
                  "signature_sha256": "abc",
                  "native_libs": false,
                  "installed_at": 10,
                  "updated_at": 20,
                  "instances": [
                    {
                      "id": "instance-1",
                      "created_at": 10,
                      "last_launched_at": 20
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val instance = app!!.instances.single()
        assertEquals(CURRENT_ENVIRONMENT_VERSION, instance.environmentVersion)
        assertNull(instance.lastProbeResult)
    }

    @Test
    fun roundTripsVersionTwoEnvironmentMetadata() {
        val instance = StoredInstance(
            id = "instance-2",
            createdAt = 10,
            lastLaunchedAt = 20,
            environmentVersion = 1,
            lastProbeResult = "consistent"
        )
        val json = JSONObject()
            .put("space_id", "virtual-1")
            .put("package", "com.example.app")
            .put("version_code", 1)
            .put("launcher_activity", "com.example.app.Main")
            .put("base_apk", "/data/example.apk")
            .put("instances", org.json.JSONArray().put(JSONObject()
                .put("id", instance.id)
                .put("created_at", instance.createdAt)
                .put("last_launched_at", instance.lastLaunchedAt)
                .put("environment_version", instance.environmentVersion)
                .put("last_probe_result", instance.lastProbeResult)))

        val restored = StoredApp.fromJson(json)!!.instances.single()
        assertEquals(instance, restored)
    }

    @Test
    fun apkIndexRoundTripsAndInvalidatesOnFileChange() {
        val root = Files.createTempDirectory("nestspace-apk-index").toFile()
        try {
            val apk = File(root, "base.apk").apply { writeText("apk") }
            val fingerprint = ApkFileFingerprint(
                path = apk.absolutePath,
                size = apk.length(),
                lastModified = apk.lastModified(),
                sha256 = ApkPayloadIdentity.sha256(apk)
            )
            val index = ApkInstallIndex(
                schemaVersion = APK_INDEX_SCHEMA_VERSION,
                files = listOf(fingerprint),
                nativeAbis = setOf("arm64-v8a", "x86_64"),
                descriptorCachePath = File(root, "descriptor.bin").absolutePath
            )

            val restored = ApkInstallIndex.fromJson(index.toJson())!!
            assertEquals(index, restored)
            assertTrue(restored.isCurrent())
            assertTrue(restored.matchesPayload(listOf(fingerprint.sha256)))

            apk.appendText("changed")
            assertFalse(restored.isCurrent())
        } finally {
            root.deleteRecursively()
        }
    }
}
