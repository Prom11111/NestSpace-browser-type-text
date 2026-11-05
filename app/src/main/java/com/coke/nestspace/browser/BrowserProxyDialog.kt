package com.coke.nestspace.browser

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coke.nestspace.i18n.AppLanguage

/**
 * Browser-wide proxy configuration. WebView only supports one proxy override
 * per app process, so every tab shares these values.
 */
@Composable
fun BrowserProxyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(BrowserProxySettings.load(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    val status by BrowserProxyManager.status.collectAsState()
    val supported = remember { BrowserProxyManager.isSupported() }

    fun validationText(value: BrowserProxySettings.ValidationError): String = when (value) {
        BrowserProxySettings.ValidationError.INVALID_TYPE ->
            AppLanguage.tr("代理类型无效", "Invalid proxy type")
        BrowserProxySettings.ValidationError.HOST_REQUIRED ->
            AppLanguage.tr("请填写代理服务器地址", "Proxy host is required")
        BrowserProxySettings.ValidationError.INVALID_HOST ->
            AppLanguage.tr(
                "代理服务器只填主机名或 IP，不要带协议、端口或斜杠",
                "Enter only a hostname or IP, without scheme, port or slash"
            )
        BrowserProxySettings.ValidationError.INVALID_PORT ->
            AppLanguage.tr("端口必须在 1-65535 之间", "Port must be between 1 and 65535")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(AppLanguage.tr("浏览器代理", "Browser proxy")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppLanguage.tr("启用代理", "Enable proxy"),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            AppLanguage.tr(
                                "所有浏览器标签页共用这个代理",
                                "Shared by all browser tabs"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = {
                            error = null
                            config = config.copy(enabled = it)
                        }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.type == BrowserProxySettings.TYPE_HTTP,
                        onClick = { config = config.copy(type = BrowserProxySettings.TYPE_HTTP) },
                        label = { Text("HTTP") }
                    )
                    FilterChip(
                        selected = config.type == BrowserProxySettings.TYPE_SOCKS,
                        onClick = { config = config.copy(type = BrowserProxySettings.TYPE_SOCKS) },
                        label = { Text("SOCKS") }
                    )
                }

                OutlinedTextField(
                    value = config.host,
                    onValueChange = {
                        error = null
                        config = config.copy(host = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("代理服务器", "Proxy host")) },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = if (config.port == BrowserProxySettings.NO_PORT) {
                        ""
                    } else {
                        config.port.toString()
                    },
                    onValueChange = { value ->
                        error = null
                        val digits = value.filter(Char::isDigit).take(5)
                        config = config.copy(
                            port = digits.toIntOrNull() ?: BrowserProxySettings.NO_PORT
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(AppLanguage.tr("端口", "Port")) },
                    placeholder = { Text("8080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = config.bypass,
                    onValueChange = { config = config.copy(bypass = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(AppLanguage.tr("绕过代理（可选）", "Bypass (optional)"))
                    },
                    placeholder = { Text("localhost, 127.0.0.1, *.example.com") },
                    maxLines = 2
                )
                Text(
                    AppLanguage.tr(
                        "WebView 代理暂不支持用户名/密码认证，请使用无需认证的代理。",
                        "WebView proxy overrides do not support username/password authentication; use a proxy without auth."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!supported) {
                    Text(
                        AppLanguage.tr(
                            "当前 WebView 不支持代理设置（需要 Android 10+ 且 WebView 支持 PROXY_OVERRIDE）。",
                            "This WebView does not support proxy overrides (requires Android 10+ with PROXY_OVERRIDE)."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (supported && config.enabled && status.message.isNotBlank()) {
                    Text(
                        status.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (supported && config.enabled && status.applied) {
                    Text(
                        AppLanguage.tr("代理已生效", "Proxy is active"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validation = BrowserProxySettings.validate(config)
                    if (validation != null) {
                        error = validationText(validation)
                    } else {
                        BrowserProxySettings.save(context, config)
                        BrowserProxyManager.apply(context) { status ->
                            val message = when {
                                !config.enabled -> AppLanguage.tr(
                                    "已关闭浏览器代理",
                                    "Browser proxy disabled"
                                )
                                status.applied -> AppLanguage.tr(
                                    "浏览器代理已生效",
                                    "Browser proxy is active"
                                )
                                else -> AppLanguage.tr(
                                    "代理未生效：${status.message.ifBlank { "未知错误" }}",
                                    "Proxy is not active: ${status.message.ifBlank { "unknown error" }}"
                                )
                            }
                            Toast.makeText(
                                context.applicationContext,
                                message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        onDismiss()
                    }
                }
            ) {
                Text(AppLanguage.tr("保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("取消", "Cancel"))
            }
        }
    )
}
