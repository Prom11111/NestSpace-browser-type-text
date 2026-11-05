package com.coke.nestspace.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.i18n.AppLanguage

/** Confirmation policy for the assistant's mutating tool calls. */
@Composable
internal fun ToolPermissionScreen(
    level: ToolPermissionLevel,
    customCategories: Set<ToolConfirmationCategory>,
    onSelectLevel: (ToolPermissionLevel) -> Unit,
    onToggleCategory: (ToolConfirmationCategory, Boolean) -> Unit
) {
    AppLanguage.isChinese()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "控制助手执行敏感操作前的确认弹窗。严格模式会保持现有行为。",
                    "Controls confirmation prompts before the assistant performs sensitive " +
                        "actions. Strict keeps the existing behavior."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    ToolPermissionLevel.entries.forEachIndexed { index, value ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLevel(value) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == level,
                                onClick = { onSelectLevel(value) }
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp)
                            ) {
                                Text(
                                    value.label,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    value.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        if (level == ToolPermissionLevel.CUSTOM) {
            item {
                Text(
                    AppLanguage.tr("自定义确认项", "Custom confirmations"),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        ToolConfirmationCategory.entries.forEachIndexed { index, category ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onToggleCategory(
                                            category,
                                            category !in customCategories
                                        )
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        category.label,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        category.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = category in customCategories,
                                    onCheckedChange = { onToggleCategory(category, it) }
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    AppLanguage.tr(
                        "关闭的项目会直接执行，不再弹出确认。涉及支付等金钱操作时建议保持开启。",
                        "Disabled items run without a confirmation prompt. Keep money actions " +
                            "enabled if you want a guard before payments."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
