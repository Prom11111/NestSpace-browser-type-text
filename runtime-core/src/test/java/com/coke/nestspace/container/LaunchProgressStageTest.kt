package com.coke.nestspace.container

import com.coke.nestspace.container.runtime.AndroidStartupPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchProgressStageTest {
    @Test
    fun mapsRealStartupPhasesToMonotonicCheckpoints() {
        val phases = listOf(
            AndroidStartupPhase.CLASS_LOADER,
            AndroidStartupPhase.APPLICATION_ATTACH,
            AndroidStartupPhase.PROCESS_APPLICATION,
            AndroidStartupPhase.CONTENT_PROVIDERS,
            AndroidStartupPhase.APPLICATION_CREATE,
            AndroidStartupPhase.APPLICATION_COMPATIBILITY,
            AndroidStartupPhase.HOST_BRIDGES,
            AndroidStartupPhase.STARTUP_COMPATIBILITY,
            AndroidStartupPhase.CRASH_GUARD,
            AndroidStartupPhase.READY,
            AndroidStartupPhase.FIRST_ACTIVITY_SCHEDULED,
            AndroidStartupPhase.FIRST_ACTIVITY_OPENED
        )

        val percentages = phases.map {
            LaunchProgressStage.forStartupPhase(it).percent
        }

        assertEquals(percentages.sorted(), percentages)
        assertEquals(100, percentages.last())
        assertTrue(percentages.first() > LaunchProgressStage.PREPARING_RUNTIME.percent)
    }
}
