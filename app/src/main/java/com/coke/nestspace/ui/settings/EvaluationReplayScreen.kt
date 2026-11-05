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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.eval.AgentEvaluationRunner
import com.coke.nestspace.eval.EvalResult
import com.coke.nestspace.eval.EvalStatus
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.replay.ReplayExecutor
import com.coke.nestspace.replay.ReplayItem
import com.coke.nestspace.replay.ReplayManager
import com.coke.nestspace.replay.ReplayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun EvaluationReplayScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val runner = remember { AgentEvaluationRunner(context) }
    val replayStore = remember { ReplayStore(context) }
    var evalResults by remember { mutableStateOf<List<EvalResult>>(emptyList()) }
    var evalReport by remember { mutableStateOf("") }
    var evalRunning by remember { mutableStateOf(false) }
    var replays by remember { mutableStateOf<List<ReplayItem>>(emptyList()) }
    var replayRefresh by remember { mutableIntStateOf(0) }
    var replayMessage by remember { mutableStateOf("") }
    var replayRunning by remember { mutableStateOf(false) }
    var showSaveReplay by remember { mutableStateOf(false) }

    LaunchedEffect(replayRefresh) {
        replays = withContext(Dispatchers.IO) { replayStore.list() }
    }

    val recentTrace by ReplayManager.lastFlow.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("评测", "Evaluation")) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        AppLanguage.tr(
                            "用真实模型和真实容器执行一组小任务，检查工具调用、界面读取和最终回答。",
                            "Run a small task set through the real model and container, checking " +
                                "tool calls, screen reading and the final answer."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (evalRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (evalRunning) {
                                AppLanguage.tr("正在评测…", "Running…")
                            } else {
                                AppLanguage.tr(
                                    "${evalResults.count { it.status == EvalStatus.PASS }} 通过 · " +
                                        "${evalResults.count { it.status == EvalStatus.FAIL }} 失败 · " +
                                        "${evalResults.count { it.status == EvalStatus.SKIP }} 跳过",
                                    "${evalResults.count { it.status == EvalStatus.PASS }} passed · " +
                                        "${evalResults.count { it.status == EvalStatus.FAIL }} failed · " +
                                        "${evalResults.count { it.status == EvalStatus.SKIP }} skipped"
                                )
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    evalRunning = true
                                    evalResults = emptyList()
                                    evalReport = ""
                                    val summary = runner.run { result ->
                                        evalResults = evalResults + result
                                    }
                                    evalReport = summary.render()
                                    evalRunning = false
                                }
                            },
                            enabled = !evalRunning
                        ) {
                            Text(AppLanguage.tr("运行评测", "Run evaluation"))
                        }
                    }
                }
            }
        }
        evalResults.forEach { result ->
            item(key = "eval-${result.task.id}") { EvalResultCard(result) }
        }
        if (evalReport.isNotBlank()) {
            item {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(evalReport)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppLanguage.tr("复制评测报告", "Copy evaluation report"))
                }
            }
            item {
                Text(
                    evalReport,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            SettingsSection(AppLanguage.tr("回放", "Replay"))
        }
        item {
            Text(
                AppLanguage.tr(
                    "回放会按上次成功任务里的工具调用顺序直接执行，不再次调用模型。",
                    "A replay executes the tool calls from the last successful run directly, without the model."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showSaveReplay = true },
                    enabled = recentTrace != null
                ) {
                    Text(AppLanguage.tr("保存最近流程", "Save recent flow"))
                }
                if (replayMessage.isNotBlank()) {
                    Text(
                        replayMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (replays.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        AppLanguage.tr("还没有保存的回放", "No saved replays yet"),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            replays.forEach { item ->
                item(key = "replay-${item.id}") {
                    ReplayCard(
                        item = item,
                        running = replayRunning,
                        onRun = {
                            if (!replayRunning) {
                                replayRunning = true
                                replayMessage = AppLanguage.tr("正在运行回放…", "Running replay…")
                                scope.launch {
                                    try {
                                        val result = ReplayExecutor.run(context, item)
                                        replayMessage = result.message
                                        replayRefresh++
                                    } finally {
                                        replayRunning = false
                                    }
                                }
                            }
                        },
                        onDelete = {
                            if (!replayRunning) {
                                replayStore.delete(item.id)
                                replayRefresh++
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSaveReplay) {
        val trace = ReplayManager.last()
        var name by remember { mutableStateOf(trace?.goal?.take(40).orEmpty()) }
        var description by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveReplay = false },
            title = { Text(AppLanguage.tr("保存回放", "Save replay")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(AppLanguage.tr("名称", "Name")) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(AppLanguage.tr("说明", "Description")) },
                        maxLines = 2
                    )
                    Text(
                        AppLanguage.tr(
                            "${trace?.steps?.size ?: 0} 个工具步骤",
                            "${trace?.steps?.size ?: 0} tool steps"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val current = ReplayManager.last()
                        if (current == null) {
                            replayMessage = AppLanguage.tr(
                                "没有可保存的最近流程",
                                "There is no recent flow to save"
                            )
                        } else {
                            val result = replayStore.save(
                                name = name,
                                description = description,
                                goal = current.goal,
                                steps = current.steps
                            )
                            if (result.isSuccess) {
                                replayMessage = AppLanguage.tr(
                                    "已保存回放：${result.getOrNull()?.name ?: name}",
                                    "Saved replay: ${result.getOrNull()?.name ?: name}"
                                )
                                replayRefresh++
                            } else {
                                replayMessage = AppLanguage.tr(
                                    "保存失败：请填写名称并确认流程包含工具步骤",
                                    "Save failed: enter a name and make sure the flow " +
                                        "contains tool steps"
                                )
                            }
                        }
                        showSaveReplay = false
                    }
                ) {
                    Text(AppLanguage.tr("保存", "Save"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveReplay = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun EvalResultCard(result: EvalResult) {
    val color = when (result.status) {
        EvalStatus.PASS -> MaterialTheme.colorScheme.primary
        EvalStatus.FAIL -> MaterialTheme.colorScheme.error
        EvalStatus.SKIP -> MaterialTheme.colorScheme.outline
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    when (result.status) {
                        EvalStatus.PASS -> AppLanguage.tr("通过", "Pass")
                        EvalStatus.FAIL -> AppLanguage.tr("失败", "Fail")
                        EvalStatus.SKIP -> AppLanguage.tr("跳过", "Skip")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
            if (result.tools.isNotEmpty()) {
                Text(
                    result.tools.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (result.answer.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    result.answer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            result.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${result.durationMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ReplayCard(
    item: ReplayItem,
    running: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (item.description.isNotBlank()) {
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                AppLanguage.tr(
                    "${item.steps.size} 个步骤 · ${formatReplayTime(item.createdAt)}",
                    "${item.steps.size} steps · ${formatReplayTime(item.createdAt)}"
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRun, enabled = !running) {
                    Text(AppLanguage.tr("运行", "Run"))
                }
                TextButton(onClick = onDelete, enabled = !running) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            }
        }
    }
}

private fun formatReplayTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
