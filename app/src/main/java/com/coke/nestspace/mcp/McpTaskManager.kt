package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.data.AgentMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

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
        DONE("done"),
        FAILED("failed"),
        CANCELLED("cancelled");

        val terminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    }

    data class TaskView(
        val id: String,
        val goal: String,
        val requestId: String?,
        val status: Status,
        val createdAt: Long,
        val startedAt: Long,
        val finishedAt: Long,
        val answer: String,
        val error: String?,
        val progress: String,
        val queuePosition: Int
    )

    sealed class StartResult {
        data class Started(val task: TaskView) : StartResult()
        data class Error(val code: String, val message: String) : StartResult()
    }

    private class Task(
        val id: String,
        val goal: String,
        val requestId: String?,
        val createdAt: Long
    ) {
        var status: Status = Status.QUEUED
        var startedAt: Long = 0
        var finishedAt: Long = 0
        var answer: String = ""
        var error: String? = null
        var messageBaseline: Int = 0
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private val tasks = LinkedHashMap<String, Task>()
    private val queue = ArrayDeque<String>()
    private val byRequest = mutableMapOf<String, String>()
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
    }

    fun start(goal: String, requestId: String?): StartResult {
        synchronized(lock) {
            if (requestId != null) {
                val existingId = byRequest[requestId]
                if (existingId != null) {
                    val existing = tasks[existingId]
                    if (existing != null) {
                        return if (existing.goal == goal) {
                            StartResult.Started(view(existing))
                        } else {
                            StartResult.Error(
                                "REQUEST_ID_CONFLICT",
                                "request_id already belongs to a different goal"
                            )
                        }
                    }
                }
            }

            val task = Task(
                id = UUID.randomUUID().toString(),
                goal = goal,
                requestId = requestId,
                createdAt = System.currentTimeMillis()
            )
            tasks[task.id] = task
            if (requestId != null) byRequest[requestId] = task.id
            pruneLocked()

            if (activeId == null && !AssistantConversation.busy.value) {
                startLocked(task)
            } else {
                // Keep the task here until the assistant is actually idle;
                // submitting it to AssistantConversation while a user run is
                // active would make steer/cancel target the wrong run.
                queue.addLast(task.id)
            }
            return StartResult.Started(view(task))
        }
    }

    fun status(taskId: String): TaskView? = synchronized(lock) {
        tasks[taskId]?.let(::view)
    }

    fun list(limit: Int): List<TaskView> = synchronized(lock) {
        tasks.values
            .sortedByDescending { it.createdAt }
            .take(limit.coerceIn(1, 50))
            .map(::view)
    }

    /**
     * Inject a follow-up instruction into the task currently executing.
     * Returns null when the task is not the active run.
     */
    fun steer(taskId: String, text: String): TaskView? {
        synchronized(lock) {
            val task = tasks[taskId] ?: return null
            if (task.status != Status.RUNNING || activeId != taskId) return null
            if (!AssistantConversation.steer(text)) return null
            return view(task)
        }
    }

    fun cancel(taskId: String): TaskView? {
        synchronized(lock) {
            val task = tasks[taskId] ?: return null
            when (task.status) {
                Status.QUEUED -> {
                    queue.remove(taskId)
                    task.status = Status.CANCELLED
                    task.finishedAt = System.currentTimeMillis()
                    task.error = "cancelled before start"
                }
                Status.RUNNING -> {
                    task.status = Status.CANCELLED
                    task.finishedAt = System.currentTimeMillis()
                    task.error = "cancelled by client"
                    AssistantConversation.cancel()
                }
                else -> Unit
            }
            return view(task)
        }
    }

    suspend fun wait(taskId: String, timeoutMs: Long): TaskView? {
        val end = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, 60_000)
        while (System.currentTimeMillis() < end) {
            val task = status(taskId) ?: return null
            if (task.status.terminal) return task
            delay(200)
        }
        return status(taskId)
    }

    private fun startLocked(task: Task) {
        task.status = Status.RUNNING
        task.startedAt = System.currentTimeMillis()
        task.messageBaseline = AssistantConversation.messages.value.size
        activeId = task.id
        val accepted = AssistantConversation.submit(
            appContext,
            task.goal,
            queueIfBusy = true
        ) { outcome, answer ->
            finished(task.id, outcome, answer)
        }
        if (!accepted) {
            task.status = Status.FAILED
            task.finishedAt = System.currentTimeMillis()
            task.error = "assistant is busy with another run"
            activeId = null
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
        return TaskView(
            id = task.id,
            goal = task.goal,
            requestId = task.requestId,
            status = visibleStatus,
            createdAt = task.createdAt,
            startedAt = task.startedAt,
            finishedAt = task.finishedAt,
            answer = task.answer,
            error = task.error,
            progress = progress,
            queuePosition = queue.indexOf(task.id)
        )
    }

    private fun pruneLocked() {
        if (tasks.size <= MAX_TASKS) return
        val removable = tasks.values
            .filter { it.status.terminal && it.id != activeId }
            .sortedBy { it.createdAt }
        for (task in removable.take(tasks.size - MAX_TASKS)) {
            tasks.remove(task.id)
            queue.remove(task.id)
            task.requestId?.let { byRequest.remove(it) }
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
