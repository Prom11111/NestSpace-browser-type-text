package com.coke.nestspace.container.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VirtualEnvironmentRegistryTest {
    @Before
    fun resetRegistry() {
        VirtualEnvironmentRegistry.resetForTests()
    }

    @After
    fun clearRegistry() {
        VirtualEnvironmentRegistry.resetForTests()
    }

    @Test
    fun installsOneEnvironmentPerProcess() {
        val environment = environment("instance-1")

        assertTrue(VirtualEnvironmentRegistry.install(environment))
        assertTrue(VirtualEnvironmentRegistry.install(environment))
        assertFalse(VirtualEnvironmentRegistry.install(environment("instance-2")))
        assertSame(environment, VirtualEnvironmentRegistry.current())
    }

    @Test
    fun publishesMonotonicIdentitySnapshots() {
        val environment = environment("instance-1")
        VirtualEnvironmentRegistry.install(environment)

        val first = VirtualIdentitySnapshot(
            revision = 1,
            androidId = "android-1",
            clientUdid = null,
            deviceId = null,
            installId = null,
            source = "test"
        )
        val second = first.copy(revision = 2, deviceId = "did-2")

        assertTrue(VirtualEnvironmentRegistry.publishIdentity(environment, first))
        assertTrue(VirtualEnvironmentRegistry.publishIdentity(environment, second))
        assertFalse(VirtualEnvironmentRegistry.publishIdentity(environment, first))
        assertEquals(second, VirtualEnvironmentRegistry.identitySnapshot())
    }

    @Test
    fun clearsOnlyTheOwningEnvironment() {
        val environment = environment("instance-1")
        VirtualEnvironmentRegistry.install(environment)

        assertFalse(VirtualEnvironmentRegistry.clear(environment("instance-2")))
        assertSame(environment, VirtualEnvironmentRegistry.current())
        assertTrue(VirtualEnvironmentRegistry.clear(environment))
        assertNull(VirtualEnvironmentRegistry.current())
        assertEquals(0, VirtualEnvironmentRegistry.identitySnapshot().revision)
    }

    private fun environment(instanceId: String) = VirtualEnvironment(
        instanceId = instanceId,
        packageName = "com.example.app",
        hostUid = 10123,
        observedUid = 10123,
        virtualPid = 4567,
        dataRoot = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/$instanceId",
        deviceProtectedDataRoot = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/$instanceId/device_protected",
        filesDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/$instanceId/files",
        cacheDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/$instanceId/cache",
        codeCacheDir = "/data/user/0/com.coke.nestspace/files/vspace/data/com.example.app/$instanceId/code_cache",
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
