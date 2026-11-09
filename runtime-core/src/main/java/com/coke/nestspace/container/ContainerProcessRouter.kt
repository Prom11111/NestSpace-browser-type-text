package com.coke.nestspace.container

import android.content.Context
import android.util.Log
import com.coke.nestspace.container.remote.ContainerProcessBrokerClient
import com.coke.nestspace.container.remote.ContainerRemoteServices

/**
 * Maps an Android target process name (android:process) to a host slot
 * process and to a process-specific ContainerSession id.
 *
 * The main target process is the legacy `pkg@instance` session. Subprocesses
 * use `pkg@instance|process` and a different host slot. A slot only ever
 * contains one target process, preserving per-process Application/static
 * state and native library identity.
 */
object ContainerProcessRouter {

    private const val TAG = "ContainerProcessRouter"

    fun normalize(packageName: String, raw: String?): String {
        val value = raw?.trim().orEmpty()
        return when {
            value.isEmpty() -> packageName
            value == packageName || value.startsWith("$packageName:") -> value
            value.startsWith(":") -> packageName + value
            value.startsWith(".") -> packageName + value
            value.contains('.') -> value
            else -> "$packageName.$value"
        }
    }

    fun isMainProcess(packageName: String, processName: String?): Boolean =
        normalize(packageName, processName) == packageName

    fun sessionId(packageName: String, instanceId: String, processName: String?): String {
        val normalized = normalize(packageName, processName)
        return if (normalized == packageName) {
            "$packageName@$instanceId"
        } else {
            "$packageName@$instanceId|$normalized"
        }
    }

    fun slotKey(packageName: String, instanceId: String, processName: String?): String =
        sessionId(packageName, instanceId, processName)

    /** Slot hosting this target process, or null when it was never launched. */
    fun slotForProcess(
        context: Context,
        packageName: String,
        instanceId: String,
        processName: String?
    ): Int? = ContainerProcessPool.slotForKey(
        context,
        slotKey(packageName, instanceId, processName)
    )

    /**
     * Blocking slot resolution. The main process allocates locally; a slot
     * process delegates to the main-process broker. Never call this on a slot
     * process's main thread (the broker reply needs that looper); use
     * [ContainerProcessBrokerClient.acquireAsync] there instead.
     */
    fun ensureSlot(
        context: Context,
        packageName: String,
        instanceId: String,
        processName: String?
    ): Int? {
        val normalized = normalize(packageName, processName)
        val slot = ContainerProcessBrokerClient.acquire(
            context,
            packageName,
            instanceId,
            normalized
        )
        if (slot != null) {
            Log.d(TAG, "resolved $normalized -> slot $slot")
        } else {
            Log.w(TAG, "no slot for $normalized")
        }
        return slot
    }

    fun hostServiceClass(slot: Int): Class<*> = ContainerRemoteServices.classForSlot(slot)
}
