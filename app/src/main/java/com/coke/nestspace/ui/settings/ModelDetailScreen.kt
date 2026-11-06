package com.coke.nestspace.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ModelDetailScreen(
    configs: List<LlmConfig>,
    activeId: String,
    llmStatus: String,
    onAddLlm: () -> Unit,
    onEditLlm: (LlmConfig) -> Unit,
    onDeleteLlm: (String) -> Unit,
    onSelectLlm: (String) -> Unit
) {
    var actionMode by remember { mutableStateOf(false) }

    if (configs.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    AppLanguage.tr("还没有模型配置", "No model configured yet"),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    AppLanguage.tr(
                        "添加一个模型配置后，AI 助手会使用大模型调用 space_* / ui_* " +
                            "工具操作虚拟应用；未配置时只执行本地规则。",
                        "Add a model and the AI assistant can use space_* / ui_* " +
                            "tools to operate virtual apps. Without one it only runs " +
                            "local rules."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = onAddLlm) { Text(AppLanguage.tr("添加", "Add")) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("大模型", "Models"),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onAddLlm) { Text(AppLanguage.tr("添加", "Add")) }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { actionMode = !actionMode }) {
                        Text(if (actionMode) AppLanguage.tr("完成", "Done") else AppLanguage.tr("操作", "Manage"))
                    }
                }
            }
            if (llmStatus.isNotBlank()) {
                item {
                    Text(
                        llmStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(configs, key = { it.id }) { config ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (config.id == activeId) {
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !actionMode) { onSelectLlm(config.id) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        config.name.ifBlank { AppLanguage.tr("未命名模型", "Unnamed model") },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (config.id == activeId) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            AppLanguage.tr("当前使用", "Active"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    config.protocol.label + " · " +
                                        config.model.ifBlank { AppLanguage.tr("未填模型", "No model") } +
                                        " · " + String.format(
                                            java.util.Locale.getDefault(),
                                            "%,d",
                                            config.contextTokens
                                        ) + " tokens",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    config.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (actionMode) {
                                TextButton(onClick = { onDeleteLlm(config.id) }) {
                                    Text(AppLanguage.tr("删除", "Delete"))
                                }
                                TextButton(onClick = { onEditLlm(config) }) {
                                    Text(AppLanguage.tr("修改", "Edit"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
