package com.coke.nestspace

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.browser.BrowserActivity
import com.coke.nestspace.browser.routeWebIntentInside
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerSessions
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.runtime.RuntimeHostIdentity
import com.coke.nestspace.runtime.RuntimeHostHooks
import com.coke.nestspace.task.OperationTaskRegistry
import com.coke.nestspace.ui.DesktopNavigation
import java.io.File

/**
 * Performs only the process-wide initialization required by the first screen
 * synchronously. Recovery, external MCP tools, overlays, proxy setup and speech
 * preloading are dispatched by [NestSpaceDeferredStartup].
 */
class NestSpaceApp : Application() {
    override fun startActivity(intent: Intent) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivity(intent, options)
    }


    override fun onCreate() {
        super.onCreate()
        val processStartedAt = System.currentTimeMillis()
        installRuntimeHooks()
        HostIdentity.captureProcess(this)
        ContainerSessions.installHostActivityInstrumentation(this)
        // Slot processes render container error screens too; every process
        // needs the persisted language.
        AppLanguage.init(this)
        runCatching {
            com.coke.nestspace.plugin.PluginSourceLoader
                .restoreBundled(this)
        }.onFailure {
            android.util.Log.w(
                "NestSpaceApp",
                "source plugin restore failed",
                it
            )
        }
        runCatching {
            com.coke.nestspace.plugin.PluginPackageInstaller
                .get(this)
                .restoreInstalled()
        }.onFailure {
            android.util.Log.w(
                "NestSpaceApp",
                "external plugin restore failed",
                it
            )
        }
        if (!isMainProcess()) return
        runCatching {
            com.coke.nestspace.plugin.PluginPackageInstaller
                .get(this)
                .importIncomingPackages()
        }.onFailure {
            android.util.Log.w(
                "NestSpaceApp",
                "incoming plugin install failed",
                it
            )
        }
        // Slot processes are killed when the host is updated or force-stopped;
        // do not report that as a virtual-app crash.
        ContainerProcessPool.resetLaunchedFlagsAfterHostRestart(this)
        UserGateManager.init(this)
        OperationTaskRegistry.initialize(this)
        com.coke.nestspace.plugin.AgentPluginSupport.installBuiltins(this)
        NestSpaceDeferredStartup.schedule(this, processStartedAt)
    }

    private fun installRuntimeHooks() {
        RuntimeHostIdentity.packageName = packageName
        RuntimeHostHooks.openBrowser = { context, url ->
            context.startActivity(
                Intent(context, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        RuntimeHostHooks.setOverlayHidden = { context, reason, hidden ->
            FloatingDotOverlay.setOverlayHidden(context, reason, hidden)
        }
        RuntimeHostHooks.publishContainerMode = { context, visible ->
            FloatingDotOverlay.publishContainerMode(context, visible)
        }
        RuntimeHostHooks.requestDesktop = {
            DesktopNavigation.requestDesktop()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess()) return
        ContainerProcessPool.onTrimMemory(this, level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isMainProcess()) return
        ContainerProcessPool.onLowMemory(this)
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
