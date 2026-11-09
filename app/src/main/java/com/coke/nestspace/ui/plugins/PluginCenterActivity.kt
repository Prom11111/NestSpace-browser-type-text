package com.coke.nestspace.ui.plugins

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.ui.theme.VirtualTheme

/**
 * Plugin Center is a launcher-level feature activity. Finishing it reveals the
 * desktop underneath instead of navigating through Space Settings.
 */
class PluginCenterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VirtualTheme {
                PluginCenterHost(onBack = { finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FloatingDotOverlay.onHostActivityResumed(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginCenterHost(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(AppLanguage.tr("插件中心", "Plugin center"))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = AppLanguage.tr(
                                "返回桌面",
                                "Back to desktop"
                            )
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            PluginCenterScreen()
        }
    }
}
