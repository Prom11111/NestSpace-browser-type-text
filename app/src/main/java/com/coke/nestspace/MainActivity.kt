package com.coke.nestspace

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coke.nestspace.service.BackgroundOverlay
import com.coke.nestspace.service.McpForegroundService
import com.coke.nestspace.ui.VirtualApp
import com.coke.nestspace.ui.theme.VirtualTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VirtualTheme {
                VirtualApp()
            }
        }
        // Virtual-app notifications are posted under the host package, so the
        // host needs the runtime notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundOverlay.ensure(this)
        com.coke.nestspace.assistant.FloatingDotOverlay.onHostActivityResumed(this)
        // Application.onCreate happens before the process is foreground on
        // Android 16+, where starting a foreground service is rejected. Retry
        // while the UI is visible; ensureStarted respects an explicit Stop.
        McpForegroundService.ensureStarted(this)
    }
}
