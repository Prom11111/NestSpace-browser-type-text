package com.coke.nestspace.task

import java.util.UUID

/**
 * Process-local registry for long-running operations exposed to the assistant.
 *
 * Tools should return a task id immediately, report progress here, and make
 * completion/failure explicit. poll_tool observes task_status; task_cancel
 * invokes the registered cancellation callback.
 */
object OperationTaskRegistry {

    enum class State(val api: String) {
        QUEUED("queued"),
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELLED("cancelled");

        val terminal: Boolean
            get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    }

    data class Snapshot(
        val id: String,
        val ownerId: String,
        val kind: String,
        val title: String,
        val state: State,
        val progressPercent: Int?,
        val progressCurrent: Long?,
        val progressTotal: Long?,
        val bytesPerSecond: Long?,
        val etaSeconds: Long?,
        val message: String,
        val result: String?,
        val error: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val finishedAt: Long
    ) {
        val terminal: Boolean get() = state.terminal
    }

    private class Entry(
        val id: String,
        val ownerId: String,
        val kind: String,
        val title: String,
        val replaceKey: String?,
        val createdAt: Long,
        val onCancel: (() -> Unit)?
    ) {
        var state: State = State.QUEUED
        var progressPercent: Int? = null
        var progressCurrent: Long? = null
        var progressTotal: Long? = null
        var bytesPerSecond: Long? = null
        var etaSeconds: Long? = null
        var message: String = ""
        var result: String? = null
        var error: String? = null
        var updatedAt: Long = createdAt
        var finishedAt: Long = 0L
    }

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Entry>()

    fun create(
        ownerId: String,
        kind: String,
        title: String,
        replaceKey: String? = null,
        onCancel: (() -> Unit)? = null
    ): Snapshot {
        val now = System.currentTimeMillis()
        val replaced = mutableListOf<Entry>()
        val entry: Entry
        synchronized(lock) {
            if (!replaceKey.isNullOrBlank()) {
                tasks.values
                    .filter {
                        it.replaceKey == replaceKey &&
                            it.ownerId == ownerId &&
                            !it.state.terminal
                    }
                    .forEach { previous ->
                        previous.state = State.CANCELLED
                        previous.error = "replaced by a newer operation"
                        previous.updatedAt = now
                        previous.finishedAt = now
                        replaced += previous
                    }
            }
            entry = Entry(
                id = UUID.randomUUID().toString(),
                ownerId = ownerId,
                kind = kind,
                title = title,
                replaceKey = replaceKey,
                createdAt = now,
                onCancel = onCancel
            ).apply {
                state = State.RUNNING
                message = "started"
            }
            tasks[entry.id] = entry
            pruneLocked()
        }
        replaced.forEach { runCatching { it.onCancel?.invoke() } }
        return snapshot(entry)
    }

    fun progress(
        id: String,
        percent: Int?,
        message: String? = null,
        current: Long? = null,
        total: Long? = null,
        bytesPerSecond: Long? = null,
        etaSeconds: Long? = null
    ): Snapshot? {
        val updated = synchronized(lock) {
            val task = tasks[id] ?: return@synchronized null
            if (!task.state.terminal) {
                task.progressPercent = percent?.coerceIn(0, 100)
                task.progressCurrent = current?.coerceAtLeast(0L)
                task.progressTotal = total?.takeIf { it >= 0L }
                task.bytesPerSecond = bytesPerSecond?.coerceAtLeast(0L)
                task.etaSeconds = etaSeconds?.takeIf { it >= 0L }
                if (!message.isNullOrBlank()) task.message = message
                task.updatedAt = System.currentTimeMillis()
            }
            task
        } ?: return null
        return snapshot(updated)
    }

    fun succeed(id: String, result: String?, message: String = "completed"): Snapshot? =
        finish(id, State.SUCCEEDED, message, result, null)

    fun fail(id: String, error: String): Snapshot? =
        finish(id, State.FAILED, error, null, error)

    fun cancel(id: String, reason: String = "cancelled"): Snapshot? {
        var changed = false
        val cancelled = synchronized(lock) {
            val task = tasks[id] ?: return@synchronized null
            if (!task.state.terminal) {
                changed = true
                task.state = State.CANCELLED
                task.error = reason
                task.message = reason
                task.updatedAt = System.currentTimeMillis()
                task.finishedAt = task.updatedAt
            }
            task
        } ?: return null
        if (changed) runCatching { cancelled.onCancel?.invoke() }
        return snapshot(cancelled)
    }

    fun status(id: String, ownerId: String? = null): Snapshot? =
        synchronized(lock) {
            tasks[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?.let(::snapshot)
        }

    fun list(ownerId: String, limit: Int = 20): List<Snapshot> =
        synchronized(lock) {
            tasks.values
                .filter { it.ownerId == ownerId }
                .sortedByDescending { it.createdAt }
                .take(limit.coerceIn(1, 100))
                .map(::snapshot)
        }

    private fun finish(
        id: String,
        state: State,
        message: String,
        result: String?,
        error: String?
    ): Snapshot? = synchronized(lock) {
        val task = tasks[id] ?: return@synchronized null
        if (!task.state.terminal) {
            task.state = state
            task.message = message
            task.result = result
            task.error = error
            task.updatedAt = System.currentTimeMillis()
            task.finishedAt = task.updatedAt
        }
        snapshot(task)
    }

    private fun snapshot(task: Entry): Snapshot = Snapshot(
        id = task.id,
        ownerId = task.ownerId,
        kind = task.kind,
        title = task.title,
        state = task.state,
        progressPercent = task.progressPercent,
        progressCurrent = task.progressCurrent,
        progressTotal = task.progressTotal,
        bytesPerSecond = task.bytesPerSecond,
        etaSeconds = task.etaSeconds,
        message = task.message,
        result = task.result,
        error = task.error,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
        finishedAt = task.finishedAt
    )

    private fun pruneLocked() {
        if (tasks.size <= MAX_TASKS) return
        tasks.values
            .filter { it.state.terminal }
            .sortedBy { it.createdAt }
            .take(tasks.size - MAX_TASKS)
            .forEach { tasks.remove(it.id) }
    }

    private const val MAX_TASKS = 100
}
