package com.coke.nestspace.ui.apps

import com.coke.nestspace.i18n.AppLanguage
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.data.VirtualInstance
import com.coke.nestspace.ui.settings.SettingsCard
import com.coke.nestspace.ui.settings.SettingsSection

@Composable
internal fun AppManagerScreen(
    virtualApps: List<VirtualApp>,
    systemApps: List<AppInfo>,
    installing: Boolean,
    notice: String,
    onInstallApk: () -> Unit,
    onLaunchVirtual: (String, String?) -> Unit,
    onMultiOpen: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenInstance: (String, String) -> Unit,
    onImportApps: (Set<String>) -> Unit,
    onRefreshApps: () -> Unit
) {
    var pendingUninstall by remember { mutableStateOf<VirtualApp?>(null) }
    var showSystemPicker by remember { mutableStateOf(false) }
    val instanceEntries = remember(virtualApps) {
        virtualApps.flatMap { app ->
            app.instances
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .mapIndexed { index, instance ->
                    ManagedInstance(
                        app = app,
                        instance = instance,
                        number = index + 1,
                        displayLabel = if (index == 0) app.label else "${app.label} ${index + 1}"
                    )
                }
        }
    }
    val appCount = instanceEntries.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("添加应用", "Add apps")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("安装与导入", "Install & import"),
                status = if (installing) AppLanguage.tr("导入中", "Importing") else ""
            ) {
                Text(
                    AppLanguage.tr(
                        "导入宿主已安装的应用，也可以从 APK 文件安装。",
                        "Import apps already installed on the host, or install from an APK file."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                if (installing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (notice.isNotBlank()) {
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onRefreshApps()
                            showSystemPicker = true
                        }
                    ) {
                        Text(AppLanguage.tr("导入应用", "Import apps"))
                    }
                    Button(onClick = onInstallApk, enabled = !installing) {
                        Text(AppLanguage.tr("导入 APK", "Import APK"))
                    }
                }
            }
        }

        item {
            SettingsSection(
                AppLanguage.tr("应用（$appCount）", "Apps ($appCount)")
            )
        }
        if (appCount == 0) {
            item {
                Text(
                    AppLanguage.tr(
                        "还没有应用。点击上方“导入应用”，或从 APK 文件安装。",
                        "No apps yet. Tap \"Import apps\" above, or install from an APK file."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (instanceEntries.isNotEmpty()) {
            items(
                instanceEntries,
                key = { "${it.app.packageName}:${it.instance.id}" }
            ) { entry ->
                VirtualAppRow(
                    app = entry.app,
                    instance = entry.instance,
                    number = entry.number,
                    displayLabel = entry.displayLabel,
                    onLaunch = onLaunchVirtual,
                    onMultiOpen = onMultiOpen,
                    onOpenInstance = onOpenInstance,
                    onUninstall = { pendingUninstall = it }
                )
            }
        }
    }

    pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(AppLanguage.tr("卸载 ${app.label}？", "Uninstall ${app.label}?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除已安装 APK、${app.instances.size} 个实例以及所有实例数据。" +
                            "此操作不可恢复。",
                        "This deletes the installed APK, ${app.instances.size} " +
                            "instance(s) and all instance data. This cannot be undone."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        onUninstall(app.packageName)
                    }
                ) {
                    Text(
                        AppLanguage.tr("卸载", "Uninstall"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    if (showSystemPicker) {
        val installedPackages = virtualApps.map { it.packageName }.toSet()
        SystemAppPickerDialog(
            apps = systemApps.filterNot { it.packageName in installedPackages },
            onImport = onImportApps,
            onDismiss = { showSystemPicker = false }
        )
    }

}

private data class ManagedInstance(
    val app: VirtualApp,
    val instance: VirtualInstance,
    val number: Int,
    val displayLabel: String
)

@Composable
private fun SystemAppPickerDialog(
    apps: List<AppInfo>,
    onImport: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(apps) { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.tr("导入应用", "Import apps")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    AppLanguage.tr(
                        "勾选要安装到虚拟空间的应用。已安装的应用不会重复显示。",
                        "Choose apps to install in the virtual space. Apps already installed are hidden."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (apps.isEmpty()) {
                    Text(
                        AppLanguage.tr(
                            "宿主应用均已安装，没有需要重复导入的应用。",
                            "All host apps are already installed. There is nothing to import again."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                        val icon = app.icon
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (app.packageName in selected) {
                                        selected - app.packageName
                                    } else {
                                        selected + app.packageName
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                // The whole row is clickable; avoid a second
                                // toggle when the check mark itself is tapped.
                                onCheckedChange = null
                            )
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(app.label.take(1))
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onImport(selected)
                    onDismiss()
                }
            ) {
                Text(AppLanguage.tr("导入", "Import"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("取消", "Cancel"))
            }
        }
    )
}


@Composable
private fun VirtualAppRow(
    app: VirtualApp,
    instance: VirtualInstance,
    number: Int,
    displayLabel: String,
    onLaunch: (String, String?) -> Unit,
    onMultiOpen: (String) -> Unit,
    onOpenInstance: (String, String) -> Unit,
    onUninstall: (VirtualApp) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val icon = app.icon
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(app.label.take(1), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${app.packageName} · ${app.versionName ?: "v${app.versionCode}"} · " +
                        AppLanguage.tr("实例 $number", "Instance $number"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (app.hasNativeLibs) {
                AssistChip(onClick = {}, label = { Text("native") })
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { onLaunch(app.packageName, instance.id) }) {
                    Text(AppLanguage.tr("启动", "Launch"))
                }
                OutlinedButton(onClick = { onMultiOpen(app.packageName) }) {
                    Text(AppLanguage.tr("多开", "Duplicate"))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onOpenInstance(app.packageName, instance.id) }
                ) {
                    Text(AppLanguage.tr("实例信息", "Instance info"))
                }
                TextButton(onClick = { onUninstall(app) }) {
                    Text(
                        AppLanguage.tr("卸载", "Uninstall"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

