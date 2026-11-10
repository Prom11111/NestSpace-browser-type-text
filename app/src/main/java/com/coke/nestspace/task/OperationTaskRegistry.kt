package com.coke.nestspace.task

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide registry for long-running operation tasks.
 *
 * State and progress have independent revisions. task_wait observes lifecycle
 * changes only, while UI collectors can observe high-frequency progress.
 */
object OperationTaskRegistry {

    enum class State(val api: String) {
        QUEUED("queued"),
        PREPARING("preparing"),
        RUNNING("running"),
        WAITING_USER("waiting_user"),
        PAUSED("paused"),
        CANCELLING("cancelling"),
        FINALIZING("finalizing"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELLED("cancelled");

        val terminal: Boolean
            get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
    }

    data class Snapshot(
        val id: String,
        val requestId: String?,
        val requestHash: String?,
        val ownerId: String,
        val parentTaskId: String?,
        val kind: String,
        val title: String,
        val replaceKey: String?,
        val state: State,
        val progressPercent: Int?,
        val progressCurrent: Long?,
        val progressTotal: Long?,
        val bytesPerSecond: Long?,
        val etaSeconds: Long?,
        val message: String,
        val result: String?,
        val error: String?,
        val cleanupError: String?,
        val stateRevision: Long,
        val progressRevision: Long,
        val lastProgressAt: Long,
        val lastHeartbeatAt: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val finishedAt: Long
    ) {
        val terminal: Boolean get() = state.terminal
        val idleMilliseconds: Long
            get() = if (terminal) 0L else {
                (System.currentTimeMillis() - lastProgressAt).coerceAtLeast(0L)
            }
    }

    data class StartResult(
        val task: Snapshot?,
        val created: Boolean,
        val errorCode: String? = null
    )

    data class WaitResult(
        val task: Snapshot?,
        val changed: Boolean,
        val timedOut: Boolean
    )

    data class CancelResult(
        val task: Snapshot?,
        val accepted: Boolean,
        val errorCode: String? = null
    )

    private class Entry(
        val id: String,
        val requestId: String?,
        val requestHash: String?,
        val ownerId: String,
        val parentTaskId: String?,
        val kind: String,
        val title: String,
        val replaceKey: String?,
        val createdAt: Long,
        onCancel: (() -> Unit)?
    ) {
        var cancelCallback: (() -> Unit)? = onCancel
        var state: State = State.PREPARING
        var progressPercent: Int? = null
        var progressCurrent: Long? = null
        var progressTotal: Long? = null
        var bytesPerSecond: Long? = null
        var etaSeconds: Long? = null
        var message: String = "started"
        var result: String? = null
        var error: String? = null
        var cleanupError: String? = null
        var stateRevision: Long = 0L
        var progressRevision: Long = 0L
        var lastProgressAt: Long = createdAt
        var lastHeartbeatAt: Long = createdAt
        var updatedAt: Long = createdAt
        var finishedAt: Long = 0L
        val updates = MutableStateFlow<Snapshot?>(null)
    }

