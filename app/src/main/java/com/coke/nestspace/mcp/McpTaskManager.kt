package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.task.OperationTaskRegistry
import com.coke.nestspace.tools.ContainerDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest

/**
 * Async task queue for the MCP control plane.
 *
 * The on-device assistant can run one turn at a time, so tasks are queued and
 * started as the previous one finishes. A task survives while the app process
 * lives; after a process restart clients should retry with a new request_id.
 */
class McpTaskManager(context: Context) {

    enum class Status(val api: String) {
        QUEUED("queued"),
        RUNNING("running"),
        WAITING_FOR_USER("waiting_for_user"),
        CANCELLING("cancelling"),
        DONE("done"),
        FAILED("failed"),
        CANCELLED("cancelled");

        val terminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    }

    data class TaskView(
        val id: String,
        val ownerId: String,
        val goal: String,
        val requestId: String?,
        val displayMode: ContainerDisplayMode,
        val status: Status,
        val createdAt: Long,
        val startedAt: Long,
        val finishedAt: Long,
        val answer: String,
        val error: String?,
        val progress: String,
        val queuePosition: Int,
        val revision: Long
    )

    data class WaitResult(
        val task: TaskView,
        val changed: Boolean,
        val timedOut: Boolean
    )

    sealed class StartResult {
        data class Started(val task: TaskView) : StartResult()
        data class Error(val code: String, val message: String) : StartResult()
    }

