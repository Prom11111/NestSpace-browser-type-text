package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec

internal fun mcpConfigToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_llm_config",
                description = "Manage LLM endpoints. Actions: list, save, delete, select, " +
                    "test. save keeps the existing API key when api_key is omitted, or copies " +
                    "it from copy_key_from. test performs one real request. API keys are " +
                    "never returned.",
                inputSchema = objectSchema(
                    "action" to stringProp("list, save, delete, select or test"),
                    "id" to stringProp("existing config id"),
                    "name" to stringProp("display name"),
                    "protocol" to stringProp("anthropic or openai"),
                    "base_url" to stringProp("for example https://api.deepseek.com"),
                    "model" to stringProp("model id"),
                    "api_key" to stringProp("write-only API key; omit to keep the old one"),
                    "copy_key_from" to stringProp("config id or name to copy the key from"),
                    "context_tokens" to intProp(
                        "context budget; default 800000, minimum 100000, no upper limit"
                    ),
                    "max_tool_rounds" to intProp(
                        "max tool-calling rounds; default 128, range 1-1000"
                    ),
                    "max_tokens" to intProp(
                        "visible answer token budget, excluding hidden reasoning"
                    ),
                    "temperature" to numberProp(),
                    "reasoning_effort" to stringProp("off, low, medium or high"),
                    "select" to boolProp("make this config active"),
                    required = listOf("action")
                )
            ) { args, _ -> llmConfig(app, args) },

            ToolSpec(
                name = "nestspace_permission_level",
                description = "Read or set the assistant confirmation policy. Actions: get, " +
                    "set. Levels: strict (confirm everything), standard (default: loose " +
                    "plus install/uninstall), loose (only money actions), custom (use confirmations). " +
                    "confirmations is an array of install, permission, download, risky, " +
                    "money, external " +
                    "and only applies to custom.",
                inputSchema = objectSchema(
                    "action" to stringProp("get or set"),
                    "level" to stringProp("strict, standard, loose or custom"),
                    "confirmations" to arrayProp("custom categories to confirm")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                when (action) {
                    "get" -> ToolResult(true, permissionJson(context).toString())

                    "set" -> {
                        val level = ToolPermissionSettings.parse(args.optString("level"))
                            ?: return@ToolSpec invalid(
                                "level must be strict, standard, loose or custom"
                            )
                        val confirmations = args.optJSONArray("confirmations")
                        if (level == ToolPermissionLevel.CUSTOM || confirmations != null) {
                            val requested = if (confirmations == null) {
                                ToolPermissionSettings.customCategories(context)
                            } else {
                                val parsed = mutableSetOf<ToolConfirmationCategory>()
                                for (i in 0 until confirmations.length()) {
                                    val category = ToolPermissionSettings.parseCategory(
                                        confirmations.optString(i)
                                    ) ?: return@ToolSpec invalid(
                                        "unknown confirmation; use install, permission, " +
                                            "download, risky, money or external"
                                    )
                                    parsed += category
                                }
                                parsed
                            }
                            ToolPermissionSettings.saveCustomCategories(context, requested)
                        }
                        ToolPermissionSettings.save(context, level)
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, permissionJson(context).toString())
                    }

                    else -> invalid("action must be get or set")
                }
            },
    )
}
