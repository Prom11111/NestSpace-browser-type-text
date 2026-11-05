package com.coke.nestspace.mcp

import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import com.coke.nestspace.tools.toolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP-facing async task tools. They are not part of the assistant's own tool
 * list: a remote client starts a task, polls/waits for its state, and can
 * cancel it without holding a synchronous tools/call open.
 */
object McpTaskTools {

    fun specs(manager: McpTaskManager): List<ToolSpec> = listOf(
        ToolSpec(
            name = "nestspace_run_task",
            description = "Start an assistant task asynchronously and return a task_id " +
                "immediately. Use nestspace_task_status or nestspace_wait_task to follow it. " +
                "Statuses: queued, running, waiting_for_user, done, failed, cancelled. " +
                "Pass a request_id to make retries idempotent. Tasks live while the app " +
                "process lives; after a restart retry with a request_id.",
            inputSchema = toolSchema(
                "goal" to "string",
                "request_id" to "string",
                required = listOf("goal")
            )
        ) { args, _ ->
            val goal = args.optString("goal").trim()
            if (goal.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "goal is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            val requestId = args.optString("request_id")
                .trim()
                .takeIf { it.isNotBlank() }
            when (val started = manager.start(goal, requestId)) {
                is McpTaskManager.StartResult.Started ->
                    ToolResult(true, taskJson(started.task).toString())
                is McpTaskManager.StartResult.Error ->
                    ToolResult(false, started.message, errorCode = started.code)
            }
        },
        ToolSpec(
            name = "nestspace_steer_task",
            description = "Add an instruction to the currently running task. The task keeps " +
                "its id and context; the text is injected before the next model step. Returns " +
                "TASK_NOT_RUNNING when the task is queued or finished.",
            inputSchema = toolSchema(
                "task_id" to "string",
                "text" to "string",
                required = listOf("task_id", "text")
            )
        ) { args, _ ->
            val id = args.optString("task_id").trim()
            val text = args.optString("text").trim()
            if (text.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "text is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            val task = manager.steer(id, text)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task is not running: $id",
                    errorCode = "TASK_NOT_RUNNING"
                )
            ToolResult(
                true,
                taskJson(task)
                    .put("steered", true)
                    .put(
                        "queued_steering",
                        com.coke.nestspace.agent.AssistantConversation.queuedSteering.value
                    )
                    .toString()
            )
        },
        ToolSpec(
            name = "nestspace_task_status",
            description = "Return one asynchronous assistant task's state, progress and answer.",
            inputSchema = toolSchema("task_id" to "string", required = listOf("task_id"))
        ) { args, _ ->
            val id = args.optString("task_id").trim()
            val task = manager.status(id)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "nestspace_wait_task",
            description = "Wait until a task changes to a terminal state or the timeout expires. " +
                "Returns the current task state either way.",
            inputSchema = toolSchema(
                "task_id" to "string",
                "timeout_s" to "integer",
                required = listOf("task_id")
            )
        ) { args, _ ->
            val id = args.optString("task_id").trim()
            val timeout = args.optInt("timeout_s", 30).coerceIn(1, 50)
            val task = manager.wait(id, timeout * 1_000L)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(
                true,
                taskJson(task).put("timed_out", !task.status.terminal).toString()
            )
        },
        ToolSpec(
            name = "nestspace_cancel_task",
            description = "Cancel a queued or running assistant task.",
            inputSchema = toolSchema("task_id" to "string", required = listOf("task_id"))
        ) { args, _ ->
            val id = args.optString("task_id").trim()
            val task = manager.cancel(id)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $id",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "nestspace_list_tasks",
            description = "List recent asynchronous assistant tasks, newest first.",
            inputSchema = toolSchema("limit" to "integer")
        ) { args, _ ->
            val limit = args.optInt("limit", 10).coerceIn(1, 50)
            val array = JSONArray()
            manager.list(limit).forEach { array.put(taskJson(it)) }
            ToolResult(true, JSONObject().put("tasks", array).toString())
        }
    )

    private fun taskJson(task: McpTaskManager.TaskView): JSONObject = JSONObject()
        .put("task_id", task.id)
        .put("status", task.status.api)
        .put("goal", task.goal)
        .put("created_at", task.createdAt)
        .put("started_at", task.startedAt)
        .put("finished_at", task.finishedAt)
        .put("answer", task.answer)
        .put("error", task.error ?: "")
        .put("progress", task.progress)
        .put("queue_position", task.queuePosition)
        .put(
            "request_id",
            task.requestId ?: JSONObject.NULL
        )
}
