package com.coke.nestspace.container

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotMemoryPolicyTest {

    private val gb = 1024L * 1024L
    private val mb = 1024L

    @Test
    fun generousMemoryCanUseTheFullPool() {
        val limit = SlotMemoryPolicy.effectiveLimit(
            currentLimit = 16,
            runningSlots = 0,
            availableKb = 7 * gb,
            totalKb = 12 * gb,
            thresholdKb = 256 * mb,
            lowMemory = false
        )

        assertEquals(16, limit)
    }

    @Test
    fun lowMemoryReducesCapacityAndCanGoBelowRunningSlots() {
        val limit = SlotMemoryPolicy.effectiveLimit(
            currentLimit = 8,
            runningSlots = 6,
            availableKb = 700 * mb,
            totalKb = 8 * gb,
            thresholdKb = 256 * mb,
            lowMemory = true,
            slotPssKb = 400 * mb
        )

        assertEquals(4, limit)
    }

    @Test
    fun capacityGrowsAtMostOneSlotPerRefresh() {
        val limit = SlotMemoryPolicy.effectiveLimit(
            currentLimit = 4,
            runningSlots = 4,
            availableKb = 6 * gb,
            totalKb = 12 * gb,
            thresholdKb = 256 * mb,
            lowMemory = false
        )

        assertEquals(5, limit)
    }

    @Test
    fun lowMemoryNeverIncreasesCapacity() {
        val limit = SlotMemoryPolicy.effectiveLimit(
            currentLimit = 4,
            runningSlots = 0,
            availableKb = 8 * gb,
            totalKb = 12 * gb,
            thresholdKb = 256 * mb,
            lowMemory = true
        )

        assertEquals(4, limit)
    }

    @Test
    fun slotEstimateUsesLargestRecentQuartileWithConservativeFloor() {
        assertEquals(
            320 * mb,
            SlotMemoryPolicy.estimateSlotPssKb(listOf(120 * mb, 200 * mb))
        )
        assertEquals(
            600 * mb,
            SlotMemoryPolicy.estimateSlotPssKb(
                listOf(300 * mb, 450 * mb, 600 * mb, 800 * mb)
            )
        )
    }
}
