package com.coke.nestspace.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerSlotStateTest {

    @Test
    fun reservationCannotBeHandedToAnotherSessionBeforeCommit() {
        val state = ContainerSlotState(slotCount = 1)
        val first = state.planAcquire("package@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse

        assertEquals(
            ContainerSlotState.AcquirePlan.Wait,
            state.planAcquire("other@instance-1", 1, -1, -1, emptySet())
        )
        assertTrue(state.commitAcquire(first.lease))
        val replacement = state.planAcquire("other@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse
        assertEquals(0, replacement.lease.slot)
        assertEquals("package@instance-1", replacement.lease.previousKey)
    }

    @Test
    fun concurrentAllocationOfSameKeyWaitsForTheExistingReservation() {
        val state = ContainerSlotState(slotCount = 2)
        state.planAcquire("package@instance-1", 2, -1, -1, emptySet())

        assertEquals(
            ContainerSlotState.AcquirePlan.Wait,
            state.planAcquire("package@instance-1", 2, -1, -1, emptySet())
        )
    }

    @Test
    fun staleKillCannotClearANewerSlotGeneration() {
        val state = ContainerSlotState(slotCount = 1)
        val first = state.planAcquire("first@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse
        assertTrue(state.commitAcquire(first.lease))

        val removed = state.detachSlot(0)!!
        val newer = state.planAcquire("second@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse
        assertTrue(newer.lease.generation > removed.generation)
        assertTrue(state.commitAcquire(newer.lease))

        assertFalse(state.finishKill(removed.slot, removed.generation, stopped = true))
        assertEquals(ContainerSlotState.SlotState.LIVE, state.stateOf(0))
    }

    @Test
    fun loweringLimitOnlyRemovesSlotsAtOrAboveTheLimit() {
        val state = ContainerSlotState(slotCount = 3)
        commitFresh(state, "zero@instance-1", limit = 3, expectedSlot = 0)
        commitFresh(state, "one@instance-1", limit = 3, expectedSlot = 1)
        commitFresh(state, "two@instance-1", limit = 3, expectedSlot = 2)

        val removed = state.detachOverLimit(2)

        assertEquals(listOf(2), removed.map { it.slot })
        assertEquals(0, state.slotForKey("zero@instance-1"))
        assertEquals(1, state.slotForKey("one@instance-1"))
        assertNull(state.slotForKey("two@instance-1"))
    }

    @Test
    fun activeSlotIsNotEvictedDuringAllocation() {
        val state = ContainerSlotState(slotCount = 1)
        commitFresh(state, "active@instance-1", limit = 1, expectedSlot = 0)
        state.markActive(0)

        assertEquals(
            ContainerSlotState.AcquirePlan.Full,
            state.planAcquire("other@instance-1", 1, -1, -1, emptySet())
        )
        assertEquals(0, state.slotForKey("active@instance-1"))
    }

    @Test
    fun targetSubprocessIsEvictedBeforeTheAppMainProcess() {
        val state = ContainerSlotState(slotCount = 2)
        commitFresh(state, "package@instance-1", limit = 2, expectedSlot = 0)
        commitFresh(
            state,
            "package@instance-1|package:remote",
            limit = 2,
            expectedSlot = 1
        )

        val victim = state.detachEvictionVictim(
            limit = 2,
            protectedSlot = -1,
            protectActive = true,
            subprocessOnly = true,
            retainedSlots = emptySet()
        )

        assertEquals(1, victim?.slot)
        assertEquals(0, state.slotForKey("package@instance-1"))
    }

    @Test
    fun deadAssignmentOnlyPrunesTheMatchingGeneration() {
        val state = ContainerSlotState(slotCount = 1)
        val first = state.planAcquire("first@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse
        assertTrue(state.commitAcquire(first.lease))
        val staleSnapshot = state.liveAssignments().single()

        val removed = state.detachKey("first@instance-1")!!
        val second = state.planAcquire("second@instance-1", 1, -1, -1, emptySet())
            as ContainerSlotState.AcquirePlan.Reuse
        assertTrue(state.commitAcquire(second.lease))

        assertFalse(state.recordDead(staleSnapshot))
        assertEquals(0, state.slotForKey("second@instance-1"))
        assertFalse(state.finishKill(removed.slot, removed.generation, stopped = true))
        assertEquals(ContainerSlotState.SlotState.LIVE, state.stateOf(0))
    }

    private fun commitFresh(
        state: ContainerSlotState,
        key: String,
        limit: Int,
        expectedSlot: Int
    ) {
        val plan = state.planAcquire(key, limit, -1, -1, emptySet())
        val lease = (plan as ContainerSlotState.AcquirePlan.Reuse).lease
        assertEquals(expectedSlot, lease.slot)
        assertTrue(state.commitAcquire(lease))
    }
}
