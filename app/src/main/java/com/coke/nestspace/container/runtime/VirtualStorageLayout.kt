package com.coke.nestspace.container.runtime

import java.io.File

/**
 * Android exposes one credential-protected and one device-protected storage
 * area per application. Virtual instances keep them as sibling roots so data
 * never crosses the storage-area boundary or another instance.
 */
object VirtualStorageLayout {
    const val DEVICE_PROTECTED_DIRECTORY = "device_protected"

    fun deviceProtectedRoot(credentialRoot: File): File =
        File(credentialRoot, DEVICE_PROTECTED_DIRECTORY)
}
