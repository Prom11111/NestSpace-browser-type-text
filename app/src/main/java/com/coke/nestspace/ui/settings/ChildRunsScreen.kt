package com.coke.nestspace.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.ChildRun
import com.coke.nestspace.agent.ChildRunPhase
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.agent.ChildRunStore
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent child-agent runs with phase, result preview and cancellation. */
@Composable
internal fun ChildRunsScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    var runs by remember { mutableStateOf<List<ChildRun>>(emptyList()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        while (true) {
            runs = withContext(Dispatchers.IO) {
                ChildRunStore.list(context, limit = 40)
            }
            delay(2_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "子 Agent 只能观察容器和阅读网页，不能点击、输入或启动应用。",
                    "Child agents can observe the container and read web pages, but " +
                        "cannot tap, type or launch apps."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (runs.isEmpty()) {
            item {
                SettingsCard(
                    title = AppLanguage.tr("还没有子任务", "No child runs yet"),
                    status = ""
                ) {
                    Text(
                        AppLanguage.tr(
                            "让助手使用 spawn_agent 工具即可创建子任务。",
                            "Ask the assistant to use spawn_agent to create one."
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(runs, key = { it.id }) { item ->
                val active = ChildRunRegistry.isActive(item.id)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedId = if (expandedId == item.id) null else item.id
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.goal,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = if (expandedId == item.id) 8 else 2
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                childPhaseLabel(item.phase, active),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Text(
                            "${formatChildTime(item.startedAt)} · " +
                                AppLanguage.tr(
                                    "${item.toolMessages} 次工具调用 · 最多 ${item.maxSteps} 轮",
                                    "${item.toolMessages} tool calls · max ${item.maxSteps} steps"
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.result.isNotBlank()) {
                            Text(
                                item.result,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expandedId == item.id) Int.MAX_VALUE else 4
                            )
                        }
                        if (active) {
                            OutlinedButton(
                                onClick = {
                                    ChildRunRegistry.cancel(item.id)
                                    refresh++
                                }
                            ) {
                                Text(AppLanguage.tr("停止子任务", "Stop child"))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun childPhaseLabel(phase: ChildRunPhase, active: Boolean): String = when {
    active -> AppLanguage.tr("运行中", "Running")
    phase == ChildRunPhase.RUNNING -> AppLanguage.tr("状态未知", "Unknown")
    phase == ChildRunPhase.COMPLETED -> AppLanguage.tr("已完成", "Done")
    phase == ChildRunPhase.INTERRUPTED -> AppLanguage.tr("已中断", "Interrupted")
    else -> AppLanguage.tr("未知", "Unknown")
}

private fun formatChildTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
