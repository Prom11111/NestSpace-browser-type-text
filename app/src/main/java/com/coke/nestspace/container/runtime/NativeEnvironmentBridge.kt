package com.coke.nestspace.container.runtime

import android.util.Log

data class NativeEnvironmentSnapshot(
    val configuredHostUid: Int,
    val configuredObservedUid: Int,
    val configuredPid: Int,
    val processUid: Int,
    val processName: String?,
    val identityRevision: Long,
    val capabilityMask: Long,
    val pageSize: Long,
    val hookCount: Int = 0,
    val hookedModuleCount: Int = 0,
    val scannedModuleCount: Int = 0,
    val targetModuleCount: Int = 0,
    val hookErrorCount: Int = 0,
    val virtualProcReadCount: Int = 0,
    val propertyReadCount: Int = 0,
    val dlopenCallCount: Int = 0
)

data class NativeHookSnapshot(
    val hookCount: Int,
    val hookedModuleCount: Int,
    val scannedModuleCount: Int,
    val targetModuleCount: Int,
    val errorCount: Int,
    val virtualProcReadCount: Int,
    val propertyReadCount: Int,
    val dlopenCallCount: Int
)

data class NativeRuntimeProbe(
    val processUid: Int,
    val directSyscallUid: Int,
    val procStatusUid: Int?,
    val pageSize: Long,
    val procStatusReadable: Boolean,
    val procMapsReadable: Boolean,
    val procMountInfoReadable: Boolean = false,
    val selinuxContextReadable: Boolean = false,
    val directSyscallOpenReadable: Boolean = false,
    val directSyscallStatReadable: Boolean = false,
    val propertyBackendReadable: Boolean,
    val productAbi: String?
)

/**
 * JNI boundary for the native compatibility facade.
 *
 * Installs the immutable environment snapshot and a target-module-only GOT/PLT
 * path facade. /proc and property virtualization remain controlled probes.
 */
