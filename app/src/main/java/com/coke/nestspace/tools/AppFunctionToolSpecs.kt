package com.coke.nestspace.tools

import com.coke.nestspace.fastpath.AppFunctionsBridge
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.appfnToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "appfn_status",
            description = "Report AppFunctions fast-path availability (Android 16+, host/container/system).",
            inputSchema = schema()
        ) { _, _ ->
            val status = AppFunctionsBridge.status(context)
            ToolResult(
                true,
                JSONObject()
                    .put("platform_available", status.platformAvailable)
                    .put("manager_available", status.managerAvailable)
                    .put("can_execute_other_apps", status.canExecuteOtherApps)
                    .put("note", status.note)
                    .toString()
            )
        },
        ToolSpec(
            name = "appfn_list",
            description = "List AppFunctions exposed by a package. The basic version reports availability; discovery is Stage F.",
            inputSchema = schema("package" to "string")
        ) { args, _ ->
            val status = AppFunctionsBridge.status(context)
            ToolResult(
                true,
                JSONObject()
                    .put("package", args.optString("package"))
                    .put("platform_available", status.platformAvailable)
                    .put("manager_available", status.managerAvailable)
                    .put("can_execute_other_apps", status.canExecuteOtherApps)
                    .put("functions", JSONArray())
                    .put(
                        "note",
                        "Discovery is Stage F. Supported paths: host AppFunctions export, " +
                            "and container-local AppFunctions bridge."
                    )
                    .toString()
            )
        },
        ToolSpec(
            name = "appfn_execute",
            description = "Execute an AppFunction as a fast path. The basic version returns the permission/availability boundary.",
            inputSchema = schema(
                "package" to "string",
                "function_id" to "string",
                "arguments" to "object"
            ),
            mutating = true
        ) { args, _ ->
            val status = AppFunctionsBridge.status(context)
            ToolResult(
                false,
                JSONObject()
                    .put("package", args.optString("package"))
                    .put("function_id", args.optString("function_id"))
                    .put("platform_available", status.platformAvailable)
                    .put("can_execute_other_apps", status.canExecuteOtherApps)
                    .put("note", "AppFunctions execution is Stage F. " + status.note)
                    .toString(),
                errorCode = "APPFUNCTIONS_NOT_IMPLEMENTED"
            )
        },
    )
