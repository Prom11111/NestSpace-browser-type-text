package com.coke.nestspace.container

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetFragmentPagerHookTest {
    @Test
    fun detectsDuplicateItemIdsAndRepeatedFragmentInstances() {
        assertEquals(
            listOf(2),
            TargetFragmentPagerHook.duplicatePositions(
                itemIds = listOf("home", "video", "home"),
                fragmentIdentities = listOf(11, 12, 13)
            )
        )
        assertEquals(
            listOf(1),
            TargetFragmentPagerHook.duplicatePositions(
                itemIds = listOf("home", "video"),
                fragmentIdentities = listOf(11, 11)
            )
        )
    }

    @Test
    fun ignoresEmptyAndDistinctPositions() {
        assertEquals(
            emptyList<Int>(),
            TargetFragmentPagerHook.duplicatePositions(
                itemIds = listOf(null, null, "video"),
                fragmentIdentities = listOf(0, 0, 12)
            )
        )
    }

    @Test
    fun detectsDuplicatePendingAddsBeforeCommit() {
        assertEquals(
            listOf(1, 2),
            TargetFragmentPagerHook.stalePendingAddPositions(
                listOf(
                    TargetFragmentPagerHook.PendingFragmentAdd(
                        fragmentIdentity = 11,
                        tag = "android:switcher:1:0",
                        alreadyAdded = false
                    ),
                    TargetFragmentPagerHook.PendingFragmentAdd(
                        fragmentIdentity = 11,
                        tag = "android:switcher:1:0",
                        alreadyAdded = false
                    ),
                    TargetFragmentPagerHook.PendingFragmentAdd(
                        fragmentIdentity = 12,
                        tag = "android:switcher:1:0",
                        alreadyAdded = false
                    )
                )
            )
        )
        assertEquals(
            listOf(0),
            TargetFragmentPagerHook.stalePendingAddPositions(
                listOf(
                    TargetFragmentPagerHook.PendingFragmentAdd(
                        fragmentIdentity = 21,
                        tag = "android:switcher:1:1",
                        alreadyAdded = true
                    )
                )
            )
        )
    }
}
