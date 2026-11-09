package com.coke.nestspace.container.loader

import android.content.Intent
import android.content.pm.ActivityInfo
import com.coke.nestspace.container.intent.ContainerIntentFilter
import com.coke.nestspace.container.intent.ManifestIntentFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class ApkLauncherSelectionTest {

    @Test
    fun disabledLauncherIsSkippedAndEnabledAliasIsResolved() {
        val debug = activity("debug.DebugActivity", enabled = true)
        val target = activity("app.MainActivity", enabled = true)
        val aliasName = "app.LauncherAlias"
        val filters = ManifestIntentFilters.Parsed(
            packageName = "app",
            activities = linkedMapOf(
                debug.name to listOf(launcherFilter()),
                aliasName to listOf(launcherFilter())
            ),
            activityAliases = mapOf(aliasName to target.name),
            activityEnabled = mapOf(
                debug.name to false,
                aliasName to true
            ),
            services = emptyMap(),
            receivers = emptyMap()
        )

        val selected = ApkInspector.selectLauncherActivity(
            listOf(debug, target),
            filters
        )

        assertEquals(aliasName, selected?.name)
        assertEquals(target.name, selected?.targetActivity)
    }

    @Test
    fun enabledDirectLauncherWinsBeforeExportedFallback() {
        val debug = activity("debug.DebugActivity", enabled = true)
        val main = activity("app.MainActivity", enabled = true)
        val filters = ManifestIntentFilters.Parsed(
            packageName = "app",
            activities = linkedMapOf(
                debug.name to listOf(launcherFilter()),
                main.name to emptyList()
            ),
            activityAliases = emptyMap(),
            activityEnabled = mapOf(main.name to true),
            services = emptyMap(),
            receivers = emptyMap()
        )

        val selected = ApkInspector.selectLauncherActivity(
            listOf(main, debug),
            filters
        )

        assertEquals(debug.name, selected?.name)
    }

    private fun activity(name: String, enabled: Boolean) =
        ActivityInfo().apply {
            this.name = name
            this.enabled = enabled
            exported = true
        }

    private fun launcherFilter() = ContainerIntentFilter().apply {
        actions += Intent.ACTION_MAIN
        categories += Intent.CATEGORY_LAUNCHER
    }
}
