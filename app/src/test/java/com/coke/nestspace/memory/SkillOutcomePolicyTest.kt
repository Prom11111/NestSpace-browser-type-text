package com.coke.nestspace.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillOutcomePolicyTest {

    @Test
    fun twoFailuresInLastThreeSuspend() {
        assertFalse(SkillOutcomePolicy.shouldSuspend(listOf(true, true, true)))
        assertFalse(SkillOutcomePolicy.shouldSuspend(listOf(false, true, true)))
        assertTrue(SkillOutcomePolicy.shouldSuspend(listOf(true, false, false)))
        assertTrue(SkillOutcomePolicy.shouldSuspend(listOf(false, false, false)))
    }

    @Test
    fun olderFailuresDoNotCount() {
        assertFalse(SkillOutcomePolicy.shouldSuspend(listOf(false, false, true, true, true)))
    }
}
