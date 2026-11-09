package com.coke.nestspace.container.runtime

import android.content.Context
import java.io.File

data class VirtualStorageProbeResult(
    val deviceProtected: Boolean,
    val external: Boolean,
    val deviceProtectedRoot: String,
    val externalRoot: String?,
    val externalInstanceRoot: String? = externalRoot?.let {
        java.io.File(it).parent
    }
)

object VirtualStorageProbe {
    fun probe(
        context: Context,
        credentialRoot: File
    ): VirtualStorageProbeResult {
        val expectedDeviceRoot = VirtualStorageLayout.deviceProtectedRoot(credentialRoot)
        val deviceContext = runCatching {
            context.createDeviceProtectedStorageContext()
        }.getOrNull()
        val actualDeviceRoot = deviceContext?.dataDir
        val deviceProtected = deviceContext?.isDeviceProtectedStorage == true &&
            actualDeviceRoot != null &&
            actualDeviceRoot.absolutePath == expectedDeviceRoot.absolutePath &&
            canWrite(expectedDeviceRoot)

        val externalRoot = runCatching { context.externalCacheDir }.getOrNull()
        val external = externalRoot != null && canWrite(externalRoot)

        return VirtualStorageProbeResult(
            deviceProtected = deviceProtected,
            external = external,
            deviceProtectedRoot = expectedDeviceRoot.absolutePath,
            externalRoot = externalRoot?.absolutePath
        )
    }

    private fun canWrite(root: File): Boolean = runCatching {
        if (!root.isDirectory && !root.mkdirs()) return false
        val probe = File(root, ".nestspace-storage-probe")
        try {
            probe.writeText("ok")
            probe.isFile && probe.readText() == "ok"
        } finally {
            probe.delete()
        }
    }.getOrDefault(false)
}
