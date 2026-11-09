package com.coke.nestspace.container

/**
 * Small, deterministic policy for the automatic slot mode.
 *
 * A negative delta means the device is already below the configured reserve;
 * it reduces the target below the current number of running slots so the pool
 * can evict background sessions.
 */
internal object SlotMemoryPolicy {

    const val DEFAULT_SLOT_PSS_KB = 320L * 1024L
    const val MIN_RESERVE_KB = 768L * 1024L
    private const val RESERVE_RATIO_PERCENT = 15L

    fun estimateSlotPssKb(samples: List<Long>): Long {
        val useful = samples.filter { it > 0L }.sorted()
        if (useful.isEmpty()) return DEFAULT_SLOT_PSS_KB
        val index = ((useful.size - 1) * 3 / 4).coerceIn(useful.indices)
        return maxOf(DEFAULT_SLOT_PSS_KB, useful[index])
    }

    fun reserveKb(totalKb: Long, thresholdKb: Long): Long = maxOf(
        thresholdKb * 2L,
        totalKb / 100L * RESERVE_RATIO_PERCENT,
        MIN_RESERVE_KB
    )

    fun effectiveLimit(
        currentLimit: Int,
        runningSlots: Int,
        availableKb: Long,
        totalKb: Long,
        thresholdKb: Long,
        lowMemory: Boolean,
        slotPssKb: Long = DEFAULT_SLOT_PSS_KB
    ): Int {
        val reserve = reserveKb(totalKb, thresholdKb)
        val estimate = slotPssKb.coerceAtLeast(DEFAULT_SLOT_PSS_KB)
        val delta = Math.floorDiv(availableKb - reserve, estimate).toInt()
        val raw = (runningSlots + delta).coerceIn(1, ContainerProcessPool.SLOT_COUNT)
        val bounded = when {
            lowMemory -> minOf(currentLimit, raw)
            raw < currentLimit -> raw
            raw > currentLimit -> minOf(raw, currentLimit + 1)
            else -> currentLimit
        }
        return bounded.coerceIn(1, ContainerProcessPool.SLOT_COUNT)
    }
}
