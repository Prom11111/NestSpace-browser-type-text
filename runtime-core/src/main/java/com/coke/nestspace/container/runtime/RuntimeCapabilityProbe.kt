package com.coke.nestspace.container.runtime

import android.os.Build
import android.os.Process

data class RuntimeCapabilityReport(
    val capabilities: Set<RuntimeCapability>,
    val androidApi: Int,
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val pageSize: Long,
    val nativeProcessUid: Int?,
    val directSyscallUid: Int?,
    val procStatusUid: Int?,
    val procStatusReadable: Boolean,
    val procMapsReadable: Boolean,
    val procMountInfoReadable: Boolean,
    val selinuxContextReadable: Boolean,
    val directSyscallOpenReadable: Boolean,
    val directSyscallStatReadable: Boolean,
    val propertyBackendReadable: Boolean,
    val nativeProductAbi: String?,
    val subprocessSlots: Int,
    val externalDataRoot: String?,
    val systemProperties: Map<String, String>,
    val issues: List<String>
) {
    val properties: Map<String, String>
        get() = buildMap {
            put("runtime.android_api", androidApi.toString())
            put("runtime.primary_abi", primaryAbi)
            put("runtime.supported_abis", supportedAbis.joinToString(","))
            put("runtime.page_size", pageSize.toString())
            put("runtime.proc_status_readable", procStatusReadable.toString())
            put("runtime.proc_maps_readable", procMapsReadable.toString())
            put("runtime.proc_mountinfo_readable", procMountInfoReadable.toString())
            put("runtime.selinux_context_readable", selinuxContextReadable.toString())
            put("runtime.direct_syscall_open_readable", directSyscallOpenReadable.toString())
            put("runtime.direct_syscall_stat_readable", directSyscallStatReadable.toString())
            put("runtime.property_backend_readable", propertyBackendReadable.toString())
            put("runtime.subprocess_slots", subprocessSlots.toString())
            nativeProcessUid?.let { put("runtime.native_process_uid", it.toString()) }
            directSyscallUid?.let { put("runtime.direct_syscall_uid", it.toString()) }
            procStatusUid?.let { put("runtime.proc_status_uid", it.toString()) }
            nativeProductAbi?.takeIf { it.isNotBlank() }?.let {
                put("runtime.native_product_abi", it)
            }
            putAll(systemProperties)
        }
}

/**
 * Verifies the process facts on which the native facade can rely. Probe
 * results are capabilities only when the backend was actually exercised; a
 * readable UID is not evidence that virtualized UID/proc hooks are installed.
 */
object RuntimeCapabilityProbe {
    fun probe(
        expectedProcessUid: Int = Process.myUid(),
        availableSubprocessSlots: Int,
        storage: VirtualStorageProbeResult? = null,
        accountBasicVerified: Boolean = false,
        pluginClassPathMounted: Boolean = false
    ): RuntimeCapabilityReport = resolve(
        native = NativeEnvironmentBridge.probe(),
        expectedProcessUid = expectedProcessUid,
        androidApi = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        availableSubprocessSlots = availableSubprocessSlots,
        storage = storage,
        accountBasicVerified = accountBasicVerified,
        pluginClassPathMounted = pluginClassPathMounted,
        systemProperties = deviceSystemProperties()
    )

