package com.coke.nestspace.container.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerBackPolicyTest {
    @Test
    fun rootHostWithoutCallbacksReturnsToDesktop() {
        assertTrue(
            ContainerBackPolicy.shouldReturnRootToDesktop(
                isTaskRoot = true,
                hasEnabledCallbacks = false
            )
        )
    }

    @Test
    fun nestedHostsAndEnabledCallbacksKeepNormalBackBehavior() {
        assertFalse(
            ContainerBackPolicy.shouldReturnRootToDesktop(
                isTaskRoot = false,
                hasEnabledCallbacks = false
            )
        )
        assertFalse(
            ContainerBackPolicy.shouldReturnRootToDesktop(
                isTaskRoot = true,
                hasEnabledCallbacks = true
            )
        )
    }
}
