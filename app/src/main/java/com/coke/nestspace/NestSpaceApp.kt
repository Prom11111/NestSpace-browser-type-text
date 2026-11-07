package com.coke.nestspace

import android.app.Application
import android.os.Build
import com.coke.nestspace.service.McpForegroundService
import java.io.File

/**
 * Starts the local MCP server with the app process so an agent can control
 * virtual apps without opening the UI first.
 */
class NestSpaceApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Slot processes render container error screens too; every process
        // needs the persisted language.
        com.coke.nestspace.i18n.AppLanguage.init(this)
        if (!isMainProcess()) return
        // Slot processes are killed when the host is updated or force-stopped;
        // do not report that as a virtual-app crash.
        com.coke.nestspace.container.ContainerProcessPool
            .resetLaunchedFlagsAfterHostRestart(this)
        com.coke.nestspace.agent.UserGateManager.init(this)
        // A run cannot survive its process. Finish stale records before any
        // new assistant turn can start.
        com.coke.nestspace.agent.RunRecovery.recover(this)
        runCatching {
            com.coke.nestspace.schedule.ScheduledTaskManager.recoverInterrupted(this)
        }.onFailure { android.util.Log.w("NestSpaceApp", "Schedule recovery failed", it) }
        com.coke.nestspace.mcp.McpClientManager.initialize(this)
        runCatching {
            com.coke.nestspace.schedule.ScheduledTaskManager.rescheduleAll(this)
        }.onFailure { android.util.Log.w("NestSpaceApp", "Schedule reschedule failed", it) }
        com.coke.nestspace.assistant.FloatingDotOverlay.ensure(this)
        com.coke.nestspace.browser.BrowserProxyManager.apply(this)
        // If the offline speech model is already cached, load it in the
        // background at startup so the voice sheet opens ready to listen.
        val speechSettings = com.coke.nestspace.assistant.FloatingDotSettings.load(this)
        if (com.coke.nestspace.assistant.LocalSpeechModelStore.isReady(
                this,
                speechSettings.speechLanguage
            )
        ) {
            com.coke.nestspace.assistant.LocalSpeechEngine.preload(
                this,
                speechSettings.speechLanguage
            )
        }
        McpForegroundService.ensureStarted(this)
    }

    private fun isMainProcess(): Boolean {
        val frameworkName = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                null
            }
        }.getOrNull()
        val processName = runCatching {
            File("/proc/self/cmdline").readText().trim('\u0000')
        }.getOrDefault("")
        if (frameworkName != null && frameworkName != packageName) return false
        if (processName.isNotBlank() && processName != packageName) return false
        return frameworkName == packageName || processName == packageName
    }
}
