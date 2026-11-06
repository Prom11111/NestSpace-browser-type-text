package com.coke.nestspace.tools

import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.memory.MemoryKind
import com.coke.nestspace.memory.MemoryPolicy
import com.coke.nestspace.data.AppSettingsEvents
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.memoryToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "memory_search",
            description = "Search approved user memories (facts, preferences, procedures). " +
                "Only approved memories are returned.",
            inputSchema = schema("query" to "string", "limit" to "integer")
        ) { args, _ ->
            val items = memory.search(args.optString("query"), args.optInt("limit", 8))
            val array = JSONArray()
            items.forEach { array.put(memoryJson(it)) }
            ToolResult(true, JSONObject().put("memories", array).toString())
        },
        ToolSpec(
            name = "memory_save",
            description = "Save a durable fact, preference or procedure for the user without " +
                "asking first. The result reports active or pending; in Standard/Loose mode " +
                "procedures are active immediately, while Strict/Custom-risk mode may show a " +
                "confirmation card in the chat. Pass the exact user quote that supports it in " +
                "source_quote. Never save passwords, codes or card data.",
            inputSchema = schema(
                "kind" to "string",
                "content" to "string",
                "keywords" to "string",
                "source_quote" to "string",
                required = listOf("content", "source_quote")
            )
        ) { args, ctx ->
            val content = args.optString("content")
            val source = args.optString("source_quote")
            if (source.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "source_quote is required",
                    errorCode = "SOURCE_REQUIRED"
                )
            }
            if (ctx.caller == Caller.LOCAL_AGENT &&
                ctx.userUtterances.isNotEmpty() &&
                !MemoryPolicy.quoteSupported(source, ctx.userUtterances)
            ) {
                return@ToolSpec ToolResult(
                    false,
                    "source_quote must be an exact quote from the user's message",
                    errorCode = "QUOTE_NOT_FROM_USER"
                )
            }
            val kind = MemoryKind.from(args.optString("kind"))
            val keywords = args.optString("keywords")
                .split(',', '，', ';', '；')
                .map(String::trim)
                .filter(String::isNotEmpty)
            val result = memory.add(
                kind = kind,
                content = content,
                keywords = keywords,
                source = source,
                // Facts and preferences are useful immediately. Procedures only
                // wait for review in Strict/Custom-risk modes.
                approved = kind != MemoryKind.PROCEDURE ||
                    !ToolPermissionSettings.requiresReviewForLearning(context)
            )
            result.fold(
                onSuccess = { item ->
                    AppSettingsEvents.notifyChanged()
                    ToolResult(
                        true,
                        JSONObject()
                            .put("saved", true)
                            .put("pending_review", !item.approved)
                            .put("status", if (item.approved) "active" else "pending")
                            .put("memory", memoryJson(item))
                            .toString()
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        error.message ?: "memory rejected",
                        errorCode = (error as? com.coke.nestspace.memory.MemoryException)?.code
                            ?.uppercase() ?: "MEMORY_ERROR"
                    )
                }
            )
        },
        ToolSpec(
            name = "memory_forget",
            description = "Delete one memory by id. Only do this when the user asks to forget it.",
            inputSchema = schema("id" to "string", required = listOf("id"))
        ) { args, _ ->
            val id = args.optString("id")
            val removed = memory.forget(id)
            if (removed) AppSettingsEvents.notifyChanged()
            ToolResult(
                removed,
                if (removed) "memory forgotten" else "memory not found",
                errorCode = if (removed) null else "MEMORY_NOT_FOUND"
            )
        },
        ToolSpec(
            name = "skill_list",
            description = "List reusable skills. Enabled skills are also included in the " +
                "system prompt index; disabled skills can still be read explicitly.",
            inputSchema = schema()
        ) { _, _ ->
            val array = JSONArray()
            skills.list().forEach { item ->
                array.put(
                    JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("description", item.description)
                        .put("enabled", item.enabled)
                        .put("built_in", item.builtIn)
                )
            }
            ToolResult(true, JSONObject().put("skills", array).toString())
        },
        ToolSpec(
            name = "skill_read",
            description = "Read a reusable skill by name or id before following it.",
            inputSchema = schema("name" to "string", required = listOf("name"))
        ) { args, ctx ->
            val item = skills.get(args.optString("name"))
                ?: return@ToolSpec ToolResult(
                    false,
                    "skill not found",
                    errorCode = "SKILL_NOT_FOUND"
                )
            if (!item.builtIn) ctx.onSkillUsed?.invoke(item.id)
            ToolResult(
                true,
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("description", item.description)
                    .put("enabled", item.enabled)
                    .put("built_in", item.builtIn)
                    .put("body", item.body)
                    .toString()
            )
        },
        ToolSpec(
            name = "skill_save",
            description = "Save a reusable procedure as a skill without asking first. The result " +
                "reports whether it is enabled; in Standard/Loose mode it is enabled immediately, " +
                "while Strict/Custom-risk mode may show an enable card in the chat. Pass the " +
                "user's exact request in source_quote. Use a short lowercase name such as " +
                "'bilibili-search'.",
            inputSchema = schema(
                "name" to "string",
                "description" to "string",
                "body" to "string",
                "source_quote" to "string",
                required = listOf("name", "description", "body", "source_quote")
            )
        ) { args, ctx ->
            val source = args.optString("source_quote")
            if (source.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "source_quote is required",
                    errorCode = "SOURCE_REQUIRED"
                )
            }
            if (ctx.caller == Caller.LOCAL_AGENT &&
                ctx.userUtterances.isNotEmpty() &&
                !MemoryPolicy.quoteSupported(source, ctx.userUtterances)
            ) {
                return@ToolSpec ToolResult(
                    false,
                    "source_quote must be an exact quote from the user's message",
                    errorCode = "QUOTE_NOT_FROM_USER"
                )
            }
            val result = skills.save(
                name = args.optString("name"),
                description = args.optString("description"),
                body = args.optString("body"),
                source = source,
                enabled = !ToolPermissionSettings.requiresReviewForLearning(context)
            )
            result.fold(
                onSuccess = { item ->
                    AppSettingsEvents.notifyChanged()
                    ToolResult(
                        true,
                        JSONObject()
                            .put("saved", true)
                            .put("pending_enable", !item.enabled)
                            .put("skill", JSONObject()
                                .put("id", item.id)
                                .put("name", item.name)
                                .put("enabled", item.enabled))
                            .toString()
                    )
                },
                onFailure = { error ->
                    ToolResult(
                        false,
                        error.message ?: "skill rejected",
                        errorCode = (error as? com.coke.nestspace.memory.SkillException)?.code
                            ?.uppercase() ?: "SKILL_ERROR"
                    )
                }
            )
        },
    )
