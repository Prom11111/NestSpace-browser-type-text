package com.coke.nestspace.container

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerBroadcastRouterTest {
    @Test
    fun routesOnlyEnabledReceiversOutsideCurrentProcess() {
        val receivers = listOf(
            receiver("com.example.app:one", enabled = true),
            receiver("com.example.app:one", enabled = true),
            receiver(".two", enabled = true),
            receiver("com.example.app", enabled = true),
            receiver("com.example.app:disabled", enabled = false)
        )

        assertEquals(
            listOf("com.example.app:one", "com.example.app.two"),
            ContainerBroadcastRouter.remoteProcesses(
                packageName = "com.example.app",
                currentProcess = "com.example.app",
                receivers = receivers
            )
        )
    }

    private fun receiver(process: String, enabled: Boolean) = ActivityInfo().apply {
        processName = process
        this.enabled = enabled
    }
}