    private val lock = Any()
    private val tasks = LinkedHashMap<String, Entry>()
    private val byRequest = HashMap<String, Entry>()
    private val callbackScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var journal: TaskJournal? = null
    private val _tasks = MutableStateFlow<List<Snapshot>>(emptyList())
    val tasksFlow: StateFlow<List<Snapshot>> = _tasks.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (journal != null) return
            val store = TaskJournal(context)
            journal = store
            store.recoverAndLoad().forEach { recovered ->
                val entry = Entry(
                    id = recovered.id,
                    requestId = recovered.requestId,
                    requestHash = recovered.requestHash,
                    ownerId = recovered.ownerId,
                    parentTaskId = recovered.parentTaskId,
                    kind = recovered.kind,
                    title = recovered.title,
                    replaceKey = recovered.replaceKey,
                    createdAt = recovered.createdAt,
                    onCancel = null
                ).apply {
                    state = recovered.state
                    progressPercent = recovered.progressPercent
                    progressCurrent = recovered.progressCurrent
                    progressTotal = recovered.progressTotal
                    bytesPerSecond = recovered.bytesPerSecond
                    etaSeconds = recovered.etaSeconds
                    message = recovered.message
                    result = recovered.result
                    error = recovered.error
                    cleanupError = recovered.cleanupError
                    stateRevision = 1L
                    progressRevision = 1L
                    lastProgressAt = recovered.lastProgressAt
                    lastHeartbeatAt = recovered.lastHeartbeatAt
                    updatedAt = recovered.updatedAt
                    finishedAt = recovered.finishedAt
                }
                tasks[entry.id] = entry
                requestKey(entry.ownerId, entry.requestId)?.let {
                    byRequest[it] = entry
                }
                publishLocked(entry, stateChanged = false)
                store.save(snapshotLocked(entry))
            }
            pruneLocked()
        }
    }

    fun start(
        ownerId: String,
        kind: String,
        title: String,
        replaceKey: String? = null,
        parentTaskId: String? = null,
        requestId: String? = null,
        requestHash: String? = null,
        onCancel: (() -> Unit)? = null
    ): StartResult {
        val cleanRequest = requestId?.trim()?.takeIf(String::isNotBlank)
        val key = requestKey(ownerId, cleanRequest)
        val replaced = mutableListOf<Entry>()
        val result: StartResult
        val createdEntry: Entry?
        synchronized(lock) {
            if (key != null) {
                byRequest[key]?.let { existing ->
                    val same = existing.requestHash == requestHash
                    return StartResult(
                        task = snapshotLocked(existing),
                        created = false,
                        errorCode = if (same) null else "REQUEST_ID_CONFLICT"
                    )
                }
            }
            val now = System.currentTimeMillis()
            if (!replaceKey.isNullOrBlank()) {
                tasks.values
                    .filter {
                        it.replaceKey == replaceKey &&
                            it.ownerId == ownerId &&
                            !it.state.terminal
                    }
                    .forEach { previous ->
                        previous.state = State.CANCELLING
                        previous.error = "replaced by a newer operation"
                        previous.message = "cancelling previous operation"
                        previous.updatedAt = now
                        publishLocked(previous, stateChanged = true)
                        replaced += previous
                    }
            }
            val entry = Entry(
                id = UUID.randomUUID().toString(),
                requestId = cleanRequest,
                requestHash = requestHash,
                ownerId = ownerId,
                parentTaskId = parentTaskId,
                kind = kind,
                title = title,
                replaceKey = replaceKey,
                createdAt = now,
                onCancel = onCancel
            )
            tasks[entry.id] = entry
            key?.let { byRequest[it] = entry }
            publishLocked(entry, stateChanged = true)
            pruneLocked()
            result = StartResult(snapshotLocked(entry), created = true)
            createdEntry = entry
        }
        replaced.forEach {
            persist(it)
            runCatching { it.cancelCallback?.invoke() }
        }
        persist(checkNotNull(createdEntry))
        return result
    }

    fun create(
        ownerId: String,
        kind: String,
        title: String,
        replaceKey: String? = null,
        onCancel: (() -> Unit)? = null
    ): Snapshot = requireNotNull(
        start(
            ownerId = ownerId,
            kind = kind,
            title = title,
            replaceKey = replaceKey,
            onCancel = onCancel
        ).task
    )

    fun transition(
        id: String,
        state: State,
        message: String? = null
    ): Snapshot? {
        val updated = synchronized(lock) {
            val task = tasks[id] ?: return@synchronized null
            if (!task.state.terminal) {
                task.state = state
                if (!message.isNullOrBlank()) task.message = message
                task.updatedAt = System.currentTimeMillis()
                if (state.terminal) task.finishedAt = task.updatedAt
                publishLocked(task, stateChanged = true)
            }
            task
        } ?: return null
        persist(updated)
        return snapshot(updated)
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
                val now = System.currentTimeMillis()
                task.progressPercent = percent?.coerceIn(0, 100)
                task.progressCurrent = current?.coerceAtLeast(0L)
                task.progressTotal = total?.takeIf { it >= 0L }
                task.bytesPerSecond = bytesPerSecond?.coerceAtLeast(0L)
                task.etaSeconds = etaSeconds?.takeIf { it >= 0L }
                if (!message.isNullOrBlank()) task.message = message
                task.progressRevision += 1L
                task.lastProgressAt = now
                task.lastHeartbeatAt = now
                task.updatedAt = now
                publishLocked(task, stateChanged = false)
            }
            task
        } ?: return null
        return snapshot(updated)
    }

    fun heartbeat(id: String): Snapshot? {
        val updated = synchronized(lock) {
            val task = tasks[id] ?: return@synchronized null
            if (!task.state.terminal) {
                task.lastHeartbeatAt = System.currentTimeMillis()
                publishLocked(task, stateChanged = false)
            }
            task
        } ?: return null
        return snapshot(updated)
    }

    fun succeed(
        id: String,
        result: String?,
        message: String = "completed"
    ): Snapshot? = finish(id, State.SUCCEEDED, message, result, null)

    fun fail(id: String, error: String): Snapshot? =
        finish(id, State.FAILED, error, null, error)

    fun requestCancel(
        id: String,
        reason: String = "cancelled",
        ownerId: String? = null
    ): CancelResult {
        val cancelled = mutableListOf<Entry>()
        val root = synchronized(lock) {
            val task = tasks[id] ?: return CancelResult(null, false, "TASK_NOT_FOUND")
            if (ownerId != null && task.ownerId != ownerId) {
                return CancelResult(null, false, "TASK_NOT_FOUND")
            }
            val pending = ArrayDeque<Entry>()
            pending.add(task)
            val seen = HashSet<String>()
            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!seen.add(current.id)) continue
                tasks.values
                    .filter { it.parentTaskId == current.id }
                    .forEach(pending::addLast)
                if (current.state.terminal || current.state == State.CANCELLING) {
                    continue
                }
                current.state = State.CANCELLING
                current.error = reason
                current.message = "cancelling"
                current.updatedAt = System.currentTimeMillis()
                publishLocked(current, stateChanged = true)
                cancelled += current
            }
            task
        }
        cancelled.forEach(::persist)
        cancelled.forEach { task ->
            callbackScope.launch {
                runCatching { task.cancelCallback?.invoke() }
            }
        }
        return CancelResult(
            snapshot(root),
            accepted = cancelled.any { it.id == root.id }
        )
    }

    /** Worker confirms that cancellation cleanup finished. */
    fun markCancelled(
        id: String,
        reason: String = "cancelled",
        cleanupError: String? = null
    ): Snapshot? = finish(
        taskId = id,
        state = State.CANCELLED,
        message = if (cleanupError == null) "cancelled" else "cancelled with cleanup errors",
        result = null,
        error = reason,
        cleanupError = cleanupError
    )

    /** Compatibility wrapper for callers that do not need the accepted flag. */
    fun cancel(id: String, reason: String = "cancelled"): Snapshot? =
        requestCancel(id, reason).task

    fun status(id: String, ownerId: String? = null): Snapshot? =
        synchronized(lock) {
            tasks[id]
                ?.takeIf { ownerId == null || it.ownerId == ownerId }
                ?.let(::snapshotLocked)
        }

    fun updates(id: String): StateFlow<Snapshot?>? =
        synchronized(lock) { tasks[id]?.updates }

    fun setCancelCallback(id: String, callback: () -> Unit) {
        synchronized(lock) {
            tasks[id]?.cancelCallback = callback
        }
    }

    suspend fun wait(
        id: String,
        timeoutMs: Long,
        ownerId: String? = null
    ): WaitResult {
        val initial = status(id, ownerId)
            ?: return WaitResult(null, changed = false, timedOut = false)
        val stateFlow = updates(id)
            ?: return WaitResult(null, changed = false, timedOut = false)
        if (initial.terminal) {
            return WaitResult(initial, changed = false, timedOut = false)
        }
        val changed = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            stateFlow.filterNotNull().first {
                it.stateRevision > initial.stateRevision || it.terminal
            }
        }
        return if (changed == null) {
            WaitResult(
                task = status(id) ?: initial,
                changed = false,
                timedOut = true
            )
        } else {
            WaitResult(changed, changed = true, timedOut = false)
        }
    }

    fun list(ownerId: String, limit: Int = 20): List<Snapshot> =
        synchronized(lock) {
            tasks.values
                .filter { it.ownerId == ownerId }
                .sortedByDescending { it.createdAt }
                .take(limit.coerceIn(1, 100))
                .map(::snapshotLocked)
        }

    fun children(
        parentId: String,
        ownerId: String? = null
    ): List<Snapshot> = synchronized(lock) {
        tasks.values
            .filter {
                it.parentTaskId == parentId &&
                    (ownerId == null || it.ownerId == ownerId)
            }
            .sortedBy { it.createdAt }
            .map(::snapshotLocked)
    }

    /**
     * Remove a finished task card and its finished descendants. Refuse the
     * deletion while any task in the chain is still active so cancellation and
     * cleanup cannot be bypassed by hiding the parent.
     */
    fun dismiss(
        taskId: String,
        ownerId: String? = null
    ): Boolean {
        val removed = synchronized(lock) {
            val target = tasks[taskId] ?: return@synchronized null
            if (ownerId != null && target.ownerId != ownerId) {
                return@synchronized null
            }
            if (!target.state.terminal) return@synchronized null
            val descendants = mutableSetOf(taskId)
            var changed = true
            while (changed) {
                changed = tasks.values.any {
                    it.parentTaskId in descendants && descendants.add(it.id)
                }
            }
            if (tasks.values.any {
                    it.id in descendants && !it.state.terminal
                }
            ) {
                return@synchronized null
            }
            descendants.mapNotNull { id ->
                tasks.remove(id)?.also { task ->
                    requestKey(task.ownerId, task.requestId)
                        ?.let(byRequest::remove)
                }
            }
        } ?: return false
        val store = synchronized(lock) { journal }
        removed.forEach { store?.delete(it.id) }
        synchronized(lock) {
            _tasks.value = tasks.values
                .sortedByDescending { it.createdAt }
                .map(::snapshotLocked)
        }
        return true
    }

    /** Remove every finished task visible to an owner, including interrupted history. */
    fun dismissTerminal(ownerId: String? = null): Int {
        val removable = synchronized(lock) {
            tasks.values
                .filter {
                    (ownerId == null || it.ownerId == ownerId) &&
                        it.state.terminal &&
                        tasks.values.none { child ->
                            child.parentTaskId == it.id && !child.state.terminal
                        }
                }
                .map { it.id }
        }
        return removable.count(::dismiss)
    }

    private fun finish(
        taskId: String,
        state: State,
        message: String,
        result: String?,
        error: String?,
        cleanupError: String? = null
    ): Snapshot? {
        val task = synchronized(lock) {
            val task = tasks[taskId] ?: return@synchronized null
            if (!task.state.terminal) {
                task.state = state
                task.message = message
                task.result = result
                task.error = error
                task.cleanupError = cleanupError
                task.updatedAt = System.currentTimeMillis()
                task.finishedAt = task.updatedAt
                publishLocked(task, stateChanged = true)
            }
            task
        } ?: return null
        persist(task)
        return snapshot(task)
    }

    private fun publishLocked(task: Entry, stateChanged: Boolean) {
        if (stateChanged) task.stateRevision += 1L
        val value = snapshotLocked(task)
        task.updates.value = value
        _tasks.value = tasks.values
            .sortedByDescending { it.createdAt }
            .map(::snapshotLocked)
    }

    private fun snapshot(task: Entry): Snapshot =
        synchronized(lock) { snapshotLocked(task) }

    private fun snapshotLocked(task: Entry): Snapshot = Snapshot(
        id = task.id,
        requestId = task.requestId,
        requestHash = task.requestHash,
        ownerId = task.ownerId,
        parentTaskId = task.parentTaskId,
        kind = task.kind,
        title = task.title,
        replaceKey = task.replaceKey,
        state = task.state,
        progressPercent = task.progressPercent,
        progressCurrent = task.progressCurrent,
        progressTotal = task.progressTotal,
        bytesPerSecond = task.bytesPerSecond,
        etaSeconds = task.etaSeconds,
        message = task.message,
        result = task.result,
        error = task.error,
        cleanupError = task.cleanupError,
        stateRevision = task.stateRevision,
        progressRevision = task.progressRevision,
        lastProgressAt = task.lastProgressAt,
        lastHeartbeatAt = task.lastHeartbeatAt,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
        finishedAt = task.finishedAt
    )

    private fun persist(task: Entry) {
        val store = synchronized(lock) { journal } ?: return
        runCatching { store.save(snapshot(task)) }
    }

    private fun requestKey(ownerId: String, requestId: String?): String? =
        requestId?.let { "$ownerId\u0000$it" }

    private fun pruneLocked() {
        if (tasks.size <= MAX_TASKS) return
        val parentsWithActiveChildren = tasks.values
            .filter { !it.state.terminal && it.parentTaskId != null }
            .mapNotNullTo(HashSet()) { it.parentTaskId }
        val removable = tasks.values
            .filter { it.state.terminal && it.id !in parentsWithActiveChildren }
            .sortedBy { it.createdAt }
        removable.take((tasks.size - MAX_TASKS).coerceAtLeast(0))
            .forEach { task ->
                tasks.remove(task.id)
                requestKey(task.ownerId, task.requestId)?.let(byRequest::remove)
            }
    }

    private const val MAX_TASKS = 200
}
