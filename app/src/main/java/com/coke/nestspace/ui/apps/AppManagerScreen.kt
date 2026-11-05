package com.coke.nestspace.ui.apps

import com.coke.nestspace.i18n.AppLanguage
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coke.nestspace.R
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.data.VirtualApp
import androidx.core.view.WindowCompat
import com.coke.nestspace.vm.VirtualViewModel
import com.coke.nestspace.ui.settings.SettingsCard
import com.coke.nestspace.ui.settings.SettingsSection
import com.coke.nestspace.ui.settings.rememberAppVersion

@Composable
internal fun AppManagerScreen(
    virtualApps: List<VirtualApp>,
    systemApps: List<AppInfo>,
    desktopSystemPackages: Set<String>,
    installing: Boolean,
    notice: String,
    onInstallApk: () -> Unit,
    onLoadTestApk: () -> Unit,
    onLaunchVirtual: (String, String?) -> Unit,
    onMultiOpen: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onToggleSystemApp: (String) -> Unit
) {
    var pendingUninstall by remember { mutableStateOf<VirtualApp?>(null) }
    var showSystemPicker by remember { mutableStateOf(false) }
    var showVirtualInfo by remember { mutableStateOf(false) }
    val desktopApps = systemApps.filter { it.packageName in desktopSystemPackages }

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
                        "导入 APK 安装到虚拟空间；导入桌面应用从已安装应用里挑选桌面图标。",
                        "Import an APK into the virtual space, or pick installed " +
                            "apps to add desktop shortcuts."
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
                    OutlinedButton(onClick = { showSystemPicker = true }) {
                        Text(AppLanguage.tr("导入桌面应用", "Import desktop apps"))
                    }
                    Button(onClick = onInstallApk, enabled = !installing) {
                        Text(AppLanguage.tr("导入 APK", "Import APK"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onLoadTestApk, enabled = !installing) {
                        Text(AppLanguage.tr("测试 APK", "Test APK"))
                    }
                    TextButton(onClick = { showVirtualInfo = true }) { Text(AppLanguage.tr("说明", "Details")) }
                }
            }
        }

        item {
            SettingsSection(
                AppLanguage.tr(
                    "虚拟应用（${virtualApps.size}）",
                    "Virtual apps (${virtualApps.size})"
                )
            )
        }
        if (virtualApps.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr(
                        "还没有虚拟应用。点上方“导入 APK”；如需 split，可一次多选 base + split。",
                        "No virtual apps yet. Tap \"Import APK\" above; for split " +
                            "packages, select the base and split APKs together."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(virtualApps, key = { it.packageName }) { app ->
                VirtualAppRow(
                    app = app,
                    onLaunch = onLaunchVirtual,
                    onMultiOpen = onMultiOpen,
                    onUninstall = { pendingUninstall = it }
                )
            }
        }

        item {
            SettingsSection(
                AppLanguage.tr(
                    "桌面应用（${desktopApps.size}）",
                    "Desktop apps (${desktopApps.size})"
                )
            )
        }
        item {
            Text(
                AppLanguage.tr(
                    "已导入的应用会显示在桌面；在上方“导入桌面应用”中添加或取消。",
                    "Imported apps appear on the desktop. Add or remove them with " +
                        "\"Import desktop apps\" above."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (desktopApps.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr("桌面还没有系统应用。", "No system apps on the desktop yet."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(desktopApps, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DesktopAppIcon(app)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
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
                    TextButton(onClick = { onToggleSystemApp(app.packageName) }) {
                        Text(
                            AppLanguage.tr("移除", "Remove"),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
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
        SystemAppPickerDialog(
            apps = systemApps,
            selected = desktopSystemPackages,
            onToggle = onToggleSystemApp,
            onDismiss = { showSystemPicker = false }
        )
    }

    if (showVirtualInfo) {
        AlertDialog(
            onDismissRequest = { showVirtualInfo = false },
            title = { Text(AppLanguage.tr("导入与多开", "Import & multiple instances")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "导入 base APK（可同时选择 split）后会校验包名、签名、minSdk 和 " +
                            "REQUIRE_SECURE_ENV，并持久化到应用内部存储。\n\n" +
                            "每个应用可以有多个实例，每个实例有独立的 files/cache/databases/" +
                            "code_cache 目录。",
                        "After importing the base APK (optionally with splits), the " +
                            "package name, signature, minSdk and REQUIRE_SECURE_ENV are " +
                            "verified and the APK is stored in app-private storage.\n\n" +
                            "Each app can have several instances, and every instance has " +
                            "its own files/cache/databases/code_cache directories."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { showVirtualInfo = false }) { Text(AppLanguage.tr("知道了", "Got it")) }
            }
        )
    }
}

@Composable
private fun DesktopAppIcon(app: AppInfo) {
    if (app.icon != null) {
        Image(
            bitmap = app.icon,
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
}

@Composable
private fun SystemAppPickerDialog(
    apps: List<AppInfo>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.tr("导入桌面应用", "Import desktop apps")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    AppLanguage.tr(
                    "勾选的已安装应用会出现在桌面；虚拟应用会自动显示。",
                    "Checked installed apps appear on the desktop; virtual apps " +
                        "are shown automatically."
                ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                // The whole row is clickable; avoid a second
                                // toggle when the check mark itself is tapped.
                                onCheckedChange = null
                            )
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("完成", "Done"))
            }
        }
    )
}


@Composable
private fun VirtualAppRow(
    app: VirtualApp,
    onLaunch: (String, String?) -> Unit,
    onMultiOpen: (String) -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (app.icon != null) {
                Image(
                    bitmap = app.icon,
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
                    app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${app.packageName} · ${app.versionName ?: "v${app.versionCode}"}",
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

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(app.instances) { instance ->
                val index = app.instances.indexOf(instance) + 1
                AssistChip(
                    onClick = { onLaunch(app.packageName, instance.id) },
                    label = { Text(AppLanguage.tr("实例 $index", "Instance $index")) }
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { onLaunch(app.packageName, null) }) { Text(AppLanguage.tr("启动", "Launch")) }
            OutlinedButton(onClick = { onMultiOpen(app.packageName) }) { Text(AppLanguage.tr("多开", "Duplicate")) }
            TextButton(onClick = { onUninstall(app) }) {
                Text(
                        AppLanguage.tr("卸载", "Uninstall"),
                        color = MaterialTheme.colorScheme.error
                    )
            }
        }
    }
}

