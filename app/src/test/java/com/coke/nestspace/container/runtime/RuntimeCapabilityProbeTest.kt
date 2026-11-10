package com.coke.nestspace.container.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCapabilityProbeTest {
    @Test
    fun fullProbeReportsOnlyProbeCapabilities() {
        val report = RuntimeCapabilityProbe.resolve(
            native = probe(),
            expectedProcessUid = 10216,
            androidApi = 36,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            availableSubprocessSlots = 16,
            storage = storage(),
            accountBasicVerified = true,
            pluginClassPathMounted = true
        )

        assertTrue(report.capabilities.contains(RuntimeCapability.NATIVE_BRIDGE))
        assertTrue(report.capabilities.contains(RuntimeCapability.NATIVE_UID_PROBE))
        assertTrue(report.capabilities.contains(RuntimeCapability.NATIVE_DIRECT_SYSCALL_PROBE))
        assertTrue(report.capabilities.contains(RuntimeCapability.NATIVE_PROC_MOUNTINFO_PROBE))
        assertTrue(report.capabilities.contains(RuntimeCapability.NATIVE_SELINUX_PROBE))
        assertTrue(report.capabilities.contains(RuntimeCapability.SUBPROCESS_SLOTS))
        assertTrue(report.capabilities.contains(RuntimeCapability.DEVICE_PROTECTED_STORAGE))
        assertTrue(report.capabilities.contains(RuntimeCapability.EXTERNAL_STORAGE))
        assertTrue(report.capabilities.contains(RuntimeCapability.ACCOUNT_BASIC))
        assertTrue(report.capabilities.contains(RuntimeCapability.PLUGIN_CLASS_PATH))
        assertFalse(report.capabilities.contains(RuntimeCapability.NATIVE_UID_FACADE))
        assertFalse(report.capabilities.contains(RuntimeCapability.NATIVE_PROC_VIEW))
        assertFalse(report.capabilities.contains(RuntimeCapability.NATIVE_PROPERTY_VIEW))
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun uidMismatchDoesNotReportUidProbe() {
        val report = RuntimeCapabilityProbe.resolve(
            native = probe().copy(procStatusUid = 99999),
            expectedProcessUid = 10216,
            androidApi = 36,
            supportedAbis = listOf("arm64-v8a"),
            availableSubprocessSlots = 16,
            storage = storage()
        )

        assertFalse(report.capabilities.contains(RuntimeCapability.NATIVE_UID_PROBE))
        assertTrue(report.issues.any { it.contains("UID readings disagree") })
    }

    @Test
    fun unavailableJniDoesNotInventNativeCapabilities() {
        val report = RuntimeCapabilityProbe.resolve(
            native = null,
            expectedProcessUid = 10216,
            androidApi = 36,
            supportedAbis = listOf("arm64-v8a"),
            availableSubprocessSlots = 0,
            storage = storage(device = false, external = false)
        )

        assertTrue(report.capabilities.isEmpty())
        assertTrue(report.issues.any { it.contains("native runtime") })
        assertTrue(report.issues.any { it.contains("no subprocess slots") })
        assertTrue(report.issues.any { it.contains("device-protected storage") })
    }

    private fun probe() = NativeRuntimeProbe(
        processUid = 10216,
        directSyscallUid = 10216,
        procStatusUid = 10216,
        pageSize = 4096,
        procStatusReadable = true,
        procMapsReadable = true,
        procMountInfoReadable = true,
        selinuxContextReadable = true,
        directSyscallOpenReadable = true,
        directSyscallStatReadable = true,
        propertyBackendReadable = true,
        productAbi = "arm64-v8a"
    )

    private fun storage(
        device: Boolean = true,
        external: Boolean = true
    ) = VirtualStorageProbeResult(
        deviceProtected = device,
        external = external,
        deviceProtectedRoot = "/data/instance/device_protected",
        externalRoot = "/storage/instance/cache"
    )
}
