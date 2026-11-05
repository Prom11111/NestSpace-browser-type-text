package com.coke.nestspace.replay

import android.content.Context
import com.coke.nestspace.AppGraph
import com.coke.nestspace.mcp.McpClientManager
import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ReplayRunResult(
    val ok: Boolean,
    val steps: Int,
    val message: String,
    val lastText: String
)

/** Executes a saved tool sequence directly, without invoking a model. */
object ReplayExecutor {

    private val replayLock = Mutex()

    suspend fun run(context: Context, item: ReplayItem): ReplayRunResult =
        withContext(Dispatchers.IO) {
            replayLock.withLock { runLocked(context, item) }
        }

    private suspend fun runLocked(context: Context, item: ReplayItem): ReplayRunResult {
        val registry = AppGraph.registry(context.applicationContext)
        val ctx = ToolContext(
            sessionId = "replay-${item.id}",
            caller = Caller.LOCAL_AGENT,
            spaceId = "virtual-1"
        )
        var completed = 0
        var lastText = ""
        for (step in item.steps) {
            val args = runCatching { JSONObject(step.argsJson) }.getOrNull()
                ?: return ReplayRunResult(
                    false,
                    completed,
                    "invalid arguments at step ${completed + 1}: ${step.name}",
                    lastText
                )
            val result = if (step.name.startsWith("mcp_")) {
                McpClientManager.executeNamed(step.name, args, ctx)
                    ?: return ReplayRunResult(
                        false,
                        completed,
                        "external MCP tool is unavailable: ${step.name}",
                        lastText
                    )
            } else {
                registry.execute(step.name, args, ctx)
            }
            completed++
            lastText = result.text
            if (!result.ok) {
                return ReplayRunResult(
                    false,
                    completed,
                    "step ${completed} ${step.name} failed: " +
                        (result.errorCode ?: result.text.take(160)),
                    lastText
                )
            }
        }
        return ReplayRunResult(
            true,
            completed,
            "replayed $completed step(s)",
            lastText
        )
    }
}
