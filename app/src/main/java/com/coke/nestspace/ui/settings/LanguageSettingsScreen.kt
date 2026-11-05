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
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.i18n.AppLanguageMode

@Composable
internal fun LanguageDetailScreen(
    mode: AppLanguageMode,
    onSelect: (AppLanguageMode) -> Unit
) {
    // Reading isChinese() here subscribes this screen to language changes.
    AppLanguage.isChinese()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingsSection(
                AppLanguage.tr("显示语言", "Display language")
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    AppLanguageMode.entries.forEachIndexed { index, value ->
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
                                AppLanguage.modeLabel(value),
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
                    "默认跟随系统语言。切换后立即生效，无需重启应用。",
                    "Follows the system language by default. Changes apply " +
                        "immediately; no restart needed."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
