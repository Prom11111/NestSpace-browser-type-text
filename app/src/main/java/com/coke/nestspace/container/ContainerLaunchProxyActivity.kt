package com.coke.nestspace.container

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.coke.nestspace.AppGraph
import kotlinx.coroutines.launch

/**
 * Invisible trampoline for notification taps (and other host-side entry
 * points) that need to launch a virtual app.
 *
 * Target-created PendingIntents point at the target package, which the system
 * cannot start for the host UID. ContainerNotificationBridge replaces them
 * with a PendingIntent to this Activity; the user tap is an allowed
 * user-initiated app start, and the engine resumes/creates the container.
 */
class ContainerLaunchProxyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spaceId = intent.getStringExtra(EXTRA_SPACE_ID) ?: "virtual-1"
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE)
        if (packageName.isNullOrBlank() || instanceId.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            runCatching {
                AppGraph.engine(applicationContext).launch(
                    spaceId = spaceId,
                    packageName = packageName,
                    instanceId = instanceId,
                    headless = false
                )
            }
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val EXTRA_SPACE_ID = "space_id"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_INSTANCE = "instance"
    }
}
