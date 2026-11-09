package com.coke.nestspace.ui.running

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class AppProcessGroup(
    val packageName: String,
    val instanceId: String,
    val label: String,
    val icon: ImageBitmap?,
    val processes: List<ContainerProcessPool.RunningSlot>
) {
    val totalKb: Long get() = processes.sumOf { it.memoryKb }
    val hasActive: Boolean get() = processes.any { it.active }
}

/**
 * Shows the live container slots grouped by app/instance. Each process the
 * target app declares (main, :pushservice, :download, ...) runs in its own
 * slot, so the group expands into one row per process with its own close
 * action; the whole group can also be closed at once.
 */
@Composable
internal fun RunningAppsScreen(apps: List<VirtualApp>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var slots by remember {
        // Do not touch /proc or ActivityManager during composition; the
        // LaunchedEffect below loads the first snapshot off the main thread.
        mutableStateOf(emptyList<ContainerProcessPool.RunningSlot>())
    }
    var confirmProcess by remember {
        mutableStateOf<ContainerProcessPool.RunningSlot?>(null)
    }
    var confirmGroup by remember {
        mutableStateOf<AppProcessGroup?>(null)
    }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    AppLanguage.isChinese()

    LaunchedEffect(Unit) {
        while (true) {
            slots = withContext(Dispatchers.IO) {
                ContainerProcessPool.runningSlots(context)
            }
            delay(2_000)
        }
    }

    fun refresh() {
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                ContainerProcessPool.runningSlots(context)
            }
            slots = next
        }
    }

    if (slots.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    AppLanguage.tr("没有正在运行的容器", "No containers running"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    AppLanguage.tr(
                        "从桌面启动虚拟应用后，这里会按应用显示运行中的进程和内存占用。",
                        "Launch a virtual app to see its processes and memory usage by app."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val groups = slots
        .groupBy { it.packageName to it.instanceId }
        .map { (key, processes) ->
            val app = apps.firstOrNull { it.packageName == key.first }
            AppProcessGroup(
                packageName = key.first,
                instanceId = key.second,
                label = app?.label ?: key.first,
                icon = app?.icon,
                processes = processes.sortedBy { it.slot }
            )
        }
        .sortedBy { it.label.lowercase() }

    val instanceCounts = groups.groupingBy { it.packageName }.eachCount()
    val runningAppCount = slots.map { it.packageName }.distinct().size
    val totalKb = slots.sumOf { it.memoryKb }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        AppLanguage.trCount(
                            slots.size,
                            "正在运行 $runningAppCount 个应用 · ${slots.size} 个容器槽位",
                            "$runningAppCount app · 1 container slot running",
                            "$runningAppCount apps · ${slots.size} container slots running"
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        AppLanguage.tr(
                            "合计占用 ${formatMemory(totalKb)}",
                            "Using ${formatMemory(totalKb)} in total"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(groups, key = { "${it.packageName}@${it.instanceId}" }) { group ->
            val groupKey = "${group.packageName}@${group.instanceId}"
            val expanded = expandedGroups[groupKey] == true
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedGroups[groupKey] = !expanded
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(
                            icon = group.icon,
                            label = group.label,
                            size = 42
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    group.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if ((instanceCounts[group.packageName] ?: 0) > 1) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        AppLanguage.tr(
                                            "实例 ${group.instanceId}",
                                            "Instance ${group.instanceId}"
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (group.hasActive) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        AppLanguage.tr("前台", "Foreground"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                AppLanguage.trCount(
                                    group.processes.size,
                                    "${group.processes.size} 个进程 · ${formatMemory(group.totalKb)}",
                                    "1 process · ${formatMemory(group.totalKb)}",
                                    "${group.processes.size} processes · ${formatMemory(group.totalKb)}"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (expanded) Icons.Default.ExpandLess
                            else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) {
                                AppLanguage.tr("收起", "Collapse")
                            } else {
                                AppLanguage.tr("展开", "Expand")
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = { confirmGroup = group }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = AppLanguage.tr("全部关闭", "Close all"),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (expanded) {
                        group.processes.forEach { slot ->
                            HorizontalDivider(Modifier.padding(start = 70.dp))
                            ProcessRow(slot = slot) { confirmProcess = slot }
                        }
                    }
                }
            }
        }
    }

    confirmProcess?.let { slot ->
        val app = apps.firstOrNull { it.packageName == slot.packageName }
        val label = app?.label ?: slot.packageName
        AlertDialog(
            onDismissRequest = { confirmProcess = null },
            title = {
                Text(AppLanguage.tr("关闭 $label？", "Close $label?"))
            },
            text = {
                Text(
                    AppLanguage.tr(
                        "将结束该容器进程并释放内存，应用数据会保留。",
                        "This ends the container process and frees its memory. " +
                            "App data is kept."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmProcess = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ContainerProcessPool.closeSlot(context, slot.slot)
                            }
                            refresh()
                        }
                    }
                ) {
                    Text(
                        AppLanguage.tr("关闭", "Close"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmProcess = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }

    confirmGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { confirmGroup = null },
            title = {
                Text(
                    AppLanguage.tr(
                        "关闭 ${group.label} 的 ${group.processes.size} 个进程？",
                        "Close all ${group.processes.size} processes of ${group.label}?"
                    )
                )
            },
            text = {
                Text(
                    AppLanguage.tr(
                        "将结束该应用的全部容器进程并释放内存，应用数据会保留。",
                        "This ends every container process of this app and frees its " +
                            "memory. App data is kept."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmGroup = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                group.processes.forEach {
                                    ContainerProcessPool.closeSlot(context, it.slot)
                                }
                            }
                            refresh()
                        }
                    }
                ) {
                    Text(
                        AppLanguage.tr("全部关闭", "Close all"),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmGroup = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun ProcessRow(
    slot: ContainerProcessPool.RunningSlot,
    onClose: () -> Unit
) {
    val processLabel = if (slot.processName == slot.packageName) {
        AppLanguage.tr("主进程", "Main process")
    } else {
        slot.processName.substringAfter(':').ifBlank { slot.processName }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 70.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (slot.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            processLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            AppLanguage.tr(
                "槽位 ${slot.slot + 1} · ${formatMemory(slot.memoryKb)}",
                "Slot ${slot.slot + 1} · ${formatMemory(slot.memoryKb)}"
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = AppLanguage.tr("关闭", "Close"),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun AppIcon(icon: ImageBitmap?, label: String, size: Int) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun formatMemory(kb: Long): String = when {
    kb >= 1024L * 1024L -> String.format(Locale.US, "%.2f GB", kb / (1024.0 * 1024.0))
    kb >= 1024L -> String.format(Locale.US, "%.0f MB", kb / 1024.0)
    else -> "$kb KB"
}
