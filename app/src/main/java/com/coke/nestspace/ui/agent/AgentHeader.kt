package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.R
import com.coke.nestspace.data.LlmConfig

@Composable
internal fun AgentHeader(
    llmConfig: LlmConfig,
    busy: Boolean,
    externalTaskBusy: Boolean,
    onClear: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenMcpTasks: () -> Unit,
    activeMcpTaskCount: Int,
    onOpenSettings: () -> Unit
) {
    val status = when {
        !llmConfig.isConfigured -> AppLanguage.tr("欢迎使用", "Welcome")
        externalTaskBusy -> AppLanguage.tr("MCP 任务运行中…", "MCP task running…")
        busy -> AppLanguage.tr("正在处理，请稍候…", "Working on it…")
        else -> AppLanguage.tr("在线 · 随时可以开始", "Online · ready to help")
    }
    val dotColor = when {
        !llmConfig.isConfigured -> Color(0xFFFF9F0A)
        busy -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF34C759)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp)) {
            Image(
                painter = painterResource(R.drawable.ai_assistant),
                contentDescription = AppLanguage.tr("NestSpace 助手", "NestSpace Assistant"),
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .padding(2.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                AppLanguage.tr("NestSpace 助手", "NestSpace Assistant"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClear, enabled = !busy) {
            Icon(
                Icons.Rounded.AddComment,
                contentDescription = AppLanguage.tr("新对话", "New conversation"),
                tint = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = onOpenHistory) {
            Icon(
                Icons.Rounded.History,
                contentDescription = AppLanguage.tr("运行记录", "Run history"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenMcpTasks) {
            BadgedBox(
                badge = {
                    if (activeMcpTaskCount > 0) {
                        Badge { Text(activeMcpTaskCount.toString()) }
                    }
                }
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ListAlt,
                    contentDescription = AppLanguage.tr("MCP 任务", "MCP tasks"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = AppLanguage.tr("AI 助手设置", "AI assistant settings"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
