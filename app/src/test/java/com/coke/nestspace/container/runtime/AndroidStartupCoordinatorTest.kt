package com.coke.nestspace.container.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidStartupCoordinatorTest {
    @Test
    fun recordsPhasesInExecutionOrderAndMeasuresDurations() {
        var now = 100L
        val coordinator = AndroidStartupCoordinator(clock = { now })

        coordinator.run(AndroidStartupPhase.CONTENT_PROVIDERS) {
            now = 125L
        }
        coordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
            now = 160L
        }

        val report = coordinator.report()
        assertEquals(
            listOf(
                AndroidStartupPhase.CONTENT_PROVIDERS,
                AndroidStartupPhase.APPLICATION_CREATE
            ),
            report.phases.map { it.phase }
        )
        assertEquals(listOf(25L, 35L), report.phases.map { it.durationMillis })
        assertEquals(60L, report.totalDurationMillis)
    }

    @Test
    fun recordsFailureAndRethrowsCriticalPhase() {
        var now = 0L
        val coordinator = AndroidStartupCoordinator(clock = { now })
        val error = runCatching {
            coordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
                now = 9L
                error("application failed")
            }
        }.exceptionOrNull()

        assertNotNull(error)
        val failed = coordinator.report().phase(AndroidStartupPhase.APPLICATION_CREATE)
        assertEquals(AndroidStartupOutcome.FAILED, failed?.outcome)
        assertEquals(9L, failed?.durationMillis)
        assertTrue(failed?.failure.orEmpty().contains("application failed"))
    }

    @Test
    fun optionalPhaseFailureIsObservableWithoutThrowing() {
        val coordinator = AndroidStartupCoordinator(clock = { 0L })

        val failure = coordinator.runOptional(
            AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH
        ) {
            error("optional shim unavailable")
        }

        assertNotNull(failure)
        assertEquals(
            AndroidStartupOutcome.FAILED,
            coordinator.report()
                .phase(AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH)
                ?.outcome
        )
    }

    @Test
    fun markOnceDoesNotDuplicateActivityMilestones() {
        val coordinator = AndroidStartupCoordinator(clock = { 0L })

        coordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_OPENED)
        coordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_OPENED)

        assertEquals(
            1,
            coordinator.report().phases.count {
                it.phase == AndroidStartupPhase.FIRST_ACTIVITY_OPENED
            }
        )
    }

    @Test
    fun reportsPhaseStartBeforeRunningBlock() {
        val started = mutableListOf<AndroidStartupPhase>()
        var startedBeforeBlock = false
        val coordinator = AndroidStartupCoordinator(
            clock = { 0L },
            phaseStartedObserver = { phase ->
                started += phase
                startedBeforeBlock = true
            }
        )

        coordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
            assertTrue(startedBeforeBlock)
        }

        assertEquals(
            listOf(AndroidStartupPhase.APPLICATION_CREATE),
            started
        )
    }
}
