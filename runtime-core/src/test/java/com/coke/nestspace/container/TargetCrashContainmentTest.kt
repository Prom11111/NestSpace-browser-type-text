package com.coke.nestspace.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCrashContainmentTest {

    @Test
    fun terminatesOnlyTargetMainThreadFailures() {
        val main = Thread("main")
        val worker = Thread("target-worker")
        assertTrue(TargetCrashContainment.shouldTerminate(main, main))
        assertFalse(TargetCrashContainment.shouldTerminate(worker, main))
    }
}