object NativeEnvironmentBridge {
    private const val TAG = "NativeEnvironmentBridge"

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return synchronized(this) {
            if (loaded) return@synchronized true
            loaded = runCatching {
                System.loadLibrary("nestspace_runtime")
                true
            }.onFailure {
                Log.w(TAG, "native runtime unavailable", it)
            }.getOrDefault(false)
            loaded
        }
    }

    fun install(
        environment: VirtualEnvironment,
        identity: VirtualIdentitySnapshot
    ): Boolean {
        if (!ensureLoaded()) return false
        return runCatching {
            nativeInstall(
                environment.instanceId,
                environment.packageName,
                environment.dataRoot,
                environment.externalDataRoot.orEmpty(),
                environment.processName,
                environment.nativeLibraryDir.orEmpty(),
                environment.properties.flatMap { (key, value) -> listOf(key, value) }
                    .toTypedArray(),
                environment.hostUid,
                environment.observedUid,
                environment.virtualPid,
                identity.revision,
                environment.capabilities.toMask()
            )
        }.onFailure {
            Log.w(TAG, "install environment failed", it)
        }.getOrDefault(false)
    }

    fun publishIdentity(identity: VirtualIdentitySnapshot): Boolean {
        if (!ensureLoaded()) return false
        return runCatching {
            nativePublishIdentity(identity.revision)
        }.onFailure {
            Log.w(TAG, "publish identity failed", it)
        }.getOrDefault(false)
    }

    fun snapshot(): NativeEnvironmentSnapshot? {
        if (!ensureLoaded()) return null
        return runCatching {
            val identity = nativeIdentity()
            if (identity == null || identity.size < 4) return@runCatching null
            val hooks = hookSnapshot()
            NativeEnvironmentSnapshot(
                configuredHostUid = identity[0],
                configuredObservedUid = identity[1],
                configuredPid = identity[2],
                processUid = identity[3],
                processName = nativeProcessName(),
                identityRevision = nativeIdentityRevision(),
                capabilityMask = nativeCapabilityMask(),
                pageSize = nativePageSize(),
                hookCount = hooks?.hookCount ?: 0,
                hookedModuleCount = hooks?.hookedModuleCount ?: 0,
                scannedModuleCount = hooks?.scannedModuleCount ?: 0,
                targetModuleCount = hooks?.targetModuleCount ?: 0,
                hookErrorCount = hooks?.errorCount ?: 0,
                virtualProcReadCount = hooks?.virtualProcReadCount ?: 0,
                propertyReadCount = hooks?.propertyReadCount ?: 0,
                dlopenCallCount = hooks?.dlopenCallCount ?: 0
            )
        }.onFailure {
            Log.w(TAG, "read native environment failed", it)
        }.getOrNull()
    }

    fun probe(): NativeRuntimeProbe? {
        if (!ensureLoaded()) return null
        return runCatching {
            val facts = nativeProbe()
            if (facts == null || facts.size < 6) return@runCatching null
            NativeRuntimeProbe(
                processUid = facts[0].toInt(),
                directSyscallUid = facts[1].toInt(),
                procStatusUid = facts[2].toInt().takeIf { it >= 0 },
                pageSize = facts[3],
                procStatusReadable = facts[4] and PROBE_STATUS_READABLE != 0L,
                procMapsReadable = facts[4] and PROBE_MAPS_READABLE != 0L,
                procMountInfoReadable = facts[4] and PROBE_MOUNTINFO_READABLE != 0L,
                selinuxContextReadable = facts[4] and PROBE_SELINUX_READABLE != 0L,
                directSyscallOpenReadable = facts[4] and PROBE_DIRECT_OPEN_READABLE != 0L,
                directSyscallStatReadable = facts[4] and PROBE_DIRECT_STAT_READABLE != 0L,
                propertyBackendReadable = facts[4] and PROBE_PROPERTY_READABLE != 0L,
                productAbi = nativeProductAbi()?.takeIf { it.isNotBlank() }
            )
        }.onFailure {
            Log.w(TAG, "native capability probe failed", it)
        }.getOrNull()
    }

    fun refreshHooks(scanElfFallback: Boolean = false): NativeHookSnapshot? {
        if (!ensureLoaded()) return null
        return runCatching {
            val values = nativeRefreshHooks(scanElfFallback)
            if (values == null || values.size < 8) return@runCatching null
            NativeHookSnapshot(
                hookCount = values[0].toInt(),
                hookedModuleCount = values[1].toInt(),
                scannedModuleCount = values[2].toInt(),
                targetModuleCount = values[3].toInt(),
                errorCount = values[4].toInt(),
                virtualProcReadCount = values[5].toInt(),
                propertyReadCount = values[6].toInt(),
                dlopenCallCount = values[7].toInt()
            )
        }.onFailure {
            Log.w(TAG, "native hook refresh failed", it)
        }.getOrNull()
    }

    fun hookSnapshot(): NativeHookSnapshot? {
        if (!ensureLoaded()) return null
        return runCatching {
            val values = nativeHookSnapshot()
            if (values == null || values.size < 8) return@runCatching null
            NativeHookSnapshot(
                hookCount = values[0].toInt(),
                hookedModuleCount = values[1].toInt(),
                scannedModuleCount = values[2].toInt(),
                targetModuleCount = values[3].toInt(),
                errorCount = values[4].toInt(),
                virtualProcReadCount = values[5].toInt(),
                propertyReadCount = values[6].toInt(),
                dlopenCallCount = values[7].toInt()
            )
        }.getOrNull()
    }

    fun rewritePathForDiagnostics(path: String): String? {
        if (!ensureLoaded()) return null
        return runCatching { nativeRewritePath(path) }.getOrNull()
    }

    fun readVirtualProcForDiagnostics(path: String): String? {
        if (!ensureLoaded()) return null
        return runCatching { nativeReadVirtualProc(path) }.getOrNull()
    }

    fun readPropertyForDiagnostics(name: String): String? {
        if (!ensureLoaded()) return null
        return runCatching { nativeReadProperty(name) }.getOrNull()
    }

    fun readPropertyCallbackForDiagnostics(name: String): String? {
        if (!ensureLoaded()) return null
        return runCatching { nativeReadPropertyCallback(name) }.getOrNull()
    }

    fun clear() {
        if (loaded) {
            runCatching { nativeClear() }
        }
    }

    private fun Set<RuntimeCapability>.toMask(): Long =
        fold(0L) { mask, capability ->
            mask or (1L shl capability.ordinal)
        }

    @JvmStatic
    private external fun nativeInstall(
        instanceId: String,
        packageName: String,
        dataRoot: String,
        externalDataRoot: String,
        processName: String,
        nativeLibraryDir: String,
        properties: Array<String>,
        hostUid: Int,
        observedUid: Int,
        virtualPid: Int,
        identityRevision: Long,
        capabilityMask: Long
    ): Boolean

    @JvmStatic
    private external fun nativeIdentity(): IntArray?

    @JvmStatic
    private external fun nativeProcessName(): String?

    @JvmStatic
    private external fun nativePageSize(): Long

    @JvmStatic
    private external fun nativeRefreshHooks(scanElfFallback: Boolean): LongArray?

    @JvmStatic
    private external fun nativeHookSnapshot(): LongArray?

    @JvmStatic
    private external fun nativeRewritePath(path: String): String?

    @JvmStatic
    private external fun nativeReadVirtualProc(path: String): String?

    @JvmStatic
    private external fun nativeReadProperty(name: String): String?

    @JvmStatic
    private external fun nativeReadPropertyCallback(name: String): String?

    @JvmStatic
    private external fun nativeProbe(): LongArray?

    @JvmStatic
    private external fun nativeProductAbi(): String?

    @JvmStatic
    private external fun nativeIdentityRevision(): Long

    @JvmStatic
    private external fun nativeCapabilityMask(): Long

    @JvmStatic
    private external fun nativePublishIdentity(revision: Long): Boolean

    @JvmStatic
    private external fun nativeClear()

    private const val PROBE_STATUS_READABLE = 1L shl 0
    private const val PROBE_MAPS_READABLE = 1L shl 1
    private const val PROBE_PROPERTY_READABLE = 1L shl 2
    private const val PROBE_MOUNTINFO_READABLE = 1L shl 3
    private const val PROBE_SELINUX_READABLE = 1L shl 4
    private const val PROBE_DIRECT_OPEN_READABLE = 1L shl 5
    private const val PROBE_DIRECT_STAT_READABLE = 1L shl 6
}
