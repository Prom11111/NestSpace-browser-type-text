package com.coke.nestspace.container.vpm

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.coke.nestspace.container.identity.ContainerIdentity

/**
 * Virtual permission manager for one container process.
 *
 * Target packages are not installed for the host UID, so the system cannot
 * answer permission questions about them. This manager keeps an app-facing
 * grant model that follows Android semantics:
 *
 * - permissions the target declares are granted by default (virtual apps in a
 *   space are installed by the user, like a normal install);
 * - an explicit runtime request is routed to the real host permission flow
 *   when the host declares the same permission, so the user sees the system
 *   dialog and the answer is recorded per target package;
 * - denials persist and are visible to the target through
 *   checkSelfPermission/checkPermission, and to cross-virtual-app queries.
 *
 * Overrides live in ContainerPermissionStore (per package), so an agent tool
 * running in the main process can grant/revoke while the container is alive.
 */
class ContainerPermissions(
    hostContext: Context,
    private val targetPackage: String,
    private val targetInstanceId: String,
    private val hostPackage: String,
    private val targetRequestedPermissions: Array<String>?
) {

    private data class PendingRequest(
        val target: Activity,
        val targetRequestCode: Int,
        val permissions: Array<String>,
        val results: IntArray,
        val hostPermissions: Array<String>,
        val hostIndexes: IntArray
    )

    private data class CachedState(
        val readAt: Long,
        val state: ContainerPermissionStore.State
    )

    private val hostContext = hostContext.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val states = HashMap<String, CachedState>()
    private val pending = HashMap<Int, PendingRequest>()
    private var nextRequestCode = HOST_REQUEST_CODE_BASE

    @Volatile
    private var registry: VirtualPackageRegistry? = null

    private val targetDeclared: Set<String> = targetRequestedPermissions?.toSet().orEmpty()

    @Volatile
    private var hostDeclared: Set<String>? = null

    fun attachRegistry(registry: VirtualPackageRegistry) {
        this.registry = registry
    }

    /** Permissions declared by a virtual package (self or another app). */
    fun declaredBy(packageName: String?): Set<String> = when {
        packageName == null -> emptySet()
        packageName == targetPackage -> targetDeclared
        registry?.isVirtualPackage(packageName) == true ->
            registry?.requestedPermissions(packageName).orEmpty()
        else -> emptySet()
    }

    fun isVirtualPackage(packageName: String?): Boolean =
        packageName == targetPackage || registry?.isVirtualPackage(packageName) == true

    fun checkSelfPermission(permission: String?): Int =
        checkVirtualWithHostGrant(targetPackage, permission)

    fun checkCallingPermission(permission: String?, pid: Int, uid: Int): Int =
        if (uid == Process.myUid() || uid == ContainerIdentity.virtualUidFor(targetPackage)) {
            checkVirtualWithHostGrant(targetPackage, permission)
        } else if (permission == null) {
            PackageManager.PERMISSION_DENIED
        } else {
            hostContext.checkPermission(permission, pid, uid)
        }

    /**
     * The target may declare a dangerous permission that the host also
     * declares but the user never granted to NestSpace. Reporting it as
     * granted makes framework components (notably WebView's audio/Bluetooth
     * initializer) call the real system API, which then throws and can crash
     * the target. Reflect the host grant for those shared permissions.
     */
    private fun checkVirtualWithHostGrant(packageName: String, permission: String?): Int {
        val virtual = checkVirtualPermission(packageName, permission)
        if (virtual != PackageManager.PERMISSION_GRANTED || permission == null) return virtual
        if (permission !in hostDeclaredPermissions()) return virtual
        return hostContext.checkSelfPermission(permission)
    }

    /** Permission answer for any package visible to the container. */
    fun checkPackagePermission(packageName: String?, permission: String?): Int {
        if (!isVirtualPackage(packageName)) {
            return if (packageName == null || permission == null) {
                PackageManager.PERMISSION_DENIED
            } else {
                hostContext.packageManager.checkPermission(permission, packageName)
            }
        }
        return checkVirtualWithHostGrant(packageName!!, permission)
    }

    fun enforce(permission: String?, message: String?) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException(message ?: "Permission denied: $permission")
        }
    }

    /**
     * Intercepts an `Activity.requestPermissions` intent from target code and
     * answers it inside the container. Host-declared permissions go through
     * the real runtime permission dialog; the rest follow the virtual policy.
     */
    fun request(
        hostActivity: Activity,
        targetActivity: Activity?,
        permissions: Array<String>,
        targetRequestCode: Int
    ): Boolean {
        if (targetActivity == null) return false
        val explicitlyDenied = state(targetPackage).denied
        val results = IntArray(permissions.size) {
            if (permissions[it] in targetDeclared && permissions[it] !in explicitlyDenied) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }

        val hostPermissions = mutableListOf<String>()
        val hostIndexes = mutableListOf<Int>()
        val declaredByHost = hostDeclaredPermissions()
        permissions.forEachIndexed { index, permission ->
            if (permission in targetDeclared &&
                permission in declaredByHost &&
                permission !in explicitlyDenied
            ) {
                hostPermissions += permission
                hostIndexes += index
            }
        }

        if (hostPermissions.isEmpty() || hostActivity.isFinishing || hostActivity.isDestroyed) {
            handler.post { deliver(targetActivity, targetRequestCode, permissions, results) }
            return true
        }

        val requestCode = synchronized(pending) {
            // Failed/destroyed requests never deliver a host result; drop
            // them so they cannot retain target Activities forever.
            pending.entries.removeAll { entry ->
                val target = entry.value.target
                target.isFinishing || target.isDestroyed
            }
            var code = nextRequestCode
            var attempts = 0
            while (pending.containsKey(code) && attempts <= HOST_REQUEST_CODE_RANGE) {
                code = if (code >= HOST_REQUEST_CODE_BASE + HOST_REQUEST_CODE_RANGE) {
                    HOST_REQUEST_CODE_BASE
                } else {
                    code + 1
                }
                attempts++
            }
            nextRequestCode = if (code >= HOST_REQUEST_CODE_BASE + HOST_REQUEST_CODE_RANGE) {
                HOST_REQUEST_CODE_BASE
            } else {
                code + 1
            }
            pending[code] = PendingRequest(
                target = targetActivity,
                targetRequestCode = targetRequestCode,
                permissions = permissions.copyOf(),
                results = results,
                hostPermissions = hostPermissions.toTypedArray(),
                hostIndexes = hostIndexes.toIntArray()
            )
            code
        }
        Log.d(
            TAG,
            "requestPermissions target=$targetRequestCode host=$requestCode " +
                "hostPermissions=${hostPermissions.joinToString()}"
        )
        return try {
            hostActivity.requestPermissions(hostPermissions.toTypedArray(), requestCode)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "host requestPermissions failed", t)
            synchronized(pending) { pending.remove(requestCode) }
            handler.post { deliver(targetActivity, targetRequestCode, permissions, results) }
            true
        }
    }

    /**
     * Called from the host Activity's onRequestPermissionsResult.
     * Returns true when the result belongs to a virtual permission request.
     */
    fun onHostPermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        val request = synchronized(pending) { pending.remove(requestCode) } ?: return false
        val granted = HashSet<String>()
        val denied = HashSet<String>()
        request.hostIndexes.forEachIndexed { hostIndex, targetIndex ->
            val hostPermission = request.hostPermissions.getOrNull(hostIndex)
            val result = grantResults.getOrNull(hostIndex)
                ?: PackageManager.PERMISSION_DENIED
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted += request.permissions[targetIndex]
            } else {
                denied += request.permissions[targetIndex]
            }
            if (hostPermission != null) {
                Log.d(TAG, "host permission $hostPermission=$result")
            }
        }
        updateState(targetPackage, granted, denied)
        request.hostIndexes.forEachIndexed { hostIndex, targetIndex ->
            request.results[targetIndex] = grantResults.getOrNull(hostIndex)
                ?: PackageManager.PERMISSION_DENIED
        }
        handler.post {
            deliver(
                request.target,
                request.targetRequestCode,
                request.permissions,
                request.results
            )
        }
        return true
    }

    fun grantPermission(packageName: String, permission: String) {
        updateState(packageName, setOf(permission), emptySet())
    }

    fun revokePermission(packageName: String, permission: String) {
        updateState(packageName, emptySet(), setOf(permission))
    }

    fun grantedPermissions(packageName: String): Set<String> =
        state(packageName).granted

    fun deniedPermissions(packageName: String): Set<String> =
        state(packageName).denied

    private fun checkVirtualPermission(packageName: String, permission: String?): Int {
        if (permission.isNullOrBlank()) return PackageManager.PERMISSION_DENIED
        val declared = declaredBy(packageName)
        if (permission !in declared) return PackageManager.PERMISSION_DENIED
        val state = state(packageName)
        if (permission in state.denied) return PackageManager.PERMISSION_DENIED
        if (permission in state.granted) return PackageManager.PERMISSION_GRANTED
        // Virtual installs are user-initiated: declared permissions are
        // granted by default until an explicit runtime request denies them.
        return PackageManager.PERMISSION_GRANTED
    }

    private fun hostDeclaredPermissions(): Set<String> = hostDeclared ?: runCatching {
        val info = hostContext.packageManager.getPackageInfo(
            hostPackage,
            PackageManager.GET_PERMISSIONS
        )
        info.requestedPermissions?.toSet().orEmpty()
    }.getOrDefault(emptySet()).also { hostDeclared = it }

    private fun deliver(
        target: Activity,
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (target.isFinishing || target.isDestroyed) return
        // Activity.requestPermissions normally clears this flag in its own
        // result path; a container-delivered result must do it explicitly or
        // the next request is treated as a concurrent request and cancelled.
        runCatching {
            Activity::class.java
                .getDeclaredField("mHasCurrentPermissionsRequest")
                .apply { isAccessible = true }
                .setBoolean(target, false)
        }
        runCatching {
            target.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }.onFailure { Log.w(TAG, "deliver permission result failed", it) }
    }

    /**
     * Short cache so the per-package file (which agent tools in the main
     * process may edit) is picked up without paying disk I/O per check.
     */
    private fun state(packageName: String): ContainerPermissionStore.State =
        synchronized(states) {
            val instanceId = instanceIdFor(packageName)
            val cacheKey = "$packageName@$instanceId"
            val now = SystemClock.elapsedRealtime()
            val cached = states[cacheKey]
            if (cached != null && now - cached.readAt < STATE_TTL_MS) {
                return cached.state
            }
            val loaded = ContainerPermissionStore.load(hostContext, packageName, instanceId)
            states[cacheKey] = CachedState(now, loaded)
            loaded
        }

    private fun updateState(
        packageName: String,
        granted: Set<String>,
        denied: Set<String>
    ) {
        synchronized(states) {
            val instanceId = instanceIdFor(packageName)
            val state = ContainerPermissionStore.update(
                hostContext,
                packageName,
                instanceId,
                granted,
                denied
            )
            states["$packageName@$instanceId"] =
                CachedState(SystemClock.elapsedRealtime(), state)
        }
    }

    private fun instanceIdFor(packageName: String): String =
        if (packageName == targetPackage) {
            targetInstanceId
        } else {
            registry?.instanceIdFor(packageName) ?: ContainerPermissionStore.DEFAULT_INSTANCE_ID
        }

    private companion object {
        const val TAG = "ContainerPermissions"
        const val HOST_REQUEST_CODE_BASE = 0x4E00
        const val HOST_REQUEST_CODE_RANGE = 0xF0
        const val STATE_TTL_MS = 1_000L
    }
}
