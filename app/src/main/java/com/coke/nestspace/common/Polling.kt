package com.coke.nestspace.common

import kotlinx.coroutines.delay

data class PollResult<T : Any>(
    val value: T?,
    val checks: Int,
    val timedOut: Boolean
)

/**
 * Shared bounded polling loop for asynchronous state checks.
 *
 * [isComplete] controls completion. Reaching [maxChecks] is always a timeout;
 * callers must surface that separately instead of treating it as success.
 */
internal suspend fun <T : Any> pollUntil(
    maxChecks: Int,
    intervalMs: Long,
    probe: suspend () -> T?,
    isComplete: (T) -> Boolean
): PollResult<T> {
    val checks = maxChecks.coerceAtLeast(1)
    var last: T? = null
    repeat(checks) { index ->
        val current = probe()
        if (current != null) {
            last = current
            if (isComplete(current)) {
                return PollResult(
                    value = current,
                    checks = index + 1,
                    timedOut = false
                )
            }
        }
        if (index < checks - 1) delay(intervalMs.coerceAtLeast(0L))
    }
    return PollResult(
        value = last,
        checks = checks,
        timedOut = true
    )
}
