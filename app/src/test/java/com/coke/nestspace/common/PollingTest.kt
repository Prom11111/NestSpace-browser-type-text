package com.coke.nestspace.common

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingTest {

    @Test
    fun stopsAfterConditionBecomesTrue() = runBlocking {
        var probeCount = 0
        val result = pollUntil(
            maxChecks = 10,
            intervalMs = 0L,
            probe = {
                probeCount += 1
                probeCount
            },
            isComplete = { it >= 3 }
        )

        assertEquals(3, result.value)
        assertEquals(3, result.checks)
        assertFalse(result.timedOut)
    }

    @Test
    fun exhaustingChecksIsTimeout() = runBlocking {
        val result = pollUntil(
            maxChecks = 4,
            intervalMs = 0L,
            probe = { "downloading" },
            isComplete = { it == "done" }
        )

        assertEquals("downloading", result.value)
        assertEquals(4, result.checks)
        assertTrue(result.timedOut)
    }

    @Test
    fun missingProbeValueDoesNotBecomeSuccess() = runBlocking {
        val result = pollUntil<String>(
            maxChecks = 2,
            intervalMs = 0L,
            probe = { null },
            isComplete = { true }
        )

        assertEquals(null, result.value)
        assertEquals(2, result.checks)
        assertTrue(result.timedOut)
    }
}
