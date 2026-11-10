package com.coke.nestspace.container

/**
 * Thread-safe slot ownership state. Every lease carries a generation so a
 * slow kill or process probe can never publish a result over a newer owner.
 *
 * This class deliberately performs no file, process or preference I/O. The
 * caller holds the pool state lock only while selecting/reserving a slot and
 * does kill/wait work after [planAcquire] returns.
 */
internal class ContainerSlotState(
    private val slotCount: Int
) {

    internal enum class SlotState {
        FREE,
        RESERVED,
        LIVE,
        KILLING
    }

    internal data class Assignment(
        val key: String,
        val slot: Int,
        val generation: Long
    )

    internal data class Lease(
        val key: String,
        val slot: Int,
        val generation: Long,
        val previousKey: String?
    )

    internal data class RemovedAssignment(
        val key: String,
        val slot: Int,
        val generation: Long
    )

    internal sealed interface AcquirePlan {
        data class Existing(val key: String, val slot: Int, val generation: Long) : AcquirePlan
        data class Reuse(val lease: Lease) : AcquirePlan
        data object Wait : AcquirePlan
        data object Full : AcquirePlan
    }

    private val monitor = Object()
    private val assignments = java.util.LinkedHashMap<String, Int>(8, 0.75f, true)
    private val owners = arrayOfNulls<String>(slotCount)
    private val states = Array(slotCount) { SlotState.FREE }
    private val reservations = arrayOfNulls<String>(slotCount)
    private val generations = LongArray(slotCount)

    private var lastAcquiredSlot = -1
    private var activeSlot = -1

    fun planAcquire(
        key: String,
        limit: Int,
        protectedSlot: Int,
        preferredSlot: Int,
        retainedSlots: Set<Int>,
        excludedSlots: Set<Int> = emptySet()
    ): AcquirePlan = synchronized(monitor) {
        val existing = assignments[key]
        if (existing != null) {
            if (existing in excludedSlots) return@synchronized AcquirePlan.Full
            if (states[existing] != SlotState.LIVE) return@synchronized AcquirePlan.Wait
            if (existing < limit || existing == protectedSlot || existing == activeSlot) {
                lastAcquiredSlot = existing
                return@synchronized AcquirePlan.Existing(
                    key = key,
                    slot = existing,
                    generation = generations[existing]
                )
            }
            detachLocked(existing, key)
        }

        if (reservations.contains(key)) return@synchronized AcquirePlan.Wait

        val candidate = preferredSlot.takeIf { slot ->
            slot in 0 until limit &&
                slot != protectedSlot &&
                slot !in excludedSlots &&
                states[slot] == SlotState.FREE &&
                owners[slot] == null
        } ?: (0 until limit).firstOrNull { slot ->
            slot != protectedSlot &&
                slot !in excludedSlots &&
                states[slot] == SlotState.FREE &&
                owners[slot] == null
        } ?: firstEvictionCandidate(
            key = key,
            limit = limit,
            protectedSlot = protectedSlot,
            retainedSlots = retainedSlots,
            subprocessOnly = true,
            excludedSlots = excludedSlots
        ) ?: firstEvictionCandidate(
            key = key,
            limit = limit,
            protectedSlot = protectedSlot,
            retainedSlots = retainedSlots,
            subprocessOnly = false,
            excludedSlots = excludedSlots
        ) ?: (0 until limit).firstOrNull { slot ->
            slot != protectedSlot &&
                slot !in excludedSlots &&
                states[slot] == SlotState.KILLING &&
                reservations[slot] == null
        } ?: return@synchronized if (reservations.any { it != null }) {
            AcquirePlan.Wait
        } else {
            AcquirePlan.Full
        }

        val previousKey = owners[candidate]
        generations[candidate] += 1
        reservations[candidate] = key
        states[candidate] = SlotState.RESERVED
        AcquirePlan.Reuse(
            Lease(
                key = key,
                slot = candidate,
                generation = generations[candidate],
                previousKey = previousKey
            )
        )
    }

    fun beginKill(lease: Lease): Boolean = synchronized(monitor) {
        if (!ownsLeaseLocked(lease) || states[lease.slot] != SlotState.RESERVED) {
            return@synchronized false
        }
        states[lease.slot] = SlotState.KILLING
        true
    }

    fun commitAcquire(lease: Lease): Boolean = synchronized(monitor) {
        if (!ownsLeaseLocked(lease)) return@synchronized false
        lease.previousKey?.let { previous ->
            if (assignments[previous] == lease.slot) assignments.remove(previous)
        }
        assignments.remove(lease.key)
        assignments[lease.key] = lease.slot
        owners[lease.slot] = lease.key
        reservations[lease.slot] = null
        states[lease.slot] = SlotState.LIVE
        lastAcquiredSlot = lease.slot
        monitor.notifyAll()
        true
    }

    fun abortAcquire(lease: Lease): Boolean = synchronized(monitor) {
        if (!ownsLeaseLocked(lease)) return@synchronized false
        reservations[lease.slot] = null
        val previous = lease.previousKey
        states[lease.slot] = if (
            previous != null &&
            assignments[previous] == lease.slot &&
            owners[lease.slot] == previous
        ) {
            SlotState.LIVE
        } else {
            SlotState.KILLING
        }
        monitor.notifyAll()
        true
    }

    fun confirmExisting(key: String, slot: Int, generation: Long): Boolean =
        synchronized(monitor) {
            slot in 0 until slotCount &&
                assignments[key] == slot &&
                owners[slot] == key &&
                generations[slot] == generation &&
                states[slot] == SlotState.LIVE
        }

    fun isCurrent(key: String, slot: Int): Boolean = synchronized(monitor) {
        slot in 0 until slotCount &&
            assignments[key] == slot &&
            owners[slot] == key &&
            states[slot] == SlotState.LIVE
    }

    fun waitForReservation(key: String, timeoutMs: Long) {
        synchronized(monitor) {
            if (reservations.none { it != null }) return
            runCatching { monitor.wait(timeoutMs.coerceAtLeast(1L)) }
        }
    }

    fun detachSlot(slot: Int): RemovedAssignment? = synchronized(monitor) {
        if (slot !in 0 until slotCount) return@synchronized null
        val key = owners[slot] ?: assignments.entries.firstOrNull { it.value == slot }?.key
            ?: return@synchronized null
        detachLocked(slot, key)
    }

    fun detachKey(key: String): RemovedAssignment? = synchronized(monitor) {
        val slot = assignments[key] ?: return@synchronized null
        detachLocked(slot, key)
    }

    fun detachKeys(predicate: (String) -> Boolean): List<RemovedAssignment> =
        synchronized(monitor) {
            assignments.keys.filter(predicate).mapNotNull { key ->
                assignments[key]?.let { slot -> detachLocked(slot, key) }
            }
        }

    fun detachOverLimit(limit: Int): List<RemovedAssignment> = synchronized(monitor) {
        if (lastAcquiredSlot >= limit) lastAcquiredSlot = -1
        assignments.entries
            .filter { it.value >= limit }
            .map { it.key to it.value }
            .mapNotNull { (key, slot) -> detachLocked(slot, key) }
    }

    fun detachAutoVictim(
        target: Int,
        protectedSlot: Int,
        retainedSlots: Set<Int>
    ): RemovedAssignment? = synchronized(monitor) {
        if (assignments.size <= target) return@synchronized null
        val candidate = assignments.entries.firstOrNull { (key, slot) ->
            slot != protectedSlot &&
                slot != activeSlot &&
                slot != lastAcquiredSlot &&
                slot !in retainedSlots &&
                key.contains('|')
        } ?: assignments.entries.firstOrNull { (_, slot) ->
            slot != protectedSlot &&
                slot != activeSlot &&
                slot != lastAcquiredSlot &&
                slot !in retainedSlots
        } ?: return@synchronized null
        detachLocked(candidate.value, candidate.key)
    }

    fun detachEvictionVictim(
        limit: Int,
        protectedSlot: Int,
        protectActive: Boolean,
        subprocessOnly: Boolean,
        retainedSlots: Set<Int>
    ): RemovedAssignment? = synchronized(monitor) {
        val candidate = assignments.entries.firstOrNull { (key, slot) ->
            slot < limit &&
                slot != protectedSlot &&
                (!protectActive || slot != activeSlot) &&
                slot !in retainedSlots &&
                (!subprocessOnly || key.contains('|'))
        } ?: return@synchronized null
        detachLocked(candidate.value, candidate.key)
    }

    fun finishKill(slot: Int, generation: Long, stopped: Boolean): Boolean =
        synchronized(monitor) {
            if (slot !in 0 until slotCount || generations[slot] != generation) {
                return@synchronized false
            }
            if (reservations[slot] != null) return@synchronized false
            states[slot] = if (stopped) SlotState.FREE else SlotState.KILLING
            monitor.notifyAll()
            true
        }

    fun recordDead(assignment: Assignment): Boolean = synchronized(monitor) {
        val slot = assignment.slot
        if (
            slot !in 0 until slotCount ||
            assignments[assignment.key] != slot ||
            owners[slot] != assignment.key ||
            generations[slot] != assignment.generation ||
            states[slot] != SlotState.LIVE
        ) {
            return@synchronized false
        }
        assignments.remove(assignment.key)
        owners[slot] = null
        states[slot] = SlotState.FREE
        if (activeSlot == slot) activeSlot = -1
        if (lastAcquiredSlot == slot) lastAcquiredSlot = -1
        true
    }

    fun liveAssignments(): List<Assignment> = synchronized(monitor) {
        assignments.entries.mapNotNull { (key, slot) ->
            if (states[slot] == SlotState.LIVE && owners[slot] == key) {
                Assignment(key, slot, generations[slot])
            } else {
                null
            }
        }
    }

    fun slotCount(): Int = synchronized(monitor) { assignments.size }

    fun liveSlots(): Set<Int> = synchronized(monitor) {
        liveAssignmentsLocked().mapTo(linkedSetOf()) { it.slot }
    }

    fun lastSlot(): Int? = synchronized(monitor) {
        lastAcquiredSlot.takeIf { it in 0 until slotCount }
    }

    fun activeSlot(): Int = synchronized(monitor) {
        activeSlot.takeIf { it in 0 until slotCount } ?: -1
    }

    fun slotForKey(key: String): Int? = synchronized(monitor) {
        val slot = assignments[key] ?: return@synchronized null
        slot.takeIf { states[it] == SlotState.LIVE && owners[it] == key }
    }

    fun slotFor(packageName: String?, instanceId: String?): Int? = synchronized(monitor) {
        if (packageName == null && instanceId == null) {
            return@synchronized lastAcquiredSlot.takeIf { it in 0 until slotCount }
        }
        liveAssignmentsLocked().lastOrNull { assignment ->
            val key = assignment.key
            if (key.contains('|')) return@lastOrNull false
            val assignedPackage = key.substringBefore('@')
            val assignedInstance = instanceOf(key)
            (packageName == null || assignedPackage == packageName) &&
                (instanceId == null || assignedInstance == instanceId)
        }?.slot
    }

    fun findRunningSlot(packageName: String, instanceId: String?): Assignment? =
        synchronized(monitor) {
            liveAssignmentsLocked().lastOrNull { assignment ->
                val key = assignment.key
                if (key.contains('|')) return@lastOrNull false
                key.substringBefore('@') == packageName &&
                    (instanceId == null || instanceOf(key) == instanceId)
            }
        }

    fun ownerOfSlot(slot: Int): Pair<String, String>? = synchronized(monitor) {
        val assignment = liveAssignmentsLocked().firstOrNull { it.slot == slot }
            ?: return@synchronized null
        val key = assignment.key
        if (key.contains('|')) return@synchronized null
        key.substringBefore('@') to instanceOf(key)
    }

    fun markActive(slot: Int) {
        synchronized(monitor) {
            activeSlot = slot.takeIf { it in 0 until slotCount } ?: -1
        }
    }

    fun clearActive(slot: Int) {
        synchronized(monitor) {
            if (activeSlot == slot) activeSlot = -1
        }
    }

    internal fun stateOf(slot: Int): SlotState? = synchronized(monitor) {
        states.getOrNull(slot)
    }

    internal fun generationOf(slot: Int): Long? = synchronized(monitor) {
        generations.getOrNull(slot)
    }

    private fun firstEvictionCandidate(
        key: String,
        limit: Int,
        protectedSlot: Int,
        retainedSlots: Set<Int>,
        subprocessOnly: Boolean,
        excludedSlots: Set<Int>
    ): Int? = assignments.entries.firstOrNull { (candidateKey, slot) ->
        candidateKey != key &&
            slot < limit &&
            slot != protectedSlot &&
            slot !in excludedSlots &&
            slot != activeSlot &&
            slot !in retainedSlots &&
            states[slot] == SlotState.LIVE &&
            (!subprocessOnly || candidateKey.contains('|'))
    }?.value

    private fun detachLocked(slot: Int, key: String): RemovedAssignment {
        if (assignments[key] == slot) assignments.remove(key)
        if (owners[slot] == key) owners[slot] = null
        generations[slot] += 1
        reservations[slot] = null
        states[slot] = SlotState.KILLING
        if (activeSlot == slot) activeSlot = -1
        if (lastAcquiredSlot == slot) lastAcquiredSlot = -1
        monitor.notifyAll()
        return RemovedAssignment(key, slot, generations[slot])
    }

    private fun ownsLeaseLocked(lease: Lease): Boolean =
        lease.slot in 0 until slotCount &&
            generations[lease.slot] == lease.generation &&
            reservations[lease.slot] == lease.key

    private fun liveAssignmentsLocked(): List<Assignment> =
        assignments.entries.mapNotNull { (key, slot) ->
            if (states[slot] == SlotState.LIVE && owners[slot] == key) {
                Assignment(key, slot, generations[slot])
            } else {
                null
            }
        }

    private fun instanceOf(key: String): String =
        key.substringAfter('@', "instance-1").substringBefore('|')
}
