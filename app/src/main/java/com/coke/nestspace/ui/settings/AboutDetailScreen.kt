package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coke.nestspace.i18n.AppLanguage

@Composable
internal fun AboutDetailScreen() {
    val versionName = rememberAppVersion()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("版本", "Version")) }
        item {
            SettingsCard(title = "NestSpace", status = versionName) {
                Text(
                    AppLanguage.tr(
                    "应用虚拟空间 + 纯净桌面 + 本地/远程 Agent。",
                    "App virtualization + a clean desktop + local/remote agents."
                ),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    AppLanguage.tr(
                    "支持多开、独立实例数据与进程级隔离；本地 Agent 支持 Claude 与 " +
                        "OpenAI 兼容接口。",
                    "Supports multiple instances, isolated app data and " +
                        "process-level isolation. Local agents work with Claude and " +
                        "OpenAI-compatible APIs."
                ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
