package com.coke.nestspace.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.mcp.McpClientConfig
import com.coke.nestspace.mcp.McpClientManager
import com.coke.nestspace.mcp.McpClientStore
import com.coke.nestspace.mcp.McpNetwork
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * External MCP servers used by the local assistant. This is intentionally
 * separate from NestSpace's own control MCP server on the Services page.
 */
@Composable
internal fun McpClientSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf(McpClientStore(context).load()) }
    var editing by remember { mutableStateOf<McpClientConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<McpClientConfig?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    LaunchedEffect(settingsRevision) {
        configs = McpClientStore(context).load()
        McpClientManager.reload(context)
    }
    var lanPermissionGranted by remember {
        mutableStateOf(McpNetwork.hasLocalNetworkPermission(context))
    }
    val lanPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lanPermissionGranted = granted
        if (!granted) {
            status = AppLanguage.tr(
                "未授予局域网权限，只能连接公网 MCP Server",
                "Local network permission denied; only public MCP servers can be reached"
            )
        }
    }

    fun refreshConfigs() {
        configs = McpClientStore(context).load()
        McpClientManager.reload(context)
    }

    fun discover(config: McpClientConfig) {
        busyId = config.id
        status = ""
        scope.launch {
            val result = runCatching { McpClientManager.discover(context, config) }
            busyId = null
            refreshConfigs()
            status = result.fold(
                onSuccess = {
                    AppLanguage.tr(
                        "已获取 ${it.tools.size} 个工具",
                        "Discovered ${it.tools.size} tool(s)"
                    )
                },
                onFailure = {
                    AppLanguage.tr(
                        "连接失败：${it.message ?: it.javaClass.simpleName}",
                        "Connection failed: ${it.message ?: it.javaClass.simpleName}"
                    )
                }
            )
        }
    }

    BackHandler(enabled = editing != null) { editing = null }

    val current = editing
    if (current != null) {
        McpClientEditor(
            initial = current,
            onCancel = { editing = null },
            onSave = { next ->
                val existing = McpClientStore(context).load()
                    .firstOrNull { it.id == next.id }
                val endpointChanged = existing == null ||
                    existing.url != next.url ||
                    existing.token != next.token
                val saved = next.copy(
                    tools = if (endpointChanged) emptyList() else next.tools
                )
                McpClientStore(context).upsert(saved)
                McpClientManager.forgetSession(saved.id)
                editing = null
                refreshConfigs()
                if (saved.enabled) {
                    discover(saved)
                } else {
                    status = AppLanguage.tr("已保存", "Saved")
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                AppLanguage.tr(
                    "这里配置本地 AI 助手使用的外部 MCP Server。容器对外的 MCP 控制服务在“MCP 控制服务”里，两者互不影响。",
                    "Configure external MCP servers for the local AI assistant. NestSpace's " +
                        "own control server lives under MCP control service; the two are separate."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Text(
                AppLanguage.tr(
                    "第三方工具可能执行真实操作。严格权限模式下每次调用都会确认；工具返回内容按外部数据处理。",
                    "Third-party tools can perform real actions. Strict permission mode " +
                        "confirms every call; tool results are treated as external data."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (Build.VERSION.SDK_INT >= 37 && !lanPermissionGranted) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            AppLanguage.tr(
                                "连接局域网或本机 MCP Server 需要局域网权限。",
                                "Local network permission is required for LAN or loopback " +
                                    "MCP servers."
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = {
                                lanPermission.launch(McpNetwork.LOCAL_NETWORK_PERMISSION)
                            }
                        ) {
                            Text(AppLanguage.tr("授权局域网权限", "Allow local network"))
                        }
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AppLanguage.tr("MCP 工具", "MCP tools"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        editing = McpClientConfig(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            url = "",
                            token = "",
                            enabled = true
                        )
                    }
                ) {
                    Text(AppLanguage.tr("添加", "Add"))
                }
            }
        }
        if (configs.isEmpty()) {
            item {
                Text(
                    AppLanguage.tr(
                        "还没有外部 MCP Server。添加后先刷新一次工具列表，助手才能看到这些工具。",
                        "No external MCP server yet. Add one and refresh its tools before " +
                            "the assistant can use them."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
        items(configs, key = { it.id }) { config ->
            McpClientCard(
                config = config,
                busy = busyId == config.id,
                onToggle = { enabled ->
                    McpClientStore(context).upsert(config.copy(enabled = enabled))
                    if (!enabled) McpClientManager.forgetSession(config.id)
                    refreshConfigs()
                },
                onEdit = { editing = config },
                onDiscover = { discover(config) },
                onDelete = { deleteTarget = config }
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(AppLanguage.tr("删除 MCP Server？", "Delete MCP server?")) },
            text = {
                Text(
                    AppLanguage.tr(
                        "将删除 ${target.name} 及其缓存的工具。",
                        "Remove ${target.name} and its cached tools."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        McpClientManager.delete(context, target.id)
                        deleteTarget = null
                        refreshConfigs()
                        status = AppLanguage.tr("已删除", "Deleted")
                    }
                ) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(AppLanguage.tr("取消", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun McpClientCard(
    config: McpClientConfig,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDiscover: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        config.name.ifBlank { config.url },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        config.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onToggle)
            }
            Text(
                AppLanguage.tr(
                    "${config.tools.size} 个工具 · ${if (config.enabled) "已启用" else "已停用"}",
                    "${config.tools.size} tool(s) · ${if (config.enabled) "enabled" else "disabled"}"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (config.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (config.tools.isNotEmpty()) {
                Text(
                    config.tools.take(4).joinToString(" · ") { it.name } +
                        if (config.tools.size > 4) " …" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier
                        .padding(vertical = 4.dp)
                        .size(22.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDiscover, enabled = !busy) {
                    Text(AppLanguage.tr("刷新工具", "Refresh tools"))
                }
                TextButton(onClick = onEdit) {
                    Text(AppLanguage.tr("编辑", "Edit"))
                }
                TextButton(onClick = onDelete) {
                    Text(AppLanguage.tr("删除", "Delete"))
                }
            }
        }
    }
}

@Composable
private fun McpClientEditor(
    initial: McpClientConfig,
    onCancel: () -> Unit,
    onSave: (McpClientConfig) -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var url by remember(initial.id) { mutableStateOf(initial.url) }
    var token by remember(initial.id) { mutableStateOf(initial.token) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var error by remember { mutableStateOf("") }

    fun build(): McpClientConfig? {
        val config = initial.copy(
            name = name.trim().ifBlank {
                runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()
            },
            url = url.trim(),
            token = token.trim(),
            enabled = enabled
        )
        val validation = McpClientStore.validate(config)
        if (validation != null) {
            error = when {
                config.url.isBlank() ->
                    AppLanguage.tr("请填写 MCP URL", "MCP URL is required")
                !config.url.startsWith("http://") &&
                    !config.url.startsWith("https://") ->
                    AppLanguage.tr(
                        "URL 必须以 http:// 或 https:// 开头",
                        "URL must start with http:// or https://"
                    )
                config.name.isBlank() ->
                    AppLanguage.tr("请填写名称", "Name is required")
                else -> validation
            }
            return null
        }
        error = ""
        return config
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (initial.name.isBlank() && initial.url.isBlank()) {
                    AppLanguage.tr("添加 MCP Server", "Add MCP server")
                } else {
                    AppLanguage.tr("编辑 MCP Server", "Edit MCP server")
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("名称", "Name")) },
                    placeholder = { Text("My MCP server") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("MCP URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("Bearer Token（可选）", "Bearer token (optional)")) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text(
                    AppLanguage.tr(
                        "Token 使用 Android Keystore 加密保存。目前只支持 HTTP POST + Bearer Token。",
                        "The token is encrypted with Android Keystore. Only HTTP POST + " +
                            "Bearer token is supported for now."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("启用", "Enabled"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (error.isNotBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    build()?.let(onSave)
                }
            ) {
                Text(AppLanguage.tr("保存并连接", "Save & connect"))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(AppLanguage.tr("取消", "Cancel"))
            }
        }
    )
}
