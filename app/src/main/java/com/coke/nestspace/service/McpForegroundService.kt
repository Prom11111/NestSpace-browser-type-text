package com.coke.nestspace.service

import com.coke.nestspace.i18n.AppLanguage
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.coke.nestspace.AppGraph
import com.coke.nestspace.R
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.mcp.McpNetwork
import com.coke.nestspace.mcp.McpServer
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class McpForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.coke.nestspace.MCP_START"
        const val ACTION_RESTART = "com.coke.nestspace.MCP_RESTART"
        const val CHANNEL_ID = "mcp"
        const val NOTIFICATION_ID = 1001
        private const val PREFS = "virtual"
        private const val KEY_TOKEN = "mcp_token"
        private const val KEY_ENABLED = "mcp_auto_start"
        private const val KEY_LAN = "mcp_lan_enabled"
        private const val TAG = "McpForegroundService"

        val status = MutableStateFlow(McpStatus())

        fun start(context: Context) {
            prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
            startService(context)
        }

        /**
         * Auto-start entry point. Deferred startup may run before the process
         * is foreground on Android 16+, so the start is also retried from
         * MainActivity.onResume. Never crashes the caller on background-FGS
         * limits, and respects an explicit Stop from settings.
         */
        fun ensureStarted(context: Context) {
            if (!prefs(context).getBoolean(KEY_ENABLED, true)) return
            runCatching { startService(context) }
                .onFailure { Log.w(TAG, "MCP auto-start failed", it) }
        }

        fun stop(context: Context) {
            prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
            context.stopService(Intent(context, McpForegroundService::class.java))
        }

        fun isLanEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_LAN, false)

        /**
         * Persist the LAN switch and restart a running server so the socket
         * moves between 127.0.0.1 and 0.0.0.0 immediately.
         */
        fun setLanEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_LAN, enabled).apply()
            val current = status.value
            val lanAddress = if (enabled) {
                McpNetwork.lanAddress(McpNetwork.lanIpv4())
            } else {
                ""
            }
            status.value = current.copy(
                lanEnabled = enabled,
                address = if (lanAddress.isNotBlank()) {
                    lanAddress
                } else {
                    McpNetwork.LOCAL_ADDRESS
                },
                lanAddress = lanAddress,
                error = null
            )
            if (current.running &&
                prefs(context).getBoolean(KEY_ENABLED, true) &&
                !restart(context)
            ) {
                status.value = status.value.copy(
                    address = McpNetwork.LOCAL_ADDRESS,
                    lanAddress = "",
                    error = AppLanguage.tr(
                        "MCP 重启失败，请手动停止后重新启动。",
                        "MCP restart failed; stop and start it manually."
                    )
                )
            }
        }

        /** Recompute the displayed LAN address after a network change. */
        fun refreshNetwork(context: Context) {
            val current = status.value
            if (!current.running) return
            if (current.lanEnabled && !McpNetwork.hasLocalNetworkPermission(context)) {
                if (!restart(context)) {
                    status.value = current.copy(
                        address = McpNetwork.LOCAL_ADDRESS,
                        lanAddress = "",
                        error = AppLanguage.tr(
                            "局域网权限已被撤销，正在使用本机连接。",
                            "Local network permission was revoked; using loopback."
                        )
                    )
                }
                return
            }
            // A permission warning means the server is intentionally still on
            // loopback. If permission has since been granted, restart once so
            // the LAN switch reflects reality instead of staying stale.
            if (current.error != null) {
                if (current.lanEnabled && McpNetwork.hasLocalNetworkPermission(context)) {
                    if (!restart(context)) {
                        status.value = current.copy(
                            error = AppLanguage.tr(
                                "MCP 重启失败，请手动停止后重新启动。",
                                "MCP restart failed; stop and start it manually."
                            )
                        )
                    }
                }
                return
            }
            val lanAddress = if (current.lanEnabled) {
                McpNetwork.lanAddress(McpNetwork.lanIpv4())
            } else {
                ""
            }
            if (lanAddress == current.lanAddress) return
            status.value = current.copy(
                address = if (current.lanEnabled && lanAddress.isNotBlank()) {
                    lanAddress
                } else {
                    McpNetwork.LOCAL_ADDRESS
                },
                lanAddress = lanAddress
            )
        }

        private fun restart(context: Context): Boolean {
            if (!prefs(context).getBoolean(KEY_ENABLED, true)) return false
            return runCatching {
                // The service is already foreground; a plain start avoids
                // Android 12+ background-FGS start restrictions.
                context.startService(
                    Intent(context, McpForegroundService::class.java)
                        .setAction(ACTION_RESTART)
                )
                true
            }.getOrElse {
                Log.w(TAG, "MCP restart failed", it)
                false
            }
        }

        private fun startService(context: Context) {
            val intent = Intent(context, McpForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private var server: McpServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        BackgroundOverlay.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        BackgroundOverlay.ensure(this)

        if (intent?.action == ACTION_RESTART) {
            server?.stop()
            server = null
        }
        if (server != null) return START_STICKY

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) {
            token = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }

        val lanRequested = isLanEnabled(this)
        val lanAllowed = !lanRequested || McpNetwork.hasLocalNetworkPermission(this)
        val bindAddress = if (lanRequested && lanAllowed) "0.0.0.0" else "127.0.0.1"
        val localAddress = McpNetwork.LOCAL_ADDRESS
        val lanAddress = if (lanRequested && lanAllowed) {
            McpNetwork.lanAddress(McpNetwork.lanIpv4())
        } else {
            ""
        }
        val primaryAddress = if (lanAddress.isNotBlank()) lanAddress else localAddress
        val warning = if (lanRequested && !lanAllowed) {
            AppLanguage.tr(
                "局域网权限未授予，MCP 仅监听 127.0.0.1。",
                "Local network permission is not granted; MCP is listening on 127.0.0.1 only."
            )
        } else {
            null
        }

        return try {
            server = McpServer(
                AppGraph.registry(this),
                token,
                this,
                bindAddress = bindAddress
            ).also { it.start() }
            status.value = McpStatus(
                running = true,
                address = primaryAddress,
                token = token,
                error = warning,
                lanEnabled = lanRequested,
                localAddress = localAddress,
                lanAddress = lanAddress
            )
            startForeground(NOTIFICATION_ID, buildNotification())
            START_STICKY
        } catch (t: Throwable) {
            Log.w(TAG, "MCP start failed", t)
            status.value = McpStatus(
                running = false,
                token = token,
                error = AppLanguage.tr(
                    "MCP 启动失败：${t.message ?: t.javaClass.simpleName}",
                    "MCP failed to start: ${t.message ?: t.javaClass.simpleName}"
                ),
                lanEnabled = lanRequested,
                localAddress = localAddress,
                lanAddress = lanAddress
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        BackgroundOverlay.remove(this)
        val lan = isLanEnabled(this)
        status.value = McpStatus(
            running = false,
            token = status.value.token,
            error = status.value.error,
            lanEnabled = lan,
            localAddress = McpNetwork.LOCAL_ADDRESS,
            lanAddress = if (lan &&
                status.value.error == null &&
                McpNetwork.hasLocalNetworkPermission(this)
            ) {
                McpNetwork.lanAddress(McpNetwork.lanIpv4())
            } else {
                ""
            }
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCP Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val lanActive = status.value.lanEnabled && status.value.error == null &&
            McpNetwork.hasLocalNetworkPermission(this)
        val address = if (lanActive) {
            status.value.address.ifBlank { McpNetwork.LOCAL_ADDRESS }
        } else {
            McpNetwork.LOCAL_ADDRESS
        }
        val host = address.removePrefix("http://").removeSuffix("/mcp")
        val text = if (lanActive) {
            AppLanguage.tr(
                "MCP Server 运行在 $host（局域网）",
                "MCP server is running at $host (LAN)"
            )
        } else {
            AppLanguage.tr(
                "MCP Server 运行在 $host",
                "MCP server is running at $host"
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("NestSpace MCP")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
