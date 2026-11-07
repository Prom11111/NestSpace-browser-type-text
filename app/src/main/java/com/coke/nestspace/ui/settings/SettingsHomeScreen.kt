package com.coke.nestspace.ui.settings

import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.i18n.AppLanguageMode
import com.coke.nestspace.location.MockLocationSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal enum class SettingsCategory {
    APPS,
    ASSISTANT,
    SYSTEM,
    CONTAINER_ADVANCED,
    ASSISTANT_ADVANCED
}

@Composable
internal fun SettingsHomeScreen(
    category: SettingsCategory?,
    appCount: Int,
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
    onOpenAbout: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit
) {
    val versionName = rememberAppVersion()
    AppLanguage.isChinese()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (category) {
            null -> {
                item {
                    SettingsGroup(AppLanguage.tr("常用", "Common")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Apps,
                            title = AppLanguage.tr("应用管理", "App management"),
                            summary = AppLanguage.tr("$appCount 个应用", "$appCount apps"),
                            onClick = onOpenApps
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                        SettingsMenuRow(
                            icon = Icons.Default.SmartToy,
                            title = AppLanguage.tr("模型设置", "Model settings"),
                            summary = if (llmConfig.isConfigured) {
                                llmConfig.protocol.label + " · " + llmConfig.model
                            } else {
                                AppLanguage.tr("未配置模型", "No model configured")
                            },
                            onClick = onOpenModel
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
                            icon = Icons.Default.Settings,
                            title = AppLanguage.tr("MCP 控制服务", "MCP control service"),
                            summary = AppLanguage.tr(
                                "容器对外控制 · MCP ${if (mcpStatus.running) "运行中" else "已停止"}",
                                "Container control · MCP ${if (mcpStatus.running) "running" else "stopped"}"
                            ),
                            onClick = onOpenServices
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
                            icon = Icons.Default.Image,
                            title = AppLanguage.tr("外观", "Appearance"),
                            summary = if (hasWallpaper) {
                                AppLanguage.tr("已设置壁纸", "Wallpaper set")
                            } else {
                                AppLanguage.tr("默认壁纸", "Default wallpaper")
                            },
                            onClick = onOpenAppearance
                        )
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("更多分类", "More categories")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Apps,
                            title = AppLanguage.tr("应用与容器", "Apps & containers"),
                            summary = AppLanguage.tr("$appCount 个应用", "$appCount apps"),
                            onClick = { onOpenCategory(SettingsCategory.APPS) }
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                        SettingsMenuRow(
                            icon = Icons.Default.SmartToy,
                            title = AppLanguage.tr("AI 助手", "AI assistant"),
                            summary = AppLanguage.tr(
                                "${if (llmConfig.isConfigured) llmConfig.model else "未配置模型"} · " +
                                    "MCP ${if (mcpStatus.running) "运行中" else "已停止"}",
                                "${if (llmConfig.isConfigured) llmConfig.model else "No model"} · " +
                                    "MCP ${if (mcpStatus.running) "running" else "stopped"}"
                            ),
                            onClick = { onOpenCategory(SettingsCategory.ASSISTANT) }
                        )
                        HorizontalDivider(Modifier.padding(start = 68.dp))
                        SettingsMenuRow(
                            icon = Icons.Default.Settings,
                            title = AppLanguage.tr("系统与网络", "System & network"),
                            summary = AppLanguage.tr(
                                "${AppLanguage.modeLabel(languageMode)} · " +
                                    if (hasWallpaper) "已设置壁纸" else "默认壁纸",
                                "${AppLanguage.modeLabel(languageMode)} · " +
                                    if (hasWallpaper) "wallpaper set" else "default wallpaper"
                            ),
                            onClick = { onOpenCategory(SettingsCategory.SYSTEM) }
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

            SettingsCategory.APPS -> {
                item {
                    SettingsGroup(AppLanguage.tr("应用", "Apps")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Apps,
                            title = AppLanguage.tr("应用管理", "App management"),
                            summary = AppLanguage.tr("$appCount 个应用", "$appCount apps"),
                            onClick = onOpenApps
                        )
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("运行", "Runtime")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("更多", "More")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Build,
                            title = AppLanguage.tr("容器高级", "Container advanced"),
                            summary = AppLanguage.tr(
                                "宿主控制与容器自检",
                                "Host control and container diagnostics"
                            ),
                            onClick = { onOpenCategory(SettingsCategory.CONTAINER_ADVANCED) }
                        )
                    }
                }
            }

            SettingsCategory.ASSISTANT -> {
                item {
                    SettingsGroup(AppLanguage.tr("模型", "Model")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("使用偏好", "Preferences")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("更多", "More")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Extension,
                            title = AppLanguage.tr("高级助手工具", "Advanced assistant tools"),
                            summary = AppLanguage.tr(
                                "MCP、记忆、自动化、评测与运行统计",
                                "MCP, memory, automation, evaluation and run statistics"
                            ),
                            onClick = { onOpenCategory(SettingsCategory.ASSISTANT_ADVANCED) }
                        )
                    }
                }
            }

            SettingsCategory.CONTAINER_ADVANCED -> {
                item {
                    SettingsGroup(AppLanguage.tr("设备与控制", "Device & control")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("诊断", "Diagnostics")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Build,
                            title = AppLanguage.tr("容器自检", "Container self-check"),
                            summary = AppLanguage.tr(
                                "检查启动、界面、截图、进程和 WebView",
                                "Check launch, screen, screenshots, processes and WebView"
                            ),
                            onClick = onOpenSelfCheck
                        )
                    }
                }
            }

            SettingsCategory.ASSISTANT_ADVANCED -> {
                item {
                    SettingsGroup(AppLanguage.tr("工具与记忆", "Tools & memory")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("自动化与运行", "Automation & runs")) {
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
                    }
                }
                item {
                    SettingsGroup(AppLanguage.tr("外部连接", "External access")) {
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
            }

            SettingsCategory.SYSTEM -> {
                item {
                    SettingsGroup(AppLanguage.tr("外观与语言", "Appearance & language")) {
                        SettingsMenuRow(
                            icon = Icons.Default.Image,
                            title = AppLanguage.tr("外观", "Appearance"),
                            summary = AppLanguage.tr(
                                "壁纸与容器顶部提示",
                                "Wallpaper and container top indicator"
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
                    SettingsGroup(AppLanguage.tr("网络", "Network")) {
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
