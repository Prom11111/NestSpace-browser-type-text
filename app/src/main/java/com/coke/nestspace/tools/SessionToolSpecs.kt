package com.coke.nestspace.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.common.pollUntil
import com.coke.nestspace.task.OperationTaskRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val pollableStatusTools = setOf(
    "appfn_list",
    "appfn_status",
    "assistant_status",
    "browser_download_status",
    "browser_links",
    "browser_read_text",
    "browser_status",
    "browser_tabs",
    "memory_search",
    "plugin_list",
    "replay_list",
    "session_status",
    "skill_list",
    "space_list_apps",
    "space_list_installed",
    "space_running",
    "speech_status",
    "system_status",
    "task_status",
    "ui_observe"
)

internal fun ToolRegistry.sessionToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "session_acquire",
            description = "Acquire the exclusive control lease for a space.",
            inputSchema = schema("space_id" to "string")
        ) { args, ctx ->
            val ok = leases.acquire(args.optString("space_id", ctx.spaceId), ctx.sessionId)
            ToolResult(ok, if (ok) "lease acquired" else "SPACE_BUSY", errorCode = if (ok) null else "SPACE_BUSY")
        },
        ToolSpec(
            name = "session_release",
            description = "Release all leases held by this session.",
            inputSchema = schema()
        ) { _, ctx ->
            leases.release(ctx.sessionId)
            ToolResult(true, "lease released")
        },
        ToolSpec(
            name = "session_status",
            description = "Show the current lease holder for a space.",
            inputSchema = schema("space_id" to "string")
        ) { args, ctx ->
            ToolResult(true, leases.status(args.optString("space_id", ctx.spaceId)).toString())
        },
        ToolSpec(
            name = "task_status",
            description = "Return one asynchronous operation task by task_id. Use this with poll_tool " +
                "and stop_when_contains=\"terminal\":true. The terminal state distinguishes succeeded, " +
                "failed and cancelled. It reports progress, speed, ETA and idle time; it does not decide " +
                "that a slow task should be cancelled.",
            inputSchema = schema(
                "task_id" to "string",
                required = listOf("task_id")
            )
        ) { args, _ ->
            val taskId = args.optString("task_id").trim()
            val task = OperationTaskRegistry.status(taskId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $taskId",
                    errorCode = "TASK_NOT_FOUND"
                )
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "task_cancel",
            description = "Cancel a running asynchronous operation by task_id. This also invokes the " +
                "operation's cancellation hook, such as disconnecting an active download.",
            inputSchema = schema(
                "task_id" to "string",
                required = listOf("task_id")
            ),
            mutating = true
        ) { args, _ ->
            val taskId = args.optString("task_id").trim()
            val existing = OperationTaskRegistry.status(taskId)
                ?: return@ToolSpec ToolResult(
                    false,
                    "task not found: $taskId",
                    errorCode = "TASK_NOT_FOUND"
                )
            val task = OperationTaskRegistry.cancel(taskId)
                ?: existing
            ToolResult(true, taskJson(task).toString())
        },
        ToolSpec(
            name = "poll_tool",
            description = "Poll a safe, read-only status tool several times with a fixed interval. " +
                "Use this for page loads, app startup and other asynchronous work. Pass tool and a JSON " +
                "object in arguments; optionally set stop_when_contains, then the poll stops early when " +
                "the result contains that text. Exhausting max_checks is a timeout, not success. To " +
                "wait for visible app text, poll ui_observe and stop on the text. For an asynchronous " +
                "operation, poll task_status with its task_id and " +
                "stop_when_contains set to the terminal marker. Polling only reports state; use " +
                "task_cancel when the user or model decides a slow operation should stop.",
            inputSchema = schema(
                "tool" to "string",
                "arguments" to "string",
                "max_checks" to "integer",
                "interval_ms" to "integer",
                "stop_when_contains" to "string",
                required = listOf("tool")
            )
        ) { args, ctx ->
            val tool = args.optString("tool").trim()
            if (tool !in pollableStatusTools) {
                return@ToolSpec ToolResult(
                    false,
                    "unsupported polling tool: $tool",
                    errorCode = "POLL_TOOL_NOT_ALLOWED"
                )
            }

            val toolArgs = runCatching {
                JSONObject(args.optString("arguments", "{}").ifBlank { "{}" })
            }.getOrElse {
                return@ToolSpec ToolResult(
                    false,
                    "arguments must be a JSON object",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            val maxChecks = args.optInt("max_checks", 10)
                .coerceIn(1, 120)
            val intervalMs = args.optLong("interval_ms", 2_000L)
                .coerceIn(250L, 30_000L)
            val stopWhen = args.optString("stop_when_contains").trim()
            val startedAt = System.currentTimeMillis()
            val polled = pollUntil(
                maxChecks = maxChecks,
                intervalMs = intervalMs,
                probe = { execute(tool, toolArgs, ctx) },
                isComplete = {
                    stopWhen.isBlank() ||
                        it.text.contains(stopWhen, ignoreCase = true)
                }
            )
            val completed = !polled.timedOut
            val last = polled.value
                ?: ToolResult(false, "no poll result")
            val succeeded = completed && last.ok
            ToolResult(
                succeeded,
                JSONObject()
                    .put("tool", tool)
                    .put("completed", completed)
                    .put("success", succeeded)
                    .put("checks", polled.checks)
                    .put("max_checks", maxChecks)
                    .put("interval_ms", intervalMs)
                    .put(
                        "elapsed_ms",
                        System.currentTimeMillis() - startedAt
                    )
                    .put("stop_when_contains", stopWhen)
                    .put("result", last.text)
                    .put(
                        "next",
                        if (completed) {
                            "Continue with the next task step."
                        } else {
                            "Still not complete. Poll again or inspect another status signal."
                        }
                    )
                    .toString(),
                imageBase64 = last.imageBase64,
                errorCode = if (!completed) "POLL_TIMEOUT" else last.errorCode,
                imageMimeType = last.imageMimeType
            )
        },
        ToolSpec(
            name = "approval_request",
            description = "Request human approval for a sensitive action. Not implemented in the basic version.",
            inputSchema = schema("reason" to "string")
        ) { _, _ ->
            ToolResult(false, "Approval UI is not implemented in the basic version.", errorCode = "NOT_IMPLEMENTED")
        },
        ToolSpec(
            name = "approval_wait",
            description = "Wait for a human approval. Not implemented in the basic version.",
            inputSchema = schema("approval_id" to "string")
        ) { _, _ ->
            ToolResult(false, "Approval UI is not implemented in the basic version.", errorCode = "NOT_IMPLEMENTED")
        },
        ToolSpec(
            name = "assistant_type",
            description = "Type text directly into the AI assistant input box through the UI state, " +
                "without simulating key events. The text appears in the assistant chat input.",
            inputSchema = schema("text" to "string", required = listOf("text"))
        ) { args, _ ->
            val text = args.optString("text")
            val ok = AssistantBridge.type(text)
            ToolResult(
                ok,
                JSONObject()
                    .put("typed", ok)
                    .put("screen_attached", AssistantBridge.isScreenAttached())
                    .put("busy", AssistantBridge.snapshot.value.busy)
                    .toString(),
                errorCode = if (ok) null else if (text.isBlank()) {
                    "INVALID_ARGUMENT"
                } else {
                    "ASSISTANT_BUSY"
                }
            )
        },
        ToolSpec(
            name = "assistant_send",
            description = "Send text to the AI assistant conversation. If text is omitted, sends the text " +
                "previously injected by assistant_type. Waits for the assistant to finish by default and " +
                "returns its new messages/tool trace.",
            inputSchema = schema("text" to "string", "wait_ms" to "integer")
        ) { args, _ ->
            val waitMs = args.optInt("wait_ms", 120_000).coerceIn(0, 180_000)
            val before = AssistantBridge.snapshot.value.messages.size
            if (!AssistantBridge.send(args.optString("text"))) {
                return@ToolSpec ToolResult(
                    false,
                    JSONObject()
                        .put("busy", AssistantBridge.snapshot.value.busy)
                        .put("screen_attached", AssistantBridge.isScreenAttached())
                        .put(
                            "input_present",
                            AssistantBridge.currentInput.value.isNotBlank()
                        )
                        .toString(),
                    errorCode = "ASSISTANT_UNAVAILABLE"
                )
            }
            val snapshot = if (waitMs > 0) {
                AssistantBridge.awaitCompletion(waitMs.toLong(), before)
            } else {
                AssistantBridge.snapshot.value
            }
            ToolResult(
                true,
                assistantSnapshotJson(snapshot, before).put("sent", true).toString()
            )
        },
        ToolSpec(
            name = "assistant_status",
            description = "Return the AI assistant's busy state and recent conversation/tool messages. " +
                "Use wait_ms to wait for a running assistant turn to become idle.",
            inputSchema = schema("wait_ms" to "integer")
        ) { args, _ ->
            val waitMs = args.optInt("wait_ms", 0).coerceIn(0, 180_000)
            val snapshot = if (waitMs > 0) {
                AssistantBridge.awaitIdle(waitMs.toLong())
            } else {
                AssistantBridge.snapshot.value
            }
            ToolResult(true, assistantSnapshotJson(snapshot).toString())
        },
        ToolSpec(
            name = "system_status",
            description = "Check the host Android system: device/Android version, app version, battery, " +
                "network, memory and the current browser mode. Use it when the user asks to inspect " +
                "the system or before reporting a device-level result.",
            inputSchema = schema()
        ) { _, _ ->
            val runtime = Runtime.getRuntime()
            val memory = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(memory)
            val battery = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val network = when {
                capabilities == null -> "offline"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val browserUrl = withContext(Dispatchers.Main) {
                BrowserSession.webView()?.url.orEmpty()
            }
            ToolResult(
                true,
                JSONObject()
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                    .put("app_package", context.packageName)
                    .put("app_version", packageInfo.versionName.orEmpty())
                    .put(
                        "battery_percent",
                        if (level >= 0 && scale > 0) level * 100 / scale else -1
                    )
                    .put("network", network)
                    .put("avail_mem_mb", memory.availMem / (1024 * 1024))
                    .put("total_mem_mb", memory.totalMem / (1024 * 1024))
                    .put("java_used_mb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                    .put("java_max_mb", runtime.maxMemory() / (1024 * 1024))
                    .put("browser_mode", BrowserSession.currentMode())
                    .put("browser_url", browserUrl)
                    .put("browser_tabs", BrowserSession.tabCount())
                    .put(
                        "browser_active_tab",
                        BrowserSession.activeTabId() ?: JSONObject.NULL
                    )
                    .toString()
            )
        },
    )

private fun taskJson(task: OperationTaskRegistry.Snapshot): JSONObject =
    JSONObject()
        .put("task_id", task.id)
        .put("kind", task.kind)
        .put("title", task.title)
        .put("state", task.state.api)
        .put("terminal", task.terminal)
        .put("progress_percent", task.progressPercent ?: JSONObject.NULL)
        .put("progress_current", task.progressCurrent ?: JSONObject.NULL)
        .put("progress_total", task.progressTotal ?: JSONObject.NULL)
        .put("bytes_per_second", task.bytesPerSecond ?: JSONObject.NULL)
        .put("eta_seconds", task.etaSeconds ?: JSONObject.NULL)
        .put(
            "idle_seconds",
            if (task.terminal) 0L else {
                ((System.currentTimeMillis() - task.updatedAt) / 1_000L)
                    .coerceAtLeast(0L)
            }
        )
        .put("message", task.message)
        .put("result", task.result ?: "")
        .put("error", task.error ?: "")
        .put("created_at", task.createdAt)
        .put("updated_at", task.updatedAt)
        .put("finished_at", task.finishedAt)
