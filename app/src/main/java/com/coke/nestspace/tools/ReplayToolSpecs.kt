package com.coke.nestspace.tools

import com.coke.nestspace.replay.ReplayExecutor
import com.coke.nestspace.replay.ReplayManager
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.replayToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "replay_save",
            description = "Save the tool sequence of the most recent successful assistant run " +
                "as a replay. Replays can be executed later without calling the model.",
            inputSchema = schema(
                "name" to "string",
                "description" to "string",
                required = listOf("name")
            )
        ) { args, _ ->
            val trace = ReplayManager.last()
                ?: return@ToolSpec ToolResult(
                    false,
                    "no recent successful run to save",
                    errorCode = "NO_RECENT_TRACE"
                )
            replays.save(
                name = args.optString("name"),
                description = args.optString("description"),
                goal = trace.goal,
                steps = trace.steps
            ).fold(
                onSuccess = { ToolResult(true, replayJson(it, includeSteps = false).toString()) },
                onFailure = {
                    ToolResult(
                        false,
                        it.message ?: "cannot save replay",
                        errorCode = "REPLAY_ERROR"
                    )
                }
            )
        },
        ToolSpec(
            name = "replay_list",
            description = "List saved deterministic tool replays.",
            inputSchema = schema()
        ) { _, _ ->
            val array = JSONArray()
            replays.list().forEach { array.put(replayJson(it, includeSteps = false)) }
            ToolResult(true, JSONObject().put("replays", array).toString())
        },
        ToolSpec(
            name = "replay_run",
            description = "Execute a saved replay's tool steps directly, without the model. " +
                "Stops at the first failed step.",
            inputSchema = schema("name" to "string", required = listOf("name"))
        ) { args, _ ->
            val item = replays.get(args.optString("name"))
                ?: return@ToolSpec ToolResult(
                    false,
                    "replay not found",
                    errorCode = "REPLAY_NOT_FOUND"
                )
            val result = ReplayExecutor.run(context, item)
            ToolResult(
                result.ok,
                JSONObject()
                    .put("replay", item.name)
                    .put("ok", result.ok)
                    .put("steps", result.steps)
                    .put("message", result.message)
                    .put("last_text", result.lastText.take(2_000))
                    .toString(),
                errorCode = if (result.ok) null else "REPLAY_FAILED"
            )
        },
        ToolSpec(
            name = "replay_delete",
            description = "Delete a saved replay by name or id.",
            inputSchema = schema("name" to "string", required = listOf("name"))
        ) { args, _ ->
            val removed = replays.delete(args.optString("name"))
            ToolResult(
                removed,
                if (removed) "replay deleted" else "replay not found",
                errorCode = if (removed) null else "REPLAY_NOT_FOUND"
            )
        },
    )
