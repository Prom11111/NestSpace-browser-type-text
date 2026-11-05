package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.schedule.ScheduleRepeat
import com.coke.nestspace.schedule.ScheduledTask
import com.coke.nestspace.schedule.ScheduledTaskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ScheduledTasksScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    val manager = remember { ScheduledTaskManager(context) }
    var tasks by remember { mutableStateOf<List<ScheduledTask>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ScheduledTask?>(null) }
    val settingsRevision by AppSettingsEvents.revision.collectAsState()

    LaunchedEffect(refresh) {
        tasks = withContext(Dispatchers.IO) { manager.list() }
    }
    LaunchedEffect(settingsRevision) {
        if (settingsRevision > 0) refresh++
    }

    fun reload() {
        refresh++
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(AppLanguage.tr("定时任务", "Scheduled tasks"))
        }
        item {
            Text(
                AppLanguage.tr(
                    "让助手创建，例如“每天早上 9 点看看天气，下雨才告诉我”。任务到点会在原对话里执行，并发送结果通知。",
                    "Ask the assistant to create one, e.g. \"check the weather every morning at 9\". " +
                        "It runs in the original conversation and posts a result notification."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (tasks.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        AppLanguage.tr("还没有定时任务", "No scheduled tasks yet"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            tasks.forEach { task ->
                item(key = task.id) {
                    ScheduledTaskCard(
                        task = task,
                        onEnabled = {
                            manager.setEnabled(task.id, it)
                            reload()
                        },
                        onRunNow = {
                            manager.runNow(task.id)
                            reload()
                        },
                        onDelete = { pendingDelete = task }
                    )
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "精确闹钟需要系统允许；不允许时会在相近时间执行。",
                    "Exact alarms need system permission; without it the task runs near the scheduled time."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(AppLanguage.tr("删除定时任务？", "Delete scheduled task?")) },
            text = { Text(task.goal) },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.delete(task.id)
                        pendingDelete = null
                        reload()
                    }
                ) {
                    Text(AppLanguage.tr("删除", "Delete"))
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
private fun ScheduledTaskCard(
    task: ScheduledTask,
    onEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.goal,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${repeatLabel(task)} · ${nextLabel(task)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = task.enabled,
                    onCheckedChange = onEnabled,
                    // A one-shot task that already ran cannot be scheduled
                    // again by toggling it; use Run now or create a new task.
                    enabled = !(task.repeat == ScheduleRepeat.ONCE &&
                        task.lastStatus in setOf(
                            "running", "done", "failed", "cancelled", "interrupted", "missed"
                        ))
                )
            }
            if (task.lastStatus.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    AppLanguage.tr(
                        "上次：${scheduleStatusLabel(task.lastStatus)}",
                        "Last: ${scheduleStatusLabel(task.lastStatus)}"
                    ) + if (task.lastAnswer.isNotBlank()) " · ${task.lastAnswer.take(100)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onRunNow,
                    enabled = task.lastStatus != "running"
                ) {
                    Text(AppLanguage.tr("立即执行", "Run now"))
                }
                TextButton(onClick = onDelete) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            }
        }
    }
}

private fun repeatLabel(task: ScheduledTask): String = when (task.repeat) {
    ScheduleRepeat.ONCE -> AppLanguage.tr("一次", "Once")
    ScheduleRepeat.DAILY -> AppLanguage.tr("每天", "Daily")
    ScheduleRepeat.WEEKLY -> {
        val days = task.weekdays.mapNotNull { day ->
            when (day) {
                1 -> AppLanguage.tr("一", "Mon")
                2 -> AppLanguage.tr("二", "Tue")
                3 -> AppLanguage.tr("三", "Wed")
                4 -> AppLanguage.tr("四", "Thu")
                5 -> AppLanguage.tr("五", "Fri")
                6 -> AppLanguage.tr("六", "Sat")
                7 -> AppLanguage.tr("日", "Sun")
                else -> null
            }
        }.joinToString(if (AppLanguage.isChinese()) "" else ", ")
        AppLanguage.tr("每周$days", "Weekly $days")
    }
    ScheduleRepeat.INTERVAL -> AppLanguage.tr(
        "每 ${task.intervalMinutes} 分钟",
        "Every ${task.intervalMinutes} min"
    )
}

private fun scheduleStatusLabel(status: String): String = when (status) {
    "done" -> AppLanguage.tr("完成", "Done")
    "failed" -> AppLanguage.tr("失败", "Failed")
    "cancelled" -> AppLanguage.tr("已取消", "Cancelled")
    "interrupted" -> AppLanguage.tr("中断", "Interrupted")
    "running" -> AppLanguage.tr("进行中", "Running")
    "missed" -> AppLanguage.tr("错过", "Missed")
    else -> status
}

private fun nextLabel(task: ScheduledTask): String {
    if (task.lastStatus == "running") {
        return AppLanguage.tr("执行中", "Running")
    }
    if (!task.enabled || task.nextAt <= 0) {
        return when {
            task.repeat == ScheduleRepeat.ONCE && task.lastStatus == "done" ->
                AppLanguage.tr("已完成", "Done")
            task.repeat == ScheduleRepeat.ONCE && task.lastStatus == "interrupted" ->
                AppLanguage.tr("已中断", "Interrupted")
            task.repeat == ScheduleRepeat.ONCE && task.lastStatus == "missed" ->
                AppLanguage.tr("已错过", "Missed")
            else -> AppLanguage.tr("已暂停", "Paused")
        }
    }
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(task.nextAt))
}
