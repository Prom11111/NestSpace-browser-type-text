package com.coke.nestspace.container.runtime

/**
 * Android WebView uses one process-wide data directory unless a suffix is set
 * before the first WebView initialization. Multiple target processes and
 * instances therefore need distinct suffixes even though they share an APK.
 */
object VirtualWebViewDataDirectory {
    fun suffix(
        packageName: String,
        instanceId: String,
        processName: String
    ): String {
        val key = "$packageName|$instanceId|$processName"
        return "nestspace_${Integer.toUnsignedString(key.hashCode(), 36)}"
    }
}
