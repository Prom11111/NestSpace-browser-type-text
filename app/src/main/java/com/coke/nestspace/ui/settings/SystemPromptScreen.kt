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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.SystemPromptStore
import com.coke.nestspace.i18n.AppLanguage

/**
 * Global system prompt shared by every model config. The built-in text is
 * always available for restore; runtime hints (time, memory, skills and tool
 * rules) are appended separately by the agent.
 */
@Composable
internal fun SystemPromptScreen() {
    val context = LocalContext.current
    val store = remember { SystemPromptStore(context) }
    var text by remember { mutableStateOf(store.load()) }
    var status by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(AppLanguage.tr("系统提示词", "System prompt"))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        AppLanguage.tr(
                            "对所有模型配置生效。运行时还会自动追加当前时间、记忆、技能索引和工具规则，这里只写基础身份与行为要求。",
                            "Applies to every model config. Runtime time, memory, skill index " +
                                "and tool rules are appended automatically; write only the base " +
                                "identity and behavior here."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (store.isCustom()) {
                            AppLanguage.tr("当前：自定义提示词", "Current: custom prompt")
                        } else {
                            AppLanguage.tr("当前：内置提示词", "Current: built-in prompt")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(SystemPromptStore.MAX_LENGTH)
                    status = ""
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(AppLanguage.tr("基础提示词", "Base prompt")) },
                minLines = 14,
                maxLines = 30
            )
        }
        item {
            Text(
                AppLanguage.tr(
                    "${text.length} / ${SystemPromptStore.MAX_LENGTH} 字符",
                    "${text.length} / ${SystemPromptStore.MAX_LENGTH} characters"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (status.isNotBlank()) {
            item {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val saved = store.save(text)
                        status = if (saved) {
                            text = store.load()
                            AppLanguage.tr("已保存", "Saved")
                        } else {
                            AppLanguage.tr("保存失败：内容过长", "Save failed: text is too long")
                        }
                    }
                ) {
                    Text(AppLanguage.tr("保存", "Save"))
                }
                OutlinedButton(onClick = { confirmReset = true }) {
                    Text(AppLanguage.tr("恢复内置默认", "Restore built-in"))
                }
            }
        }
        item {
            Text(
                AppLanguage.tr(
                    "恢复内置默认会删除当前自定义内容，但不会影响模型配置、记忆或技能。",
                    "Restoring the built-in prompt removes the custom text but leaves " +
                        "model configs, memory and skills untouched."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(AppLanguage.tr("恢复内置默认？", "Restore built-in prompt?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "当前自定义提示词会被删除。",
                        "The current custom prompt will be removed."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.reset()
                        text = store.load()
                        status = AppLanguage.tr("已恢复内置默认", "Built-in prompt restored")
                        confirmReset = false
                    }
                ) {
                    Text(AppLanguage.tr("恢复", "Restore"))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}
