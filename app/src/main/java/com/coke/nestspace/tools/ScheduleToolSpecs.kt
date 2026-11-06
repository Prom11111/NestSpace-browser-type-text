package com.coke.nestspace.tools

import com.coke.nestspace.schedule.ScheduleRepeat
import com.coke.nestspace.schedule.ScheduleTime
import com.coke.nestspace.data.AppSettingsEvents
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.scheduleToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "schedule_task",
            description = "Create or manage timed assistant tasks. Actions: list, create, pause, " +
                "resume, delete, run_now. For create, pass goal and at (HH:mm local time or epoch ms). " +
                "repeat is once, daily, weekly or interval; interval uses interval_minutes. " +
                "weekdays are comma-separated 1=Mon..7=Sun. Recurring tasks run in the normal " +
                "assistant conversation and post a result notification.",
            inputSchema = schema(
                "action" to "string",
                "id" to "string",
                "goal" to "string",
                "at" to "string",
                "repeat" to "string",
                "weekdays" to "string",
                "interval_minutes" to "integer"
            )
        ) { args, _ ->
            when (args.optString("action", "list").lowercase()) {
                "list" -> {
                    val array = JSONArray()
                    schedules.list().forEach { array.put(scheduleJson(it)) }
                    ToolResult(true, JSONObject().put("tasks", array).toString())
                }
                "create" -> {
                    val goal = args.optString("goal").trim()
                    if (goal.isBlank()) {
                        return@ToolSpec ToolResult(
                            false,
                            "goal is required",
                            errorCode = "INVALID_ARGUMENT"
                        )
                    }
                    val repeat = ScheduleRepeat.from(args.optString("repeat", "once"))
                    val interval = args.optInt("interval_minutes", 0)
                    val parsed = ScheduleTime.parse(
                        at = args.optString("at"),
                        repeat = repeat,
                        weekdaysRaw = args.optString("weekdays"),
                        intervalMinutes = interval
                    ) ?: return@ToolSpec ToolResult(
                        false,
                        "at must be HH:mm or a future epoch time; interval needs interval_minutes",
                        errorCode = "INVALID_ARGUMENT"
                    )
                    schedules.create(
                        goal = goal,
                        repeat = repeat,
                        nextAt = parsed.nextAt,
                        hour = parsed.hour,
                        minute = parsed.minute,
                        weekdays = parsed.weekdays,
                        intervalMinutes = interval.coerceAtLeast(5)
                    ).fold(
                        onSuccess = {
                            AppSettingsEvents.notifyChanged()
                            ToolResult(true, scheduleJson(it).toString())
                        },
                        onFailure = {
                            ToolResult(
                                false,
                                it.message ?: "cannot create schedule",
                                errorCode = "SCHEDULE_ERROR"
                            )
                        }
                    )
                }
                "pause", "resume" -> {
                    val id = args.optString("id")
                    val enabled = args.optString("action").equals("resume", true)
                    val ok = schedules.setEnabled(id, enabled)
                    if (ok) {
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, "schedule updated")
                    } else {
                        ToolResult(false, "schedule not found", errorCode = "SCHEDULE_NOT_FOUND")
                    }
                }
                "delete" -> {
                    val id = args.optString("id")
                    val ok = schedules.delete(id)
                    if (ok) AppSettingsEvents.notifyChanged()
                    ToolResult(
                        ok,
                        if (ok) "schedule deleted" else "schedule not found",
                        errorCode = if (ok) null else "SCHEDULE_NOT_FOUND"
                    )
                }
                "run_now" -> {
                    val id = args.optString("id")
                    val ok = schedules.runNow(id)
                    ToolResult(
                        ok,
                        if (ok) "schedule started" else "schedule not found",
                        errorCode = if (ok) null else "SCHEDULE_NOT_FOUND"
                    )
                }
                else -> ToolResult(
                    false,
                    "unknown action",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
        },
    )
