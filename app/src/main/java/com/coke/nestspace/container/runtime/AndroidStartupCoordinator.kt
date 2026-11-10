package com.coke.nestspace.container.runtime

import android.os.SystemClock

enum class AndroidStartupPhase {
    CLASS_LOADER,
    APPLICATION_ATTACH,
    EMBEDDED_APPLICATION_ATTACH,
    PROCESS_APPLICATION,
    CONTENT_PROVIDERS,
    APPLICATION_CREATE,
    APPLICATION_COMPATIBILITY,
    HOST_BRIDGES,
    EMBEDDED_APPLICATION_CREATE,
    STARTUP_COMPATIBILITY,
    CRASH_GUARD,
    READY,
    FIRST_ACTIVITY_SCHEDULED,
    FIRST_ACTIVITY_OPENED
}

enum class AndroidStartupOutcome {
    SUCCESS,
    SKIPPED,
    FAILED
}

data class AndroidStartupPhaseResult(
    val phase: AndroidStartupPhase,
    val outcome: AndroidStartupOutcome,
    val durationMillis: Long,
    val detail: String? = null,
    val failure: String? = null
)

data class AndroidStartupReport(
    val phases: List<AndroidStartupPhaseResult>,
    val totalDurationMillis: Long
) {
    val completed: Boolean
        get() = phases.lastOrNull()?.phase == AndroidStartupPhase.FIRST_ACTIVITY_OPENED &&
            phases.none { it.outcome == AndroidStartupOutcome.FAILED }

    val failures: List<AndroidStartupPhaseResult>
        get() = phases.filter { it.outcome == AndroidStartupOutcome.FAILED }

    fun phase(name: AndroidStartupPhase): AndroidStartupPhaseResult? =
        phases.lastOrNull { it.phase == name }
}

/**
 * Executes the target process startup in Android's observable order and keeps
 * failures attached to the phase that produced them. This is intentionally a
 * scheduler/reporting boundary: framework-specific work stays in
 * ContainerSession.
 */
class AndroidStartupCoordinator(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val observer: (AndroidStartupPhaseResult) -> Unit = {},
    private val phaseStartedObserver: (AndroidStartupPhase) -> Unit = {}
) {
    private val lock = Any()
    private val results = mutableListOf<AndroidStartupPhaseResult>()

    fun <T> run(phase: AndroidStartupPhase, block: () -> T): T {
        val startedAt = clock()
        notifyPhaseStarted(phase)
        return try {
            block().also {
                record(phase, AndroidStartupOutcome.SUCCESS, clock() - startedAt)
            }
        } catch (error: Throwable) {
            record(
                phase,
                AndroidStartupOutcome.FAILED,
                clock() - startedAt,
                failure = failureOf(error)
            )
            throw error
        }
    }

    /** Optional framework shims must not hide a hard failure from diagnostics. */
    fun runOptional(phase: AndroidStartupPhase, block: () -> Unit): Throwable? {
        val startedAt = clock()
        notifyPhaseStarted(phase)
        return try {
            block()
            record(phase, AndroidStartupOutcome.SUCCESS, clock() - startedAt)
            null
        } catch (error: Throwable) {
            record(
                phase,
                AndroidStartupOutcome.FAILED,
                clock() - startedAt,
                failure = failureOf(error)
            )
            error
        }
    }

    fun mark(
        phase: AndroidStartupPhase,
        durationMillis: Long,
        detail: String? = null
    ) {
        record(
            phase = phase,
            outcome = AndroidStartupOutcome.SUCCESS,
            durationMillis = durationMillis.coerceAtLeast(0L),
            detail = detail
        )
    }

    fun markOnce(
        phase: AndroidStartupPhase,
        durationMillis: Long = 0L,
        detail: String? = null
    ) {
        synchronized(lock) {
            if (results.any { it.phase == phase }) return
        }
        mark(phase, durationMillis, detail)
    }

    fun skip(phase: AndroidStartupPhase, reason: String) {
        record(phase, AndroidStartupOutcome.SKIPPED, 0L, detail = reason)
    }

    fun report(): AndroidStartupReport = synchronized(lock) {
        val snapshot = results.toList()
        AndroidStartupReport(
            phases = snapshot,
            totalDurationMillis = snapshot.sumOf { it.durationMillis }
        )
    }

    private fun record(
        phase: AndroidStartupPhase,
        outcome: AndroidStartupOutcome,
        durationMillis: Long,
        detail: String? = null,
        failure: String? = null
    ) {
        val result = AndroidStartupPhaseResult(
            phase = phase,
            outcome = outcome,
            durationMillis = durationMillis,
            detail = detail,
            failure = failure
        )
        synchronized(lock) { results += result }
        observer(result)
    }

    private fun notifyPhaseStarted(phase: AndroidStartupPhase) {
        runCatching { phaseStartedObserver(phase) }
    }

    private fun failureOf(error: Throwable): String =
        "${error.javaClass.name}: ${error.message.orEmpty()}".trimEnd()
}
