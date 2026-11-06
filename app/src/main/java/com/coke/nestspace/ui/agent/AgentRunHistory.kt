package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.RunSummary
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import com.coke.nestspace.agent.RunEvent
import com.coke.nestspace.agent.RunRecordStore
import com.coke.nestspace.data.AgentMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun LastRunCard(
    run: RunSummary,
    onOpenHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenHistory),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("本次运行", "This run"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        runStatusLabel(run.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatRunDuration(run.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    runMetricsLine(run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = AppLanguage.tr("关闭汇总", "Dismiss summary"),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RunHistoryDialog(context: Context, onDismiss: () -> Unit) {
    var runs by remember { mutableStateOf<List<RunSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<RunSummary?>(null) }
    var events by remember { mutableStateOf<List<RunEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runs = withContext(Dispatchers.IO) { RunRecordStore.recent(context, 20) }
        loading = false
    }
    LaunchedEffect(selected) {
        val id = selected?.id
        events = if (id == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { RunRecordStore.events(context, id) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    IconButton(onClick = { selected = null }) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = AppLanguage.tr("返回", "Back")
                        )
                    }
                }
                Text(
                    if (selected == null) {
                        AppLanguage.tr("运行记录", "Run history")
                    } else {
                        AppLanguage.tr("运行详情", "Run details")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            val run = selected
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }

                run == null && runs.isEmpty() -> Text(
                    AppLanguage.tr("还没有运行记录。", "No runs yet.")
                )

                run == null -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(runs, key = { it.id }) { item ->
                        RunSummaryRow(item) { selected = item }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            run.command,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${runStatusLabel(run.status)} · " +
                                "${formatRunTime(run.startedAt)} · " +
                                formatRunDuration(run.durationMs) + " · " +
                                AppLanguage.tr(
                                    "${run.eventCount} 条记录",
                                    "${run.eventCount} events"
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (run.hasMetrics) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                runMetricsLine(run),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (events.isEmpty()) {
                        item {
                            Text(
                                AppLanguage.tr("没有详细记录。", "No detailed events."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(events) { event -> RunEventRow(event) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("关闭", "Close"))
            }
        }
    )
}

@Composable
private fun RunSummaryRow(run: RunSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    runStatusLabel(run.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatRunTime(run.startedAt)} · " +
                    formatRunDuration(run.durationMs) + " · " +
                    AppLanguage.tr("${run.eventCount} 条记录", "${run.eventCount} events"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (run.hasMetrics) {
                Spacer(Modifier.height(3.dp))
                Text(
                    runMetricsLine(run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (run.summary.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    run.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RunEventRow(event: RunEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(9.dp)) {
            Text(
                eventRoleLabel(event.role),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (event.role) {
                    AgentMessage.Role.ERROR.name -> MaterialTheme.colorScheme.error
                    AgentMessage.Role.TOOL.name -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    event.text.take(2_000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (event.role == AgentMessage.Role.TOOL.name) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun runStatusLabel(status: String): String = when (status) {
    RunRecordStore.STATUS_DONE -> AppLanguage.tr("已完成", "Done")
    RunRecordStore.STATUS_STOPPED -> AppLanguage.tr("已停止", "Stopped")
    RunRecordStore.STATUS_ERROR -> AppLanguage.tr("失败", "Failed")
    RunRecordStore.STATUS_INTERRUPTED -> AppLanguage.tr("中断", "Interrupted")
    RunRecordStore.STATUS_RUNNING -> AppLanguage.tr("进行中", "Running")
    else -> status
}

private fun eventRoleLabel(role: String): String = when (role) {
    AgentMessage.Role.USER.name -> AppLanguage.tr("你", "You")
    AgentMessage.Role.AGENT.name -> AppLanguage.tr("助手", "Assistant")
    AgentMessage.Role.TOOL.name -> AppLanguage.tr("工具", "Tool")
    AgentMessage.Role.ERROR.name -> AppLanguage.tr("错误", "Error")
    else -> role
}

private fun formatRunTime(at: Long): String =
    if (at <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

private fun formatRunDuration(ms: Long): String {
    if (ms <= 0) return "—"
    return when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1_000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    }
}

private fun formatMetricTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.0fk", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun runMetricsLine(run: RunSummary): String {
    if (!run.hasMetrics) return ""
    val parts = mutableListOf<String>()
    parts += AppLanguage.tr(
        "模型 ${run.modelRequests} 轮",
        "${run.modelRequests} model rounds"
    )
    parts += AppLanguage.tr(
        "工具 ${run.toolCalls} 次",
        "${run.toolCalls} tool calls"
    )
    parts += AppLanguage.tr(
        "输入 ${formatMetricTokens(run.inputTokens)} · 输出 ${formatMetricTokens(run.outputTokens)}",
        "in ${formatMetricTokens(run.inputTokens)} · out ${formatMetricTokens(run.outputTokens)}"
    )
    if (run.reasoningTokens > 0) {
        parts += AppLanguage.tr(
            "思考 ${formatMetricTokens(run.reasoningTokens)}",
            "thinking ${formatMetricTokens(run.reasoningTokens)}"
        )
    }
    if (run.firstFeedbackMs > 0) {
        parts += AppLanguage.tr(
            "首响 ${formatRunDuration(run.firstFeedbackMs)}",
            "first ${formatRunDuration(run.firstFeedbackMs)}"
        )
    }
    return parts.joinToString(" · ")
}
