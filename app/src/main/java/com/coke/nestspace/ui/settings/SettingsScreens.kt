package com.coke.nestspace.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Switch
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
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.i18n.AppLanguageMode
import com.coke.nestspace.location.MockLocationSettings
import com.coke.nestspace.mcp.McpNetwork
import com.coke.nestspace.service.McpForegroundService
import androidx.core.view.WindowCompat
import com.coke.nestspace.vm.VirtualViewModel

@Composable
internal fun SettingsHomeScreen(
    appCount: Int,
    desktopAppCount: Int,
    mcpStatus: McpStatus,
    hasWallpaper: Boolean,
    llmConfig: LlmConfig,
    languageMode: AppLanguageMode,
    slotLimitMode: Int,
    slotLimit: Int,
    hostControlEnabled: Boolean,
    locationSimulation: MockLocationSettings.Config,
    onOpenApps: () -> Unit,
    onOpenHostControl: () -> Unit,
    onOpenLocationSimulation: () -> Unit,
    onOpenBrowserProxy: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenFloatingDot: () -> Unit,
    onOpenModel: () -> Unit,
    onOpenSystemPrompt: () -> Unit,
    onOpenMcpClients: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenEvaluation: () -> Unit,
    onOpenChildRuns: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    permissionLevel: ToolPermissionLevel,
    onOpenPermissions: () -> Unit,
    onOpenSlots: () -> Unit,
    onOpenSelfCheck: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenAbout: () -> Unit
) {
    val versionName = rememberAppVersion()
    AppLanguage.isChinese()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SettingsGroup(AppLanguage.tr("应用与容器", "Apps & containers")) {
                SettingsMenuRow(
                    icon = Icons.Default.Apps,
                    title = AppLanguage.tr("应用管理", "App management"),
                    summary = AppLanguage.trCount(
                        appCount,
                        "$appCount 个虚拟应用 · 桌面 $desktopAppCount 个",
                        "$appCount virtual app · $desktopAppCount on desktop",
                        "$appCount virtual apps · $desktopAppCount on desktop"
                    ),
                    onClick = onOpenApps
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = AppLanguage.tr("宿主控制（Root）", "Host control (root)"),
                    summary = if (hostControlEnabled) {
                        AppLanguage.tr(
                            "已开启 · 助手可操作真实设备",
                            "Enabled · the assistant can control the real device"
                        )
                    } else {
                        AppLanguage.tr(
                            "未开启 · 需要设备已 Root",
                            "Off · requires a rooted device"
                        )
                    },
                    onClick = onOpenHostControl
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Layers,
                    title = AppLanguage.tr("容器槽位", "Container slots"),
                    summary = if (slotLimitMode ==
                        com.coke.nestspace.container.ContainerProcessPool.AUTO_SLOT_LIMIT
                    ) {
                        AppLanguage.tr(
                            "动态管理 · 最多 ${com.coke.nestspace.container.ContainerProcessPool.SLOT_COUNT} 个",
                            "Dynamic · up to ${com.coke.nestspace.container.ContainerProcessPool.SLOT_COUNT}"
                        )
                    } else {
                        AppLanguage.tr("最多 $slotLimit 个", "Up to $slotLimit")
                    },
                    onClick = onOpenSlots
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.LocationOn,
                    title = AppLanguage.tr("坐标模拟", "Location simulation"),
                    summary = if (locationSimulation.enabled) {
                        AppLanguage.tr(
                            "已开启 · ${MockLocationSettings.label(locationSimulation)}",
                            "On · ${MockLocationSettings.label(locationSimulation)}"
                        )
                    } else {
                        AppLanguage.tr(
                            "未开启 · 虚拟应用使用真实定位",
                            "Off · virtual apps use real location"
                        )
                    },
                    onClick = onOpenLocationSimulation
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Build,
                    title = AppLanguage.tr("容器自检", "Container self-check"),
                    summary = AppLanguage.tr(
                        "检查启动、界面、截图、进程和 WebView",
                        "Check launch, screen, screenshots, processes and WebView"
                    ),
                    onClick = onOpenSelfCheck
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Language,
                    title = AppLanguage.tr("浏览器代理", "Browser proxy"),
                    summary = AppLanguage.tr(
                        "HTTP/SOCKS 代理 · 所有标签页共用",
                        "HTTP/SOCKS proxy shared by all tabs"
                    ),
                    onClick = onOpenBrowserProxy
                )
            }
        }
        item {
            SettingsGroup(AppLanguage.tr("助手", "Assistant")) {
                SettingsMenuRow(
                    icon = Icons.Default.SmartToy,
                    title = AppLanguage.tr("模型设置", "Model settings"),
                    summary = if (llmConfig.isConfigured) {
                        llmConfig.protocol.label + " · " +
                            llmConfig.model.ifBlank {
                                AppLanguage.tr("未填写模型", "No model")
                            }
                    } else {
                        AppLanguage.tr("规则模式", "Rule mode")
                    },
                    onClick = onOpenModel
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Edit,
                    title = AppLanguage.tr("系统提示词", "System prompt"),
                    summary = AppLanguage.tr(
                        "所有模型共用 · 可恢复内置默认",
                        "Shared by all models · restore built-in default"
                    ),
                    onClick = onOpenSystemPrompt
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Extension,
                    title = AppLanguage.tr("MCP 工具", "MCP tools"),
                    summary = AppLanguage.tr(
                        "外部 MCP Server · 为助手增加工具",
                        "External MCP servers · add tools to the assistant"
                    ),
                    onClick = onOpenMcpClients
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Psychology,
                    title = AppLanguage.tr("记忆与技能", "Memory & skills"),
                    summary = AppLanguage.tr(
                        "用户偏好、已批准的记忆和可复用做法",
                        "Preferences, approved memories and reusable procedures"
                    ),
                    onClick = onOpenMemory
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Schedule,
                    title = AppLanguage.tr("定时任务", "Scheduled tasks"),
                    summary = AppLanguage.tr(
                        "到点自动执行，并在原对话里返回结果",
                        "Run automatically and return results to the conversation"
                    ),
                    onClick = onOpenSchedule
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Science,
                    title = AppLanguage.tr("评测与回放", "Evaluation & replay"),
                    summary = AppLanguage.tr(
                        "用真实任务测试 Agent，并重放成功流程",
                        "Test the agent with real tasks and replay successful flows"
                    ),
                    onClick = onOpenEvaluation
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.AccountTree,
                    title = AppLanguage.tr("子任务记录", "Child runs"),
                    summary = AppLanguage.tr(
                        "子 Agent 的运行状态、结果和停止操作",
                        "Child-agent status, results and cancellation"
                    ),
                    onClick = onOpenChildRuns
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.QueryStats,
                    title = AppLanguage.tr("Token 统计", "Token usage"),
                    summary = AppLanguage.tr(
                        "每次模型请求的输入、输出和思考 token",
                        "Input, output and thinking tokens for every model request"
                    ),
                    onClick = onOpenTokenUsage
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Security,
                    title = AppLanguage.tr("权限级别", "Permissions"),
                    summary = permissionLevel.label + " · " + permissionLevel.description,
                    onClick = onOpenPermissions
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Adjust,
                    title = AppLanguage.tr("悬浮球", "Floating dot"),
                    summary = AppLanguage.tr(
                        "语音助手快捷入口 · 颜色与开关",
                        "Voice assistant shortcut · color & visibility"
                    ),
                    onClick = onOpenFloatingDot
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Settings,
                    title = AppLanguage.tr("MCP 控制服务", "MCP control service"),
                    summary = AppLanguage.tr(
                        "容器对外控制 · MCP ${if (mcpStatus.running) "运行中" else "已停止"}",
                        "Container control · MCP ${if (mcpStatus.running) "running" else "stopped"}"
                    ),
                    onClick = onOpenServices
                )
            }
        }
        item {
            SettingsGroup(AppLanguage.tr("桌面与语言", "Desktop & language")) {
                SettingsMenuRow(
                    icon = Icons.Default.Image,
                    title = AppLanguage.tr("桌面壁纸", "Desktop wallpaper"),
                    summary = AppLanguage.tr(
                        if (hasWallpaper) "已设置" else "默认",
                        if (hasWallpaper) "Set" else "Default"
                    ),
                    onClick = onOpenAppearance
                )
                HorizontalDivider(Modifier.padding(start = 68.dp))
                SettingsMenuRow(
                    icon = Icons.Default.Translate,
                    title = AppLanguage.tr("语言", "Language"),
                    summary = AppLanguage.modeLabel(languageMode),
                    onClick = onOpenLanguage
                )
            }
        }
        item {
            SettingsMenuGroup {
                SettingsMenuRow(
                    icon = Icons.Default.Info,
                    title = AppLanguage.tr("关于", "About"),
                    summary = AppLanguage.tr("版本 $versionName", "Version $versionName"),
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSection(title)
        SettingsMenuGroup(content = content)
    }
}

@Composable
private fun SettingsMenuGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun SettingsMenuRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AppearanceDetailScreen(
    hasWallpaper: Boolean,
    onPickWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("桌面", "Desktop")) }
        item {
            SettingsCard(
                title = AppLanguage.tr("桌面壁纸", "Desktop wallpaper"),
                status = if (hasWallpaper) AppLanguage.tr("已设置", "Set") else AppLanguage.tr("默认", "Default")
            ) {
                Text(
                    AppLanguage.tr("选择图片作为桌面背景。", "Choose an image as the desktop background."),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickWallpaper) {
                        Text(
                            if (hasWallpaper) AppLanguage.tr("更换图片", "Change image")
                            else AppLanguage.tr("选择图片", "Choose image")
                        )
                    }
                    if (hasWallpaper) {
                        TextButton(onClick = onClearWallpaper) { Text(AppLanguage.tr("恢复默认", "Reset to default")) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelDetailScreen(
    configs: List<LlmConfig>,
    activeId: String,
    llmStatus: String,
    onAddLlm: () -> Unit,
    onEditLlm: (LlmConfig) -> Unit,
    onDeleteLlm: (String) -> Unit,
    onSelectLlm: (String) -> Unit
) {
    var actionMode by remember { mutableStateOf(false) }

    if (configs.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    AppLanguage.tr("还没有模型配置", "No model configured yet"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    AppLanguage.tr(
                        "添加一个模型配置后，AI 助手会使用大模型调用 space_* / ui_* " +
                            "工具操作虚拟应用；未配置时只执行本地规则。",
                        "Add a model and the AI assistant can use space_* / ui_* " +
                            "tools to operate virtual apps. Without one it only runs " +
                            "local rules."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onAddLlm) { Text(AppLanguage.tr("添加", "Add")) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("大模型", "Models"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onAddLlm) { Text(AppLanguage.tr("添加", "Add")) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { actionMode = !actionMode }) {
                        Text(if (actionMode) AppLanguage.tr("完成", "Done") else AppLanguage.tr("操作", "Manage"))
                    }
                }
            }
            if (llmStatus.isNotBlank()) {
                item {
                    Text(
                        llmStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(configs, key = { it.id }) { config ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (config.id == activeId) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !actionMode) { onSelectLlm(config.id) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        config.name.ifBlank { AppLanguage.tr("未命名模型", "Unnamed model") },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (config.id == activeId) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            AppLanguage.tr("当前使用", "Active"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    config.protocol.label + " · " +
                                        config.model.ifBlank { AppLanguage.tr("未填模型", "No model") } +
                                        " · " + String.format(
                                            java.util.Locale.getDefault(),
                                            "%,d",
                                            config.contextTokens
                                        ) + " tokens",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    config.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (actionMode) {
                                TextButton(onClick = { onDeleteLlm(config.id) }) {
                                    Text(AppLanguage.tr("删除", "Delete"))
                                }
                                TextButton(onClick = { onEditLlm(config) }) {
                                    Text(AppLanguage.tr("修改", "Edit"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}

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
internal fun AboutDetailScreen() {
    val versionName = rememberAppVersion()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("版本", "Version")) }
        item {
            SettingsCard(title = "NestSpace", status = versionName) {
                Text(
                    AppLanguage.tr(
                    "应用虚拟空间 + 纯净桌面 + 本地/远程 Agent。",
                    "App virtualization + a clean desktop + local/remote agents."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    AppLanguage.tr(
                    "支持多开、独立实例数据与进程级隔离；本地 Agent 支持 Claude 与 " +
                        "OpenAI 兼容接口。",
                    "Supports multiple instances, isolated app data and " +
                        "process-level isolation. Local agents work with Claude and " +
                        "OpenAI-compatible APIs."
                ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "1.0" }
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


@Composable
internal fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
internal fun SettingsCard(
    title: String,
    status: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}