    private class Task(
        val id: String,
        val ownerId: String,
        val goal: String,
        val requestId: String?,
        val displayMode: ContainerDisplayMode,
        val createdAt: Long
    ) {
        var status: Status = Status.QUEUED
        var startedAt: Long = 0
        var finishedAt: Long = 0
        var answer: String = ""
        var error: String? = null
        var messageBaseline: Int = 0
        var revision: Long = 0L
        val updates = MutableStateFlow(0L)
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val queue = ArrayDeque<String>()
    private var activeId: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            AssistantConversation.busy.collect { busy ->
                if (!busy) {
                    synchronized(lock) {
                        if (activeId == null) startNextLocked()
                    }
                }
            }
        }
        scope.launch {
            combine(
                UserGateManager.confirmation,
                UserGateManager.takeover
            ) { confirmation, takeover ->
                confirmation != null || takeover != null
            }.collect { waiting ->
                synchronized(lock) {
                    activeId?.let(tasks::get)?.let { task ->
                        bumpLocked(task)
                        OperationTaskRegistry.transition(
                            task.id,
                            if (waiting) {
                                OperationTaskRegistry.State.WAITING_USER
                            } else {
                                OperationTaskRegistry.State.RUNNING
                            },
                            if (waiting) {
                                "assistant task waiting for user"
                            } else {
                                "assistant task running"
                            }
                        )
                    }
                }
            }
        }
    }

    fun start(
        goal: String,
        requestId: String?,
        ownerId: String,
        displayMode: ContainerDisplayMode = ContainerDisplayMode.BACKGROUND
    ): StartResult {
        val operation = OperationTaskRegistry.start(
            ownerId = ownerId,
            kind = "assistant_task",
            title = goal.take(80),
            requestId = requestId,
            requestHash = requestHash(goal)
        )
        val snapshot = operation.task
            ?: return StartResult.Error(
                operation.errorCode ?: "TASK_CREATE_FAILED",
                "Could not create assistant task"
            )
        if (operation.errorCode != null) {
            return StartResult.Error(
                operation.errorCode,
                "request_id already belongs to a different task"
            )
        }
        synchronized(lock) {
            tasks[snapshot.id]?.let {
                return StartResult.Started(view(it))
            }
            if (!operation.created) {
                val restored = taskFromOperation(snapshot)
                tasks[restored.id] = restored
                OperationTaskRegistry.setCancelCallback(restored.id) {
                    cancelLocal(restored.id, "cancelled by client")
                }
                return StartResult.Started(view(restored))
            }
            val task = Task(
                id = snapshot.id,
                ownerId = ownerId,
                goal = goal,
                requestId = requestId,
                displayMode = displayMode,
                createdAt = snapshot.createdAt
            )
            tasks[task.id] = task
            bumpLocked(task)
            OperationTaskRegistry.setCancelCallback(task.id) {
                cancelLocal(task.id, "cancelled by client")
            }
            pruneLocked()

            if (activeId == null && !AssistantConversation.busy.value) {
                startLocked(task)
            } else {
                // Keep the task here until the assistant is actually idle;
                // submitting it to AssistantConversation while a user run is
                // active would make steer/cancel target the wrong run.
                queue.addLast(task.id)
                task.status = Status.QUEUED
                OperationTaskRegistry.transition(
                    task.id,
                    OperationTaskRegistry.State.QUEUED,
                    "assistant task queued"
                )
            }
            return StartResult.Started(view(task))
        }
    }

    fun status(taskId: String, ownerId: String): TaskView? {
        synchronized(lock) {
            tasks[taskId]
                ?.takeIf { it.ownerId == ownerId }
                ?.let { return view(it) }
        }
        val snapshot = OperationTaskRegistry.status(taskId, ownerId)
            ?: return null
        return taskView(snapshot)
    }

    fun list(limit: Int, ownerId: String): List<TaskView> {
        val local = synchronized(lock) {
            tasks.values.filter { it.ownerId == ownerId }.map(::view)
        }
        val all = LinkedHashMap<String, TaskView>()
        local.forEach { all[it.id] = it }
        OperationTaskRegistry.list(ownerId, limit.coerceIn(1, 50))
            .filter { it.kind == "assistant_task" }
            .forEach { all.putIfAbsent(it.id, taskView(it)) }
        return all.values
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 50))
    }

    /**
     * Inject a follow-up instruction into the task currently executing.
     * Returns null when the task is not the active run.
     */
    fun steer(taskId: String, text: String, ownerId: String): TaskView? {
        synchronized(lock) {
            val task = tasks[taskId]
                ?.takeIf { it.ownerId == ownerId }
                ?: return null
            if (task.status != Status.RUNNING || activeId != taskId) return null
            if (!AssistantConversation.steer(text)) return null
            return view(task)
        }
    }

    fun cancel(taskId: String, ownerId: String): TaskView? {
        val operation = OperationTaskRegistry.requestCancel(
            taskId,
            "cancelled by client",
            ownerId
        )
        if (operation.task == null) return null
        synchronized(lock) {
            tasks[taskId]?.takeIf { it.ownerId == ownerId }?.let { task ->
                if (!task.status.terminal && task.status != Status.CANCELLING) {
                    task.status = Status.CANCELLING
                    task.error = "cancelled by client"
                    bumpLocked(task)
                }
                return view(task)
            }
        }
        return taskView(operation.task)
    }

    suspend fun wait(
        taskId: String,
        timeoutMs: Long,
        ownerId: String
    ): WaitResult? {
        val initial = synchronized(lock) {
            tasks[taskId]
                ?.takeIf { it.ownerId == ownerId }
                ?.let(::view)
        } ?: run {
            val snapshot = OperationTaskRegistry.status(taskId, ownerId)
                ?: return null
            taskView(snapshot)
        }
        if (initial.status.terminal) {
            return WaitResult(initial, changed = false, timedOut = false)
        }
        val revision = initial.revision
        val updates = synchronized(lock) {
            tasks[taskId]?.updates
        }
        if (updates == null) {
            val operation = OperationTaskRegistry.wait(
                taskId,
                timeoutMs,
                ownerId
            )
            val task = operation.task?.let(::taskView) ?: return null
            return WaitResult(task, operation.changed, operation.timedOut)
        }
        val observed = withTimeoutOrNull(timeoutMs.coerceIn(1_000, 60_000)) {
            updates.first { it > revision }
        }
        val current = status(taskId, ownerId) ?: return null
        val stateChanged = current.status != initial.status || current.status.terminal
        return WaitResult(
            task = current,
            changed = stateChanged,
            timedOut = observed == null && !stateChanged
        )
    }

    private fun startLocked(task: Task) {
        task.status = Status.RUNNING
        task.startedAt = System.currentTimeMillis()
        task.messageBaseline = AssistantConversation.messages.value.size
        activeId = task.id
        OperationTaskRegistry.transition(
            task.id,
            OperationTaskRegistry.State.RUNNING,
            "assistant task running"
        )
        bumpLocked(task)
        val accepted = AssistantConversation.submit(
            context = appContext,
            command = task.goal,
            queueIfBusy = true,
            origin = com.coke.nestspace.data.AgentMessage.Origin.MCP,
            displayMode = task.displayMode,
            parentTaskId = task.id,
            onFinished = { outcome, answer ->
                finished(task.id, outcome, answer)
            }
        )
        if (!accepted) {
            task.status = Status.FAILED
            task.finishedAt = System.currentTimeMillis()
            task.error = "assistant is busy with another run"
            activeId = null
            OperationTaskRegistry.fail(task.id, task.error!!)
            bumpLocked(task)
            startNextLocked()
        }
    }

    private fun finished(
        taskId: String,
        outcome: AssistantConversation.RunOutcome,
        answer: String?
    ) {
        synchronized(lock) {
            val task = tasks[taskId] ?: return
            if (task.status != Status.CANCELLED) {
                task.status = when (outcome) {
                    AssistantConversation.RunOutcome.COMPLETED -> Status.DONE
                    AssistantConversation.RunOutcome.CANCELLED -> Status.CANCELLED
                    AssistantConversation.RunOutcome.FAILED -> Status.FAILED
                }
            }
            task.answer = answer.orEmpty()
            if (task.status == Status.FAILED) {
                task.error = answer ?: "assistant failed"
            }
            task.finishedAt = System.currentTimeMillis()
            if (activeId == taskId) activeId = null
            when (task.status) {
                Status.DONE -> OperationTaskRegistry.succeed(
                    task.id,
                    task.answer
                )
                Status.FAILED -> OperationTaskRegistry.fail(
                    task.id,
                    task.error ?: task.answer.ifBlank { "assistant failed" }
                )
                Status.CANCELLED, Status.CANCELLING ->
                    OperationTaskRegistry.markCancelled(
                        task.id,
                        task.error ?: "cancelled"
                    )
                else -> Unit
            }
            bumpLocked(task)
            startNextLocked()
        }
    }

    private fun startNextLocked() {
        while (activeId == null && !AssistantConversation.busy.value) {
            val nextId = queue.removeFirstOrNull() ?: return
            val next = tasks[nextId] ?: continue
            if (next.status != Status.QUEUED) continue
            startLocked(next)
            return
        }
    }

    private fun view(task: Task): TaskView {
        val visibleStatus = if (
            task.status == Status.RUNNING &&
            (UserGateManager.confirmation.value != null || UserGateManager.takeover.value != null)
        ) {
            Status.WAITING_FOR_USER
        } else {
            task.status
        }
        val progress = if (task.status == Status.RUNNING && activeId == task.id) {
            AssistantConversation.messages.value
                .drop(task.messageBaseline.coerceAtLeast(0))
                .lastOrNull { it.role == AgentMessage.Role.TOOL || it.role == AgentMessage.Role.AGENT }
                ?.text
                ?.take(400)
                .orEmpty()
        } else {
            ""
        }
        val queueIndex = queue.indexOf(task.id)
        return TaskView(
            id = task.id,
            ownerId = task.ownerId,
            goal = task.goal,
            requestId = task.requestId,
            displayMode = task.displayMode,
            status = visibleStatus,
            createdAt = task.createdAt,
            startedAt = task.startedAt,
            finishedAt = task.finishedAt,
            answer = task.answer,
            error = task.error,
            progress = progress,
            queuePosition = if (queueIndex >= 0) queueIndex + 1 else -1,
            revision = task.revision
        )
    }

    private fun taskFromOperation(
        snapshot: OperationTaskRegistry.Snapshot
    ): Task = Task(
        id = snapshot.id,
        ownerId = snapshot.ownerId,
        goal = snapshot.title,
        requestId = snapshot.requestId,
        displayMode = ContainerDisplayMode.BACKGROUND,
        createdAt = snapshot.createdAt
    ).apply {
        status = snapshot.status()
        startedAt = snapshot.createdAt
        finishedAt = snapshot.finishedAt
        answer = snapshot.result.orEmpty()
        error = snapshot.error
        revision = snapshot.stateRevision
        updates.value = revision
    }

    private fun taskView(snapshot: OperationTaskRegistry.Snapshot): TaskView =
        TaskView(
            id = snapshot.id,
            ownerId = snapshot.ownerId,
            goal = snapshot.title,
            requestId = snapshot.requestId,
            displayMode = ContainerDisplayMode.BACKGROUND,
            status = snapshot.status(),
            createdAt = snapshot.createdAt,
            startedAt = snapshot.createdAt,
            finishedAt = snapshot.finishedAt,
            answer = snapshot.result.orEmpty(),
            error = snapshot.error,
            progress = snapshot.message,
            queuePosition = -1,
            revision = snapshot.stateRevision
        )

    private fun OperationTaskRegistry.Snapshot.status(): Status = when (state) {
        OperationTaskRegistry.State.QUEUED,
        OperationTaskRegistry.State.PREPARING -> Status.QUEUED
        OperationTaskRegistry.State.RUNNING -> Status.RUNNING
        OperationTaskRegistry.State.WAITING_USER -> Status.WAITING_FOR_USER
        OperationTaskRegistry.State.CANCELLING -> Status.CANCELLING
        OperationTaskRegistry.State.SUCCEEDED -> Status.DONE
        OperationTaskRegistry.State.FAILED -> Status.FAILED
        OperationTaskRegistry.State.CANCELLED -> Status.CANCELLED
        OperationTaskRegistry.State.PAUSED,
        OperationTaskRegistry.State.FINALIZING -> Status.RUNNING
    }

    private fun cancelLocal(taskId: String, reason: String) {
        var cancelConversation = false
        var finishQueued = false
        synchronized(lock) {
            val task = tasks[taskId] ?: return
            val queued = queue.remove(taskId)
            if (queued) {
                task.status = Status.CANCELLED
                task.finishedAt = System.currentTimeMillis()
                task.error = reason
                finishQueued = true
            } else if (!task.status.terminal) {
                task.status = Status.CANCELLING
                task.error = reason
                cancelConversation = true
            }
            bumpLocked(task)
        }
        if (finishQueued) {
            OperationTaskRegistry.markCancelled(taskId, reason)
        }
        if (cancelConversation) {
            AssistantConversation.cancel()
        }
    }

    private fun requestHash(goal: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(goal.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun bumpLocked(task: Task) {
        task.revision += 1L
        task.updates.value = task.revision
    }

    private fun pruneLocked() {
        if (tasks.size <= MAX_TASKS) return
        val removable = tasks.values
            .filter { it.status.terminal && it.id != activeId }
            .sortedBy { it.createdAt }
        for (task in removable.take(tasks.size - MAX_TASKS)) {
            tasks.remove(task.id)
            queue.remove(task.id)
        }
    }

    companion object {
        private const val MAX_TASKS = 50

        @Volatile
        private var instance: McpTaskManager? = null

        /** Process-wide queue so an MCP server restart does not lose tasks. */
        fun get(context: Context): McpTaskManager =
            instance ?: synchronized(this) {
                instance ?: McpTaskManager(context.applicationContext).also { instance = it }
            }
    }
}
