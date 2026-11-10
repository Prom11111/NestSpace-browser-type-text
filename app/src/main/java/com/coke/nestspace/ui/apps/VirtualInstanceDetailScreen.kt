package com.coke.nestspace.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.ui.settings.SettingsCard
import com.coke.nestspace.ui.settings.SettingsSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun VirtualInstanceDetailScreen(
    virtualApps: List<VirtualApp>,
    packageName: String,
    instanceId: String,
    onLaunch: (String, String) -> Unit,
    onClearData: (String, String) -> Unit
) {
    val app = virtualApps.firstOrNull { it.packageName == packageName }
    val sortedInstances = app?.instances.orEmpty().sortedWith(
        compareBy({ it.createdAt }, { it.id })
    )
    val instanceIndex = sortedInstances.indexOfFirst { it.id == instanceId }
    val instance = sortedInstances.getOrNull(instanceIndex)
    var confirmClear by remember(instanceId) { mutableStateOf(false) }

    if (app == null || instance == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                AppLanguage.tr(
                    "实例不存在或已被删除",
                    "The instance does not exist or has been deleted"
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val icon = app.icon

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(app.label.take(1), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (instanceIndex == 0) app.label else "${app.label} ${instanceIndex + 1}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${app.packageName} · ${app.versionName ?: "v${app.versionCode}"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { onLaunch(app.packageName, instance.id) }) {
                    Icon(Icons.Default.Launch, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(AppLanguage.tr("启动", "Launch"))
                }
            }
        }

        item { SettingsSection(AppLanguage.tr("实例信息", "Instance information")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("独立运行环境", "Isolated runtime"),
                status = AppLanguage.tr("实例 ${instanceIndex + 1}", "Instance ${instanceIndex + 1}")
            ) {
                InstanceInfoRow(AppLanguage.tr("实例 ID", "Instance ID"), instance.id)
                HorizontalDivider()
                InstanceInfoRow(
                    AppLanguage.tr("创建时间", "Created"),
                    formatTime(instance.createdAt)
                )
                HorizontalDivider()
                InstanceInfoRow(
                    AppLanguage.tr("最近启动", "Last launched"),
                    formatTime(instance.lastLaunchedAt)
                )
                HorizontalDivider()
                InstanceInfoRow(
                    AppLanguage.tr("环境版本", "Environment version"),
                    instance.environmentVersion.toString()
                )
                instance.lastProbeResult?.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    InstanceInfoRow(AppLanguage.tr("最近探测", "Last probe"), it)
                }
                HorizontalDivider()
                InstanceInfoRow(
                    AppLanguage.tr("数据目录", "Data directory"),
                    instance.dataDir,
                    monospace = true
                )
            }
        }

        item { SettingsSection(AppLanguage.tr("实例数据", "Instance data")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("清空此实例数据", "Clear this instance's data"),
                status = ""
            ) {
                Text(
                    AppLanguage.tr(
                        "会停止这个实例并清除其文件、缓存、数据库、偏好设置和权限，" +
                            "APK、实例记录及其他实例不受影响。",
                        "Stops this instance and clears its files, caches, databases, " +
                            "preferences and permissions. The APK, instance record and " +
                            "other instances are not affected."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        AppLanguage.tr("清空实例数据", "Clear instance data"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(AppLanguage.tr("清空实例数据？", "Clear instance data?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将清空 ${instance.id} 的全部应用数据，无法撤销。其他实例不会受影响。",
                        "All app data for ${instance.id} will be cleared. This cannot be undone. " +
                            "Other instances are not affected."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearData(app.packageName, instance.id)
                    }
                ) {
                    Text(
                        AppLanguage.tr("清空", "Clear"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun InstanceInfoRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

private fun formatTime(timeMs: Long): String {
    if (timeMs <= 0L) return "-"
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
}
