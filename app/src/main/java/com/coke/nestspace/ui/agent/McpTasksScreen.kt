package com.coke.nestspace.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.task.McpTaskClassifier
import com.coke.nestspace.task.OperationTaskRegistry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Task center for work submitted through the external MCP control plane. */
@Composable
internal fun McpTasksScreen() {
    val tasks by OperationTaskRegistry.tasksFlow.collectAsState()
    val roots = remember(tasks) { McpTaskClassifier.roots(tasks) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val mirrorSlot = remember(tasks) {
        HeadlessMirrorWindow.activeSlot().takeIf { it >= 0 }
            ?: ContainerProcessPool.lastSlot()
    }
    DisposableEffect(Unit) {
        HeadlessMirrorWindow.setAssistantVisible(true)
        onDispose { HeadlessMirrorWindow.setAssistantVisible(false) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "这里单独显示 MCP 客户端创建的任务，不占用 AI 对话流。",
                    "Tasks created by MCP clients appear here instead of in the AI conversation."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (roots.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            AppLanguage.tr("还没有 MCP 任务", "No MCP tasks yet"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            AppLanguage.tr(
                                "通过 MCP 启动助手任务或调用工具后，任务会显示在这里。",
                                "Assistant tasks and tool calls started through MCP will appear here."
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(roots, key = { it.id }) { root ->
                val children = McpTaskClassifier.children(root, tasks)
                val activeChildren = children.count { !it.terminal }
                val expanded = expandedId == root.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedId = if (expanded) null else root.id
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = stateColor(root.state)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    root.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = if (expanded) 6 else 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOfNotNull(
                                        taskStateLabel(root.state),
                                        if (children.isNotEmpty()) {
                                            AppLanguage.tr(
                                                "${children.size} 个子任务",
                                                "${children.size} child tasks"
                                            )
                                        } else {
                                            null
                                        },
                                        formatTaskTime(root.createdAt)
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        root.progressPercent?.let { percent ->
                            Text(
                                "$percent%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val detail = root.error?.takeIf { it.isNotBlank() }
                            ?: root.message.takeIf { it.isNotBlank() }
                        if (!detail.isNullOrBlank()) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (expanded) {
                            children.forEach { child ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        child.title,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        taskStateLabel(child.state),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = stateColor(child.state)
                                    )
                                }
                            }
                        }
                        if (root.id == roots.firstOrNull()?.id &&
                            mirrorSlot != null
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        HeadlessMirrorWindow.promoteToPanel(
                                            context,
                                            mirrorSlot
                                        )
                                    }
                                }
                            ) {
                                Text(AppLanguage.tr("打开小窗", "Open window"))
                            }
                        }
                        if (root.terminal) {
                            TextButton(
                                onClick = {
                                    OperationTaskRegistry.dismiss(root.id)
                                }
                            ) {
                                Text(AppLanguage.tr("删除任务", "Delete task"))
                            }
                        }
                        if (!root.terminal) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        OperationTaskRegistry.requestCancel(
                                            root.id,
                                            "user cancelled"
                                        )
                                    }
                                ) {
                                    Text(AppLanguage.tr("取消任务", "Cancel task"))
                                }
                                if (activeChildren > 0) {
                                    OutlinedButton(
                                        onClick = {
                                            children.filterNot { it.terminal }
                                                .forEach { child ->
                                                    OperationTaskRegistry.requestCancel(
                                                        child.id,
                                                        "user cancelled child task"
                                                    )
                                                }
                                        }
                                    ) {
                                        Text(
                                            AppLanguage.tr(
                                                "取消子任务",
                                                "Cancel child tasks"
                                            )
                                        )
                                    }
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
private fun stateColor(state: OperationTaskRegistry.State) = when {
    state == OperationTaskRegistry.State.FAILED ||
        state == OperationTaskRegistry.State.CANCELLED ->
        MaterialTheme.colorScheme.error
    state.terminal -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.tertiary
}

private fun taskStateLabel(state: OperationTaskRegistry.State): String = when (state) {
    OperationTaskRegistry.State.QUEUED -> AppLanguage.tr("排队中", "Queued")
    OperationTaskRegistry.State.PREPARING -> AppLanguage.tr("准备中", "Preparing")
    OperationTaskRegistry.State.RUNNING -> AppLanguage.tr("运行中", "Running")
    OperationTaskRegistry.State.WAITING_USER -> AppLanguage.tr("等待用户", "Waiting for user")
    OperationTaskRegistry.State.PAUSED -> AppLanguage.tr("已暂停", "Paused")
    OperationTaskRegistry.State.CANCELLING -> AppLanguage.tr("正在取消", "Cancelling")
    OperationTaskRegistry.State.FINALIZING -> AppLanguage.tr("正在完成", "Finalizing")
    OperationTaskRegistry.State.SUCCEEDED -> AppLanguage.tr("已完成", "Done")
    OperationTaskRegistry.State.FAILED -> AppLanguage.tr("失败", "Failed")
    OperationTaskRegistry.State.CANCELLED -> AppLanguage.tr("已取消", "Cancelled")
}

private fun formatTaskTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
