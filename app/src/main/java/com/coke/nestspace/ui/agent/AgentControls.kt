package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort

@Composable
internal fun ControlChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp)
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
internal fun ModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    configs: List<LlmConfig>,
    activeLlmId: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 280.dp, max = 330.dp)
    ) {
        Text(
            AppLanguage.tr("选择模型", "Choose model"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (configs.isEmpty()) {
            DropdownMenuItem(
                text = { Text(AppLanguage.tr("添加模型…", "Add model…")) },
                onClick = onOpenSettings,
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
            )
        } else {
            configs.forEach { config ->
                val active = config.id == activeLlmId
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    config.name.ifBlank { config.model },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                                if (!config.isConfigured) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        AppLanguage.tr("未配置", "Not configured"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text(
                                "${config.model} · ${config.protocol.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = { onSelect(config.id) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    trailingIcon = if (active) {
                        { Icon(Icons.Rounded.Check, contentDescription = AppLanguage.tr("当前模型", "Current model")) }
                    } else {
                        null
                    }
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(AppLanguage.tr("管理模型…", "Manage models…")) },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
        )
    }
}

@Composable
internal fun PermissionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    current: ToolPermissionLevel,
    onSelect: (ToolPermissionLevel) -> Unit,
    onOpenSettings: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)
    ) {
        Text(
            AppLanguage.tr("确认级别", "Confirmation level"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ToolPermissionLevel.entries.forEach { level ->
            val active = level == current
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            level.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            level.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = { onSelect(level) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = if (active) {
                    {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = AppLanguage.tr("当前级别", "Current level")
                        )
                    }
                } else {
                    null
                }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(AppLanguage.tr("自定义确认项…", "Customize confirmations…")) },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
        )
    }
}

@Composable
internal fun ReasoningMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    current: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 260.dp, max = 310.dp)
    ) {
        Text(
            AppLanguage.tr("思考程度", "Reasoning effort"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ReasoningEffort.entries.forEach { effort ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            effort.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (effort == current) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                        Text(
                            effort.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = { onSelect(effort) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = if (effort == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = if (effort == current) {
                    { Icon(Icons.Rounded.Check, contentDescription = AppLanguage.tr("当前思考程度", "Current reasoning effort")) }
                } else {
                    null
                }
            )
        }
    }
}
