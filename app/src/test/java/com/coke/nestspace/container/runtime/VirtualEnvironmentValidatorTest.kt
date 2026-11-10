package com.coke.nestspace.container.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualEnvironmentValidatorTest {
    @Test
    fun acceptsConsistentJavaAndNativeFacts() {
        val environment = environment()
        val result = VirtualEnvironmentValidator.validate(
            environment,
            facts(environment, nativeSnapshot(environment))
        )

        assertTrue(result.issues.toString(), result.isConsistent)
    }

    @Test
    fun reportsDataDirAndNativeUidMismatch() {
        val environment = environment()
        val result = VirtualEnvironmentValidator.validate(
            environment,
            facts(
                environment,
                nativeSnapshot(environment).copy(configuredObservedUid = 99999)
            ).copy(contextDataDir = "/wrong/data")
        )

        assertFalse(result.isConsistent)
        assertTrue(result.issues.any { it.code == "CONTEXT_DATA_DIR_MISMATCH" })
        assertTrue(result.issues.any { it.code == "NATIVE_OBSERVED_UID_MISMATCH" })
    }

    private fun facts(
        environment: VirtualEnvironment,
        native: NativeEnvironmentSnapshot?
    ) = VirtualEnvironmentFacts(
        contextPackageName = environment.packageName,
        applicationPackageName = environment.packageName,
        applicationUid = environment.observedUid,
        processUid = environment.hostUid,
        processPid = environment.virtualPid,
        contextDataDir = environment.dataRoot,
        applicationDataDir = environment.dataRoot,
        contextIsDeviceProtected = true,
        deviceProtectedDataDir = environment.deviceProtectedDataRoot,
        nativeSnapshot = native
    )

    private fun nativeSnapshot(environment: VirtualEnvironment) = NativeEnvironmentSnapshot(
        configuredHostUid = environment.hostUid,
        configuredObservedUid = environment.observedUid,
        configuredPid = environment.virtualPid,
        processUid = environment.hostUid,
        processName = environment.processName,
        identityRevision = 1,
        capabilityMask = 0,
        pageSize = 16384
    )

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
        properties = emptyMap(),
        selinuxContext = null,
        capabilities = emptySet(),
        bootTimeMillis = 1,
        installTimeMillis = 2,
        updateTimeMillis = 2
    )
}
