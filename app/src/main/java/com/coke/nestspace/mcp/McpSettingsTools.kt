package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.tools.ToolSpec

/**
 * Settings tools exposed only by the local MCP server, never by the assistant.
 * They let a trusted MCP client configure the app without reinstalling it or
 * writing encrypted preferences by hand.
 *
 * API keys are write-only: `api_key` may be supplied, but it is never returned
 * by any tool and is stored through the encrypted LLM config store.
 *
 * The specs themselves are grouped by domain in `Mcp*ToolSpecs.kt`; this
 * object is only the entry point used by [McpServer].
 */
internal object McpSettingsTools {
    fun specs(context: Context): List<ToolSpec> =
        mcpCoreToolSpecs(context) +
            mcpServiceToolSpecs(context) +
            mcpConfigToolSpecs(context) +
            mcpAppearanceToolSpecs(context) +
            mcpKnowledgeToolSpecs(context)
}
