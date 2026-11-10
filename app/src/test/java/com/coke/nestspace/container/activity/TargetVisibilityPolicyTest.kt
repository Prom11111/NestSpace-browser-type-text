package com.coke.nestspace.container.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetVisibilityPolicyTest {
    @Test
    fun visibleHostRunsTargetForegroundLifecycle() {
        assertTrue(
            TargetVisibilityPolicy.shouldRunForegroundLifecycle(
                hostResumed = true,
                mirrorMode = false,
                headless = false
            )
        )
    }

    @Test
    fun backgroundMirrorAndHeadlessHostsStillRunTargetLifecycle() {
        assertTrue(
            TargetVisibilityPolicy.shouldRunForegroundLifecycle(
                hostResumed = false,
                mirrorMode = true,
                headless = true
            )
        )
        assertTrue(
            TargetVisibilityPolicy.shouldKeepTargetActiveWhileHostHidden(
                pictureInPicture = false,
                mirrorMode = true,
                headless = true
            )
        )
    }

    @Test
    fun ordinaryBackgroundHostDoesNotKeepTargetForeground() {
        assertFalse(
            TargetVisibilityPolicy.shouldRunForegroundLifecycle(
                hostResumed = false,
                mirrorMode = false,
                headless = false
            )
        )
        assertFalse(
            TargetVisibilityPolicy.shouldKeepTargetActiveWhileHostHidden(
                pictureInPicture = false,
                mirrorMode = false,
                headless = false
            )
        )
    }
}
