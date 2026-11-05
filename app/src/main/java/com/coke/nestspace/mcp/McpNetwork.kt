package com.coke.nestspace.mcp

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/** Address and Android 17 local-network helpers for the MCP server. */
object McpNetwork {

    const val PORT = 8765
    const val LOCAL_ADDRESS = "http://127.0.0.1:$PORT/mcp"
    const val LOCAL_HEALTH = "http://127.0.0.1:$PORT/health"
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    fun lanAddress(ip: String?): String =
        if (ip.isNullOrBlank()) "" else "http://$ip:$PORT/mcp"

    /**
     * Best-effort LAN IPv4 for display. Prefers Wi-Fi/Ethernet over cellular,
     * and ignores loopback/link-local/private-invalid interfaces.
     */
    fun lanIpv4(): String? = runCatching {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nif in interfaces) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name.lowercase()
            if (name.startsWith("dummy") || name.startsWith("rmnet")) continue
            val priority = when {
                name.startsWith("wlan") -> 0
                name.startsWith("eth") -> 1
                name.startsWith("ap") -> 2
                else -> 3
            }
            for (address in nif.inetAddresses.toList()) {
                if (address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    address.isSiteLocalAddress
                ) {
                    address.hostAddress?.let { candidates += priority to it }
                }
            }
        }
        candidates.minByOrNull { it.first }?.second
    }.getOrNull()

    fun hasLocalNetworkPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return ContextCompat.checkSelfPermission(
            context.applicationContext,
            LOCAL_NETWORK_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun needsLocalNetworkPermission(context: Context): Boolean =
        !hasLocalNetworkPermission(context)
}
