package com.coke.nestspace.ui.settings

import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.mcp.McpNetwork
import com.coke.nestspace.service.McpForegroundService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ServicesDetailScreen(
    mcpStatus: McpStatus,
    token: String,
    onStartMcp: () -> Unit,
    onStopMcp: () -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showMcpHelp by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val lanPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onToggleLan(true)
        } else {
            Toast.makeText(
                context,
                AppLanguage.tr(
                    "未授予局域网权限，MCP 仍只监听本机",
                    "Local network permission denied; MCP stays on loopback"
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    fun changeLan(enabled: Boolean) {
        if (!enabled) {
            onToggleLan(false)
            return
        }
        val needsPermission = Build.VERSION.SDK_INT >= 37 &&
            context.checkSelfPermission(McpNetwork.LOCAL_NETWORK_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            lanPermission.launch(McpNetwork.LOCAL_NETWORK_PERMISSION)
        } else {
            onToggleLan(true)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                McpForegroundService.refreshNetwork(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("运行状态", "Status")) }
        item {
            ServicesCard(
                mcpStatus = mcpStatus,
                overlayGranted = overlayGranted,
                onStartMcp = onStartMcp,
                onStopMcp = onStopMcp,
                onToggleLan = ::changeLan,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onShowMcpHelp = { showMcpHelp = true }
            )
        }
    }
    if (showMcpHelp) {
        AlertDialog(
            onDismissRequest = { showMcpHelp = false },
            title = { Text(AppLanguage.tr("连接 MCP", "Connect MCP")) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (mcpStatus.running) {
                        Text(
                            AppLanguage.tr(
                                "本机：${mcpStatus.localAddress.ifBlank { McpNetwork.LOCAL_ADDRESS }}",
                                "Local: ${mcpStatus.localAddress.ifBlank { McpNetwork.LOCAL_ADDRESS }}"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        if (mcpStatus.lanEnabled) {
                            val lanText = mcpStatus.lanAddress.ifBlank {
                                AppLanguage.tr("未获取到 IP", "No IP")
                            }
                            Text(
                                AppLanguage.tr("局域网：$lanText", "LAN: $lanText"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            "Token：$token",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(token))
                                Toast.makeText(
                                    context,
                                    AppLanguage.tr("Token 已复制", "Token copied"),
                                    Toast.LENGTH_SHORT
                                )
                                    .show()
                            },
                            enabled = token.isNotBlank()
                        ) { Text(AppLanguage.tr("复制 Token", "Copy token")) }
                    } else {
                        Text(
                            AppLanguage.tr(
                            "MCP Server 尚未启动，启动后可在这里查看地址和 Token。",
                            "MCP server is not running. Start it to see the address and token here."
                        ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        AppLanguage.tr(
                            "1. USB 连接后做端口转发（局域网模式可跳过）：",
                            "1. Forward the port after connecting over USB (skip in LAN mode):"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "adb forward tcp:8765 tcp:8765",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        AppLanguage.tr(
                            "2. 在 Claude Code 中添加：",
                            "2. Add it in Claude Code:"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "claude mcp add --transport http space " +
                            "${mcpStatus.address.ifBlank { McpNetwork.LOCAL_ADDRESS }} --header " +
                            "\"Authorization: Bearer <token>\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        AppLanguage.tr(
                            "3. 命令行（CLI）：",
                            "3. Command line (CLI):"
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "export NESTSPACE_MCP_URL=" +
                            "${mcpStatus.address.ifBlank { McpNetwork.LOCAL_ADDRESS }}\n" +
                            "export NESTSPACE_MCP_TOKEN=$token\n" +
                            "tools/nestspace-mcp tools",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMcpHelp = false }) { Text(AppLanguage.tr("关闭", "Close")) }
            }
        )
    }
}


@Composable
private fun ServicesCard(
    mcpStatus: McpStatus,
    overlayGranted: Boolean,
    onStartMcp: () -> Unit,
    onStopMcp: () -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onShowMcpHelp: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                AppLanguage.tr("运行状态", "Status"),
                style = MaterialTheme.typography.titleMedium
            )
            StatusRow(
                label = "MCP Server",
                ok = mcpStatus.running,
                detail = if (mcpStatus.running) mcpStatus.address
                    else AppLanguage.tr("未启动", "Not running")
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        AppLanguage.tr("局域网访问", "LAN access"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        when {
                            !mcpStatus.lanEnabled ->
                                AppLanguage.tr("仅本机 127.0.0.1", "Loopback only")
                            mcpStatus.lanAddress.isNotBlank() -> mcpStatus.lanAddress
                            else -> AppLanguage.tr("未获取到局域网 IP", "No LAN IP")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = if (mcpStatus.lanAddress.isNotBlank()) {
                            FontFamily.Monospace
                        } else {
                            FontFamily.Default
                        }
                    )
                }
                Switch(
                    checked = mcpStatus.lanEnabled,
                    onCheckedChange = onToggleLan
                )
            }
            if (mcpStatus.lanEnabled) {
                Text(
                    AppLanguage.tr(
                        "使用 HTTP + Token，未加密；只在对可信设备开放的局域网中使用。",
                        "Uses HTTP + token without encryption; only enable it on a trusted LAN."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            mcpStatus.error?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            StatusRow(
                label = AppLanguage.tr("后台启动", "Background start"),
                ok = overlayGranted,
                detail = if (overlayGranted) AppLanguage.tr("已授权", "Granted")
                    else AppLanguage.tr("未授权", "Not granted")
            )
            if (!overlayGranted) {
                OutlinedButton(onClick = onOpenOverlaySettings) { Text(AppLanguage.tr("授权悬浮窗", "Allow overlay")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (mcpStatus.running) {
                    OutlinedButton(onClick = onStopMcp) { Text(AppLanguage.tr("停止", "Stop")) }
                } else {
                    OutlinedButton(onClick = onStartMcp) { Text(AppLanguage.tr("启动", "Start")) }
                }
                TextButton(onClick = onShowMcpHelp) { Text(AppLanguage.tr("连接信息", "Connection info")) }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
