package com.coke.nestspace.task

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Registers task-owned resources as they are created and releases them in
 * reverse order. Each release is bounded so one stuck resource cannot block
 * shutdown forever.
 */
internal class ResourceScope(
    private val releaseTimeoutMs: Long = 5_000L
) {
    private val lock = Any()
    private val releases = ArrayList<suspend () -> Unit>()
    private var closed = false

    suspend fun register(release: suspend () -> Unit) {
        val accepted = synchronized(lock) {
            if (!closed) releases += release
            !closed
        }
        if (!accepted) release()
    }

    suspend fun <T : Any> acquire(
        create: suspend () -> T,
        release: suspend (T) -> Unit
    ): T {
        val value = create()
        register { release(value) }
        return value
    }

    suspend fun close(): Throwable? {
        val pending = synchronized(lock) {
            closed = true
            releases.asReversed().toList().also { releases.clear() }
        }
        var firstFailure: Throwable? = null
        for (release in pending) {
            try {
                withContext(NonCancellable) {
                    withTimeout(releaseTimeoutMs.coerceAtLeast(1L)) {
                        release()
                    }
                }
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t else {
                    firstFailure.addSuppressed(t)
                }
            }
        }
        return firstFailure
    }
}
