package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.coke.nestspace.agent.RunEvent
import com.coke.nestspace.agent.RunRecordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
internal fun RunHistoryScreen(
    context: Context,
    busy: Boolean,
    onRestore: (RunSummary, List<RunEvent>) -> Unit
) {
    var runs by remember { mutableStateOf<List<RunSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<RunSummary?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true
        runs = withContext(Dispatchers.IO) { RunRecordStore.recent(context, 30) }
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "选择一条记录，可以恢复到当前对话或删除。",
                    "Choose a run to restore it into the current conversation or delete it."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            loading -> item {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            runs.isEmpty() -> item {
                Text(
                    AppLanguage.tr("还没有运行记录。", "No runs yet."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> items(runs, key = { it.id }) { run ->
                HistoryRunCard(
                    run = run,
                    restoreEnabled = !busy,
                    onRestore = {
                        scope.launch {
                            val events = withContext(Dispatchers.IO) {
                                RunRecordStore.events(context, run.id)
                            }
                            onRestore(run, events)
                        }
                    },
                    onDelete = { pendingDelete = run }
                )
            }
        }
    }

    pendingDelete?.let { run ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(AppLanguage.tr("删除这条历史？", "Delete this run?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将永久删除“${run.command.take(80)}”及其运行详情。",
                        "This permanently deletes \"${run.command.take(80)}\" and its run details."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                RunRecordStore.delete(context, run.id)
                            }
                            refreshKey++
                        }
                    }
                ) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun HistoryRunCard(
    run: RunSummary,
    restoreEnabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.command,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
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
            Text(
                "${formatRunTime(run.startedAt)} · " +
                    formatRunDuration(run.durationMs) + " · " +
                    AppLanguage.tr("${run.eventCount} 条记录", "${run.eventCount} events"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (run.summary.isNotBlank()) {
                Text(
                    run.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDelete) {
                    Text(
                        AppLanguage.tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onRestore, enabled = restoreEnabled) {
                    Text(AppLanguage.tr("恢复", "Restore"))
                }
            }
            if (!restoreEnabled) {
                Text(
                    AppLanguage.tr(
                        "当前任务结束后才能恢复历史。",
                        "Finish the current task before restoring a run."
                    ),
                    style = MaterialTheme.typography.labelSmall,
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
