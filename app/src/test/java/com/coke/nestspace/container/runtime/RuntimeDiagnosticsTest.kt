package com.coke.nestspace.container.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsTest {
    @Test
    fun usesProcessSpecificDiagnosticsFiles() {
        assertEquals(
            RuntimeDiagnostics.MAIN_FILE_NAME,
            RuntimeDiagnostics.fileName("com.example.app", "com.example.app")
        )
        assertEquals(
            "runtime-diagnostics-com.example.app_miniappX.json",
            RuntimeDiagnostics.fileName(
                "com.example.app",
                "com.example.app:miniappX"
            )
        )
    }

    @Test
    fun exportsProbeFactsWithoutIdentitySecrets() {
        val coordinator = AndroidStartupCoordinator(clock = { 0L })
        coordinator.mark(AndroidStartupPhase.READY, 12L)
        val environment = environment()
        val json = RuntimeDiagnostics.build(
            environment = environment,
            identity = VirtualIdentitySnapshot(
                revision = 3,
                androidId = "secret-android-id",
                clientUdid = "secret-client-id",
                deviceId = "secret-device-id",
                installId = "secret-install-id",
                source = "test"
            ),
            validation = EnvironmentValidation(emptyList()),
            startup = coordinator.report(),
            native = NativeEnvironmentSnapshot(
                configuredHostUid = environment.hostUid,
                configuredObservedUid = environment.observedUid,
                configuredPid = environment.virtualPid,
                processUid = environment.hostUid,
                processName = environment.processName,
                identityRevision = 3,
                capabilityMask = 0,
                pageSize = 4096
            ),
            probe = NativeRuntimeProbe(
                processUid = environment.hostUid,
                directSyscallUid = environment.hostUid,
                procStatusUid = environment.hostUid,
                pageSize = 4096,
                procStatusReadable = true,
                procMapsReadable = true,
                procMountInfoReadable = true,
                selinuxContextReadable = true,
                directSyscallOpenReadable = true,
                directSyscallStatReadable = true,
                propertyBackendReadable = true,
                productAbi = "arm64-v8a"
            ),
            processUid = environment.hostUid,
            processPid = environment.virtualPid,
            headless = true,
            compatibilityProfile = null,
            pathMapProbe = environment.dataRoot,
            externalPathMapProbe = "/storage/instance",
            procViewProbe = "Name:\tapp\nUid:\t10123\t10123\t10123\t10123\n",
            propertyValueProbe = "36",
            propertyCallbackProbe = "36"
        )

        assertEquals(3, json.getLong("identity_revision"))
        assertEquals("verified", json.getJSONObject("native_facade")
            .getString("direct_syscall_probe"))
        assertEquals("disabled", json.getJSONObject("native_facade").getString("uid_hook"))
        assertEquals(
            "verified",
            json.getJSONObject("native_facade").getString("path_map_probe")
        )
        assertEquals(
            "verified",
            json.getJSONObject("native_facade").getString("proc_view_probe")
        )
        assertEquals(
            "verified",
            json.getJSONObject("native_facade").getString("external_path_map_probe")
        )
        assertEquals(
            "verified",
            json.getJSONObject("native_facade").getString("property_value_probe")
        )
        assertEquals(
            "verified",
            json.getJSONObject("native_facade").getString("property_callback_probe")
        )
        assertTrue(json.toString().contains("NATIVE_UID_PROBE"))
        assertFalse(json.toString().contains("secret-android-id"))
        assertFalse(json.toString().contains("secret-client-id"))
        assertFalse(json.toString().contains("secret-device-id"))
        assertFalse(json.toString().contains("secret-install-id"))
    }

    @Test
    fun exportsValidationAndStartupFailure() {
        val coordinator = AndroidStartupCoordinator(clock = { 0L })
        runCatching {
            coordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
                error("create failed")
            }
        }
        val json = RuntimeDiagnostics.build(
            environment = environment(),
            identity = VirtualIdentitySnapshot.uninitialized(),
            validation = EnvironmentValidation(
                listOf(
                    EnvironmentIssue(
                        code = "TEST_MISMATCH",
                        severity = EnvironmentIssue.Severity.ERROR,
                        message = "mismatch"
                    )
                )
            ),
            startup = coordinator.report(),
            native = null,
            probe = null,
            processUid = 10123,
            processPid = 4567,
            headless = false,
            compatibilityProfile = "test-profile"
        )

        assertFalse(
            json.getJSONObject("environment_validation").getBoolean("consistent")
        )
        assertEquals(
            "FAILED",
            json.getJSONObject("startup")
                .getJSONArray("phases")
                .getJSONObject(0)
                .getString("outcome")
        )
        assertEquals("test-profile", json.getString("compatibility_profile"))
    }

    private fun environment() = VirtualEnvironment(
        instanceId = "instance-1",
        packageName = "com.example.app",
        hostUid = 10123,
        observedUid = 10123,
        virtualPid = 4567,
        dataRoot = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/instance-1",
        deviceProtectedDataRoot = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/instance-1/device_protected",
        filesDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/instance-1/files",
        cacheDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/instance-1/cache",
        codeCacheDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/instance-1/code_cache",
        apkPaths = listOf("/data/app/com.example.app/base.apk"),
        splitApkPaths = emptyList(),
        nativeLibraryDir = null,
        processName = "com.example.app",
        versionName = "1.0",
        versionCode = 1,
        properties = mapOf(
            "runtime.android_api" to "36",
            "runtime.primary_abi" to "arm64-v8a"
        ),
        selinuxContext = null,
        capabilities = setOf(
            RuntimeCapability.NATIVE_BRIDGE,
            RuntimeCapability.NATIVE_UID_PROBE
        ),
        bootTimeMillis = 1,
        installTimeMillis = 2,
        updateTimeMillis = 2
    )
}
