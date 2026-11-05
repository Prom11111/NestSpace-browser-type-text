package com.coke.nestspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.i18n.AppLanguage

private val SLOT_PRESETS = listOf(4, 8, 16)

@Composable
internal fun SlotDetailScreen(
    mode: Int,
    limit: Int,
    onSelect: (Int) -> Unit
) {
    AppLanguage.isChinese()
    // Keep a custom MCP-set limit (for example 6) visible and selected.
    val fixedOptions = if (mode in 1..ContainerProcessPool.SLOT_COUNT &&
        mode !in SLOT_PRESETS
    ) {
        (SLOT_PRESETS + mode).sorted()
    } else {
        SLOT_PRESETS
    }
    val slotOptions = listOf(ContainerProcessPool.AUTO_SLOT_LIMIT) + fixedOptions
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(AppLanguage.tr("容器进程槽位", "Container process slots"))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    slotOptions.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(value) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == mode,
                                // The row handles selection.
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (value == ContainerProcessPool.AUTO_SLOT_LIMIT) {
                                    AppLanguage.tr(
                                        "按内存动态管理（最多 16）",
                                        "Memory-managed (up to 16)"
                                    )
                                } else {
                                    AppLanguage.tr("$value 个槽位", "$value slots")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (value == mode) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "自动模式按内存动态管理槽位，最高可使用全部 ${ContainerProcessPool.SLOT_COUNT} 个。" +
                        "每个槽位对应一个独立的容器进程；内存不足时会自动关闭最久未使用的后台容器，" +
                        "优先关闭后台子进程，当前前台容器不会被关闭。" +
                        "固定档位会限制可同时运行的容器进程数，降低上限会立即结束超出范围的进程。",
                    "Automatic mode manages slots dynamically by memory and can use all " +
                        "${ContainerProcessPool.SLOT_COUNT} slots. Each slot is one container " +
                        "process; when memory runs low the least recently used background " +
                        "containers are closed, preferring subprocesses, and the foreground " +
                        "container is never closed. Fixed presets cap how many container " +
                        "processes may run at once; lowering a preset stops excess processes."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Text(
                AppLanguage.tr(
                    if (mode == ContainerProcessPool.AUTO_SLOT_LIMIT) {
                        "自动模式：按内存动态管理（最多 ${ContainerProcessPool.SLOT_COUNT} 个）"
                    } else {
                        "当前上限：$limit（最多 ${ContainerProcessPool.SLOT_COUNT}）"
                    },
                    if (mode == ContainerProcessPool.AUTO_SLOT_LIMIT) {
                        "Automatic: memory-managed (up to ${ContainerProcessPool.SLOT_COUNT})"
                    } else {
                        "Current limit: $limit (max ${ContainerProcessPool.SLOT_COUNT})"
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