    internal fun resolve(
        native: NativeRuntimeProbe?,
        expectedProcessUid: Int,
        androidApi: Int,
        supportedAbis: List<String>,
        availableSubprocessSlots: Int,
        storage: VirtualStorageProbeResult? = null,
        accountBasicVerified: Boolean = false,
        pluginClassPathMounted: Boolean = false,
        systemProperties: Map<String, String> = emptyMap()
    ): RuntimeCapabilityReport {
        val capabilities = linkedSetOf<RuntimeCapability>()
        val issues = mutableListOf<String>()

        if (native != null) {
            capabilities += RuntimeCapability.NATIVE_BRIDGE
        } else {
            issues += "native runtime library or probe is unavailable"
        }

        val allUidReadings = listOfNotNull(
            native?.processUid,
            native?.directSyscallUid,
            native?.procStatusUid
        )
        if (allUidReadings.size == 3 &&
            allUidReadings.all { it == expectedProcessUid } &&
            native?.processUid == native?.directSyscallUid &&
            native?.processUid == native?.procStatusUid
        ) {
            capabilities += RuntimeCapability.NATIVE_UID_PROBE
        } else if (native != null) {
            issues += "native UID readings disagree with Process.myUid"
        }

        if (native?.procStatusReadable == true) {
            capabilities += RuntimeCapability.NATIVE_PROC_STATUS_PROBE
        } else if (native != null) {
            issues += "/proc/self/status is not readable"
        }
        if (native?.procMapsReadable == true) {
            capabilities += RuntimeCapability.NATIVE_PROC_MAPS_PROBE
        } else if (native != null) {
            issues += "/proc/self/maps is not readable"
        }
        if (native?.procMountInfoReadable == true) {
            capabilities += RuntimeCapability.NATIVE_PROC_MOUNTINFO_PROBE
        } else if (native != null) {
            issues += "/proc/self/mountinfo is not readable"
        }
        if (native?.selinuxContextReadable == true) {
            capabilities += RuntimeCapability.NATIVE_SELINUX_PROBE
        } else if (native != null) {
            issues += "/proc/self/attr/current is not readable"
        }
        if (native?.directSyscallOpenReadable == true &&
            native.directSyscallStatReadable
        ) {
            capabilities += RuntimeCapability.NATIVE_DIRECT_SYSCALL_PROBE
        } else if (native != null) {
            issues += "direct openat/statx syscall probe did not complete"
        }
        if (native?.propertyBackendReadable == true) {
            capabilities += RuntimeCapability.NATIVE_PROPERTY_PROBE
        } else if (native != null) {
            issues += "Android property backend is not readable"
        }
        if (availableSubprocessSlots > 0) {
            capabilities += RuntimeCapability.SUBPROCESS_SLOTS
        } else {
            issues += "no subprocess slots are available"
        }
        if (storage?.deviceProtected == true) {
            capabilities += RuntimeCapability.DEVICE_PROTECTED_STORAGE
        } else {
            issues += "device-protected storage probe failed"
        }
        if (storage?.external == true) {
            capabilities += RuntimeCapability.EXTERNAL_STORAGE
        } else {
            issues += "external storage probe failed"
        }
        if (accountBasicVerified) {
            capabilities += RuntimeCapability.ACCOUNT_BASIC
        } else {
            issues += "virtual AccountManager probe failed"
        }
        if (pluginClassPathMounted) {
            capabilities += RuntimeCapability.PLUGIN_CLASS_PATH
        }

        return RuntimeCapabilityReport(
            capabilities = capabilities,
            androidApi = androidApi,
            primaryAbi = supportedAbis.firstOrNull().orEmpty(),
            supportedAbis = supportedAbis,
            pageSize = native?.pageSize ?: 0L,
            nativeProcessUid = native?.processUid,
            directSyscallUid = native?.directSyscallUid,
            procStatusUid = native?.procStatusUid,
            procStatusReadable = native?.procStatusReadable == true,
            procMapsReadable = native?.procMapsReadable == true,
            procMountInfoReadable = native?.procMountInfoReadable == true,
            selinuxContextReadable = native?.selinuxContextReadable == true,
            directSyscallOpenReadable = native?.directSyscallOpenReadable == true,
            directSyscallStatReadable = native?.directSyscallStatReadable == true,
            propertyBackendReadable = native?.propertyBackendReadable == true,
            nativeProductAbi = native?.productAbi,
            subprocessSlots = availableSubprocessSlots,
            externalDataRoot = storage?.externalInstanceRoot,
            systemProperties = systemProperties,
            issues = issues
        )
    }

    private fun deviceSystemProperties(): Map<String, String> = buildMap {
        put("ro.build.version.sdk", Build.VERSION.SDK_INT.toString())
        put("ro.build.version.release", Build.VERSION.RELEASE.orEmpty())
        put("ro.build.version.incremental", Build.VERSION.INCREMENTAL.orEmpty())
        put("ro.build.id", Build.ID.orEmpty())
        put("ro.build.display.id", Build.DISPLAY.orEmpty())
        put("ro.build.type", Build.TYPE.orEmpty())
        put("ro.build.tags", Build.TAGS.orEmpty())
        put("ro.build.fingerprint", Build.FINGERPRINT.orEmpty())
        put("ro.product.brand", Build.BRAND.orEmpty())
        put("ro.product.manufacturer", Build.MANUFACTURER.orEmpty())
        put("ro.product.model", Build.MODEL.orEmpty())
        put("ro.product.device", Build.DEVICE.orEmpty())
        put("ro.product.name", Build.PRODUCT.orEmpty())
        put("ro.product.cpu.abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        put("ro.product.cpu.abilist", Build.SUPPORTED_ABIS.joinToString(","))
        put("ro.product.cpu.abilist64", Build.SUPPORTED_64_BIT_ABIS.joinToString(","))
        put("ro.product.cpu.abilist32", Build.SUPPORTED_32_BIT_ABIS.joinToString(","))
    }
}
