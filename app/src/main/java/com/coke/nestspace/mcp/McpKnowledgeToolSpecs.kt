package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.agent.ChildRunStore
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

internal fun mcpKnowledgeToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_memory_config",
                description = "Review user memories. Actions: list, approve, pin, delete. " +
                    "The assistant only uses approved memories; this is the same review the " +
                    "Memory & skills screen performs.",
                inputSchema = objectSchema(
                    "action" to stringProp("list, approve, pin or delete"),
                    "id" to stringProp("memory id"),
                    "filter" to stringProp("all, approved or pending"),
                    "pinned" to boolProp("pin state for action=pin"),
                    "include_content" to boolProp("include text in list results")
                )
            ) { args, _ ->
                val store = MemoryStore(app)
                when (args.optString("action", "list").trim().lowercase()) {
                    "list" -> {
                        val filter = args.optString("filter", "all").trim().lowercase()
                        val includeContent = args.optBoolean("include_content", true)
                        val items = store.list().filter {
                            when (filter) {
                                "approved" -> it.approved
                                "pending" -> !it.approved
                                else -> true
                            }
                        }
                        ToolResult(
                            true,
                            JSONObject()
                                .put("memories", JSONArray().apply {
                                    items.forEach { put(memoryJson(it, includeContent)) }
                                })
                                .put("count", items.size)
                                .toString()
                        )
                    }

                    "approve" -> {
                        val id = args.optString("id").trim()
                        val ok = id.isNotBlank() && store.approve(id)
                        if (ok) AppSettingsEvents.notifyChanged()
                        ToolResult(
                            ok,
                            if (ok) "memory approved" else "memory not found",
                            errorCode = if (ok) null else "MEMORY_NOT_FOUND"
                        )
                    }

                    "pin" -> {
                        val id = args.optString("id").trim()
                        val ok = id.isNotBlank() &&
                            store.setPinned(id, args.optBoolean("pinned", true))
                        if (ok) AppSettingsEvents.notifyChanged()
                        ToolResult(
                            ok,
                            if (ok) "memory pin updated" else "memory not found",
                            errorCode = if (ok) null else "MEMORY_NOT_FOUND"
                        )
                    }

                    "delete" -> {
                        val id = args.optString("id").trim()
                        val ok = id.isNotBlank() && store.forget(id)
                        if (ok) AppSettingsEvents.notifyChanged()
                        ToolResult(
                            ok,
                            if (ok) "memory deleted" else "memory not found",
                            errorCode = if (ok) null else "MEMORY_NOT_FOUND"
                        )
                    }

                    else -> invalid("action must be list, approve, pin or delete")
                }
            },

            ToolSpec(
                name = "nestspace_skill_config",
                description = "Review reusable skills. Actions: list, enable, disable, delete. " +
                    "In Standard/Loose permission modes new skills are enabled immediately; " +
                    "Strict/Custom-risk modes may leave them disabled for review.",
                inputSchema = objectSchema(
                    "action" to stringProp("list, enable, disable or delete"),
                    "name" to stringProp("skill id or name"),
                    "include_body" to boolProp("include Markdown body in list results")
                )
            ) { args, _ ->
                val store = SkillStore(app)
                when (args.optString("action", "list").trim().lowercase()) {
                    "list" -> {
                        val includeBody = args.optBoolean("include_body", false)
                        val items = store.list()
                        ToolResult(
                            true,
                            JSONObject()
                                .put("skills", JSONArray().apply {
                                    items.forEach { put(skillJson(it, includeBody)) }
                                })
                                .put("count", items.size)
                                .put("enabled", items.count { it.enabled })
                                .toString()
                        )
                    }

                    "enable", "disable" -> {
                        val item = store.get(args.optString("name"))
                            ?: return@ToolSpec ToolResult(
                                false,
                                "skill not found",
                                errorCode = "SKILL_NOT_FOUND"
                            )
                        val enabled = args.optString("action").equals("enable", true)
                        val ok = store.setEnabled(item.id, enabled)
                        if (ok) AppSettingsEvents.notifyChanged()
                        ToolResult(
                            ok,
                            JSONObject()
                                .put("id", item.id)
                                .put("name", item.name)
                                .put("enabled", enabled)
                                .toString(),
                            errorCode = if (ok) null else "SKILL_NOT_FOUND"
                        )
                    }

                    "delete" -> {
                        val item = store.get(args.optString("name"))
                            ?: return@ToolSpec ToolResult(
                                false,
                                "skill not found",
                                errorCode = "SKILL_NOT_FOUND"
                            )
                        if (item.builtIn) {
                            return@ToolSpec ToolResult(
                                false,
                                "built-in skills cannot be deleted; disable it instead",
                                errorCode = "BUILTIN_READ_ONLY"
                            )
                        }
                        val ok = store.delete(item.id)
                        if (ok) AppSettingsEvents.notifyChanged()
                        ToolResult(
                            ok,
                            if (ok) "skill deleted" else "skill not found",
                            errorCode = if (ok) null else "SKILL_NOT_FOUND"
                        )
                    }

                    else -> invalid("action must be list, enable, disable or delete")
                }
            },

            ToolSpec(
                name = "nestspace_child_runs",
                description = "List, inspect or cancel child-agent runs. Actions: list, get, " +
                    "cancel, cancel_all, active. Child results are stored as encrypted previews.",
                inputSchema = objectSchema(
                    "action" to stringProp("list, get, cancel, cancel_all or active"),
                    "id" to stringProp("child run id"),
                    "parent_id" to stringProp("parent session id"),
                    "limit" to intProp("max records for list, 1-40")
                )
            ) { args, _ ->
                when (args.optString("action", "list").trim().lowercase()) {
                    "list" -> {
                        val parent = args.optString("parent_id").trim()
                            .takeIf { it.isNotBlank() }
                        val limit = args.optInt("limit", 20).coerceIn(1, 40)
                        val items = ChildRunStore.list(app, parent, limit)
                        ToolResult(
                            true,
                            JSONObject()
                                .put("children", JSONArray().apply {
                                    items.forEach { put(childRunJson(it)) }
                                })
                                .put("count", items.size)
                                .toString()
                        )
                    }

                    "get" -> {
                        val id = args.optString("id").trim()
                        val item = ChildRunStore.get(app, id)
                            ?: return@ToolSpec ToolResult(
                                false,
                                "child run not found",
                                errorCode = "CHILD_NOT_FOUND"
                            )
                        ToolResult(true, childRunJson(item).toString())
                    }

                    "cancel" -> {
                        val id = args.optString("id").trim()
                        val cancelled = ChildRunRegistry.cancel(id)
                        ToolResult(
                            cancelled,
                            JSONObject()
                                .put("id", id)
                                .put("cancelled", cancelled)
                                .put("active", ChildRunRegistry.isActive(id))
                                .toString(),
                            errorCode = if (cancelled) null else "CHILD_NOT_ACTIVE"
                        )
                    }

                    "cancel_all" -> {
                        val parent = args.optString("parent_id").trim()
                        if (parent.isBlank()) {
                            return@ToolSpec invalid("parent_id is required")
                        }
                        val count = ChildRunRegistry.cancelAll(parent)
                        ToolResult(
                            true,
                            JSONObject()
                                .put("parent_id", parent)
                                .put("cancelled", count)
                                .toString()
                        )
                    }

                    "active" -> ToolResult(
                        true,
                        JSONObject()
                            .put("ids", JSONArray(ChildRunRegistry.activeIds()))
                            .put("count", ChildRunRegistry.activeIds().size)
                            .toString()
                    )

                    else -> invalid("action must be list, get, cancel, cancel_all or active")
                }
            },

            ToolSpec(
                name = "nestspace_token_usage",
                description = "Read or reset model token statistics. Every completed model " +
                    "request is counted, including retries, summaries and connectivity tests. " +
                    "summary includes today, all-time, per-day calendar, per-model and recent " +
                    "requests. Actions: summary, reset.",
                inputSchema = objectSchema(
                    "action" to stringProp("summary or reset"),
                    "limit" to intProp("recent request count, 1-100")
                )
            ) { args, _ ->
                when (args.optString("action", "summary").trim().lowercase()) {
                    "summary" -> ToolResult(
                        true,
                        tokenUsageJson(context, args.optInt("limit", 20).coerceIn(1, 100))
                            .toString()
                    )

                    "reset" -> {
                        TokenUsageStore.get(context).reset()
                        ToolResult(
                            true,
                            tokenUsageJson(context, args.optInt("limit", 20).coerceIn(1, 100))
                                .put("reset", true)
                                .toString()
                        )
                    }

                    else -> invalid("action must be summary or reset")
                }
            },
    )
}
