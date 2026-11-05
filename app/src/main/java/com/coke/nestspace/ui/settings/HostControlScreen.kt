package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coke.nestspace.host.HostControlSettings
import com.coke.nestspace.host.RootShell
import com.coke.nestspace.host.RootStatus
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.launch

@Composable
internal fun HostControlScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(HostControlSettings.isEnabled(context)) }
    var status by remember { mutableStateOf<RootStatus?>(null) }
    var checking by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val next = RootShell.status(force = true)
        status = next
        if (next != RootStatus.AVAILABLE && enabled) {
            HostControlSettings.setEnabled(context, false)
            enabled = false
            message = AppLanguage.tr(
                "Root 已不可用，宿主控制已自动关闭。",
                "Root is no longer available; host control was turned off."
            )
        }
        checking = false
    }

    fun setEnabled(next: Boolean) {
        if (checking) return
        if (!next) {
            HostControlSettings.setEnabled(context, false)
            enabled = false
            message = ""
            return
        }
        scope.launch {
            checking = true
            message = ""
            val nextStatus = RootShell.status(force = true)
            status = nextStatus
            if (nextStatus == RootStatus.AVAILABLE) {
                HostControlSettings.setEnabled(context, true)
                enabled = true
            } else {
                enabled = false
                message = AppLanguage.tr(
                    "无法开启：没有可用的 Root 权限。",
                    "Cannot enable: root access is not available."
                )
            }
            checking = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("宿主控制（Root）", "Host control (root)")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("启用宿主控制", "Enable host control"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            AppLanguage.tr(
                                "让 AI 助手和 MCP 观察、点击和操作真实设备上的应用，而不仅是虚拟空间。",
                                "Let the AI assistant and MCP observe, tap and operate real " +
                                    "device apps, not just the virtual space."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        enabled = !checking,
                        onCheckedChange = ::setEnabled
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            AppLanguage.tr("Root 状态", "Root status"),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (checking) {
                            Spacer(Modifier.width(10.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    Text(
                        rootStatusText(status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (status) {
                            RootStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                            RootStatus.DENIED -> MaterialTheme.colorScheme.error
                            RootStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                checking = true
                                message = ""
                                val next = RootShell.status(force = true)
                                status = next
                                if (next != RootStatus.AVAILABLE && enabled) {
                                    HostControlSettings.setEnabled(context, false)
                                    enabled = false
                                }
                                checking = false
                            }
                        },
                        enabled = !checking
                    ) {
                        Text(AppLanguage.tr("重新检测 Root", "Check root again"))
                    }
                    if (message.isNotBlank()) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "这个开关需要设备已经 root，并允许 NestSpace 使用 su。" +
                        "开启后会向 AI 助手和 MCP 暴露 host_* 工具，可以读取屏幕、点击、输入、" +
                        "截图、启动应用和打开网址。权限很高，请只在可信设备上使用。",
                    "This switch requires a rooted device and su access for NestSpace. When " +
                        "enabled, host_* tools become available to the AI assistant and MCP: " +
                        "read the screen, tap, type, take screenshots, launch apps and open " +
                        "URLs. These are powerful permissions; only use them on a trusted device."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        checking = true
                        message = ""
                        val next = RootShell.status(force = true)
                        status = next
                        if (next == RootStatus.AVAILABLE) {
                            if (!enabled) {
                                HostControlSettings.setEnabled(context, true)
                                enabled = true
                            }
                        } else {
                            message = AppLanguage.tr(
                                "无法开启：没有可用的 Root 权限。",
                                "Cannot enable: root access is not available."
                            )
                        }
                        checking = false
                    }
                },
                enabled = !checking
            ) {
                Text(AppLanguage.tr("检测并开启", "Check and enable"))
            }
        }
    }
}

private fun rootStatusText(status: RootStatus?): String = when (status) {
    null -> AppLanguage.tr("正在检测 Root…", "Checking root…")
    RootStatus.AVAILABLE -> AppLanguage.tr(
        "Root 可用，su 已授权。",
        "Root is available and su is granted."
    )
    RootStatus.DENIED -> AppLanguage.tr(
        "Root 被拒绝，请在 Root 管理器中允许 NestSpace。",
        "Root was denied. Allow NestSpace in your root manager."
    )
    RootStatus.UNAVAILABLE -> AppLanguage.tr(
        "未检测到可用的 su。设备可能未 root，或 Root 管理器尚未安装 su。",
        "No usable su was found. The device may not be rooted, or the root manager has " +
            "not installed su yet."
    )
}
