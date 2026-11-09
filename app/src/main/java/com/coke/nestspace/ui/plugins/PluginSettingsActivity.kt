package com.coke.nestspace.ui.plugins

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.plugin.PluginCenterStore
import com.coke.nestspace.plugin.PluginContext
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginSettingsViewContext
import com.coke.nestspace.plugin.PluginSettingsViewPlugins
import com.coke.nestspace.ui.theme.VirtualTheme

/**
 * Hosts a plugin-owned Android View as the plugin's Settings entry.
 */
class PluginSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        setContent {
            VirtualTheme {
                PluginSettingsHost(
                    pluginId = pluginId,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_settings_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginSettingsHost(
    pluginId: String,
    onBack: () -> Unit
) {
    val provider = remember(pluginId) {
        PluginSettingsViewPlugins.find(pluginId)
    }
    val host = remember(pluginId) {
        PluginSettingsViewPlugins.findHost(pluginId)
    }
    val descriptor = provider?.descriptor
    val context = LocalContext.current
    val title = remember(pluginId, descriptor) {
        val fallback = descriptor?.name ?: AppLanguage.tr(
            "插件设置",
            "Plugin settings"
        )
        PluginCenterStore.displayName(context, pluginId, fallback)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$title · ${AppLanguage.tr("设置", "Settings")}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppLanguage.tr(
                                "返回插件中心",
                                "Back to Plugin Center"
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (provider == null || host == null || descriptor == null) {
                Text(
                    AppLanguage.tr(
                        "该插件没有提供设置界面。",
                        "This plugin does not provide a Settings View."
                    ),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                PluginSettingsView(
                    pluginId = pluginId,
                    provider = provider,
                    host = host,
                    descriptor = descriptor,
                    onClose = onBack
                )
            }
        }
    }
}

@Composable
private fun PluginSettingsView(
    pluginId: String,
    provider: com.coke.nestspace.plugin.PluginSettingsViewProvider,
    host: PluginContext,
    descriptor: PluginDescriptor,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val viewContext = remember(pluginId, provider, host) {
        PluginSettingsViewContext.create(
            context,
            host,
            descriptor,
            onClose
        )
    }
    val created = remember(pluginId, provider, viewContext) {
        runCatching {
            provider.createSettingsView(viewContext)
        }
    }
    val view = created.getOrNull()
    if (view == null) {
        Text(
            AppLanguage.tr(
                "设置界面加载失败：${created.exceptionOrNull()?.message.orEmpty()}",
                "Settings View failed: " +
                    created.exceptionOrNull()?.message.orEmpty()
            ),
            color = MaterialTheme.colorScheme.error
        )
        return
    }
    DisposableEffect(provider, view) {
        onDispose {
            runCatching {
                provider.onSettingsViewClosed(view)
            }
        }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize()
    )
}
