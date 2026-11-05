package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coke.nestspace.container.ContainerCheck
import com.coke.nestspace.container.ContainerCheckResult
import com.coke.nestspace.container.ContainerCheckStatus
import com.coke.nestspace.container.ContainerSelfCheckRunner
import com.coke.nestspace.i18n.AppLanguage

@Composable
internal fun ContainerSelfCheckScreen() {
    AppLanguage.isChinese()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var results by remember { mutableStateOf<List<ContainerCheckResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }

    LaunchedEffect(request) {
        running = true
        results = emptyList()
        report = ""
        val runner = ContainerSelfCheckRunner(context)
        val finalReport = runner.run { result ->
            results = (results.filterNot { it.id == result.id } + result)
                .sortedBy { it.index }
        }
        report = finalReport.render(context)
        running = false
    }

    val passed = results.count { it.status == ContainerCheckStatus.PASS }
    val partial = results.count { it.status == ContainerCheckStatus.PARTIAL }
    val failed = results.count { it.status == ContainerCheckStatus.FAIL }
    val skipped = results.count { it.status == ContainerCheckStatus.SKIPPED }
    val finished = results.size == ContainerCheck.entries.size && !running

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(AppLanguage.tr("容器自检", "Container self-check"))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        AppLanguage.tr(
                            "检查容器能否启动虚拟应用、读取界面、截图、使用独立进程、WebView 和 MCP。",
                            "Checks whether the container can launch apps, read the screen, take " +
                                "screenshots, use isolated processes, WebView and MCP."
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            when {
                                running -> AppLanguage.tr("正在检查…", "Checking…")
                                results.isEmpty() -> AppLanguage.tr("准备开始", "Ready")
                                else -> AppLanguage.tr(
                                    "$passed 通过 · $partial 部分 · $failed 失败 · $skipped 跳过",
                                    "$passed passed · $partial partial · $failed failed · $skipped skipped"
                                )
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { request++ },
                            enabled = !running
                        ) {
                            Text(
                                if (results.isEmpty()) {
                                    AppLanguage.tr("开始", "Start")
                                } else {
                                    AppLanguage.tr("重新检查", "Re-check")
                                }
                            )
                        }
                    }
                }
            }
        }
        results.forEach { result ->
            item(key = result.id) {
                SelfCheckRow(result)
            }
        }
        if (finished) {
            item {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(report))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(AppLanguage.tr("复制报告", "Copy report"))
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "报告只包含设备版本、检查结果和容器状态，不包含账号、聊天内容或截图。",
                    "The report contains device/version info, check results and container state; " +
                        "it does not include accounts, chats, or screenshots."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SelfCheckRow(result: ContainerCheckResult) {
    val color = when (result.status) {
        ContainerCheckStatus.PASS -> MaterialTheme.colorScheme.primary
        ContainerCheckStatus.PARTIAL -> Color(0xFFFF9F0A)
        ContainerCheckStatus.FAIL -> MaterialTheme.colorScheme.error
        ContainerCheckStatus.SKIPPED -> MaterialTheme.colorScheme.outline
        ContainerCheckStatus.RUNNING -> MaterialTheme.colorScheme.primary
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                when (result.status) {
                    ContainerCheckStatus.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = color
                    )
                    else -> Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = color.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "${result.index}",
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        statusLabel(result.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                }
                if (result.detail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                result.advice?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: ContainerCheckStatus): String = when (status) {
    ContainerCheckStatus.PASS -> AppLanguage.tr("通过", "Pass")
    ContainerCheckStatus.PARTIAL -> AppLanguage.tr("部分", "Partial")
    ContainerCheckStatus.FAIL -> AppLanguage.tr("失败", "Fail")
    ContainerCheckStatus.SKIPPED -> AppLanguage.tr("跳过", "Skipped")
    ContainerCheckStatus.RUNNING -> AppLanguage.tr("检查中", "Checking")
}
