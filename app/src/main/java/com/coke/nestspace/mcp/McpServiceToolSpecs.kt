package com.coke.nestspace.mcp

import android.content.Context
import android.provider.Settings
import com.coke.nestspace.service.McpForegroundService
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

internal fun mcpServiceToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_mcp_lan",
                description = "Read or change MCP LAN access. LAN mode binds 0.0.0.0 and " +
                    "requires the bearer token, but traffic is HTTP without TLS. It is off " +
                    "by default; only enable it on a trusted network. Actions: get, set with " +
                    "enabled=true/false. Android 17+ also needs ACCESS_LOCAL_NETWORK permission.",
                inputSchema = objectSchema(
                    "action" to stringProp("get or set"),
                    "enabled" to boolProp("true to listen on the LAN, false for loopback only")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                when (action) {
                    "get" -> ToolResult(true, mcpJson(app).toString())

                    "set" -> {
                        if (!args.has("enabled")) {
                            return@ToolSpec invalid("enabled is required")
                        }
                        val enabled = args.optBoolean("enabled")
                        if (enabled && !McpNetwork.hasLocalNetworkPermission(app)) {
                            return@ToolSpec invalid(
                                "ACCESS_LOCAL_NETWORK permission is not granted; open " +
                                    "Settings > Services & connections and enable LAN access"
                            )
                        }
                        val wasRunning = McpForegroundService.status.value.running
                        McpForegroundService.setLanEnabled(app, enabled)
                        // A running server restarts asynchronously; wait briefly so
                        // the response reports the new bind address.
                        if (wasRunning) delay(800)
                        ToolResult(true, mcpJson(app).toString())
                    }

                    else -> invalid("action must be get or set")
                }
            },

            ToolSpec(
                name = "nestspace_mcp_clients",
                description = "Manage external MCP servers used by the LOCAL assistant. " +
                    "These are separate from NestSpace's own control MCP server. Actions: " +
                    "list, save, delete, discover. Tokens are write-only and never returned. " +
                    "Call discover after save/refresh to fetch tools/list.",
                inputSchema = objectSchema(
                    "action" to stringProp("list, save, delete or discover"),
                    "id" to stringProp("existing client id"),
                    "name" to stringProp("display name"),
                    "url" to stringProp("HTTP MCP endpoint, for example https://host/mcp"),
                    "token" to stringProp("write-only bearer token; omit to keep the old one"),
                    "enabled" to boolProp("enable or disable this client")
                )
            ) { args, _ ->
                val action = args.optString("action", "list").trim().lowercase()
                val store = McpClientStore(app)
                val clients = store.load()
                val existing = clients.firstOrNull { it.id == args.optString("id") }
                    ?: clients.firstOrNull { it.name == args.optString("name") }
                when (action) {
                    "list" -> ToolResult(true, mcpClientsJson(app).toString())

                    "save" -> {
                        val url = args.optString("url").trim()
                            .ifBlank { existing?.url.orEmpty() }
                        val token = if (args.has("token")) {
                            args.optString("token").trim()
                        } else {
                            existing?.token.orEmpty()
                        }
                        val fallbackName = runCatching { java.net.URI(url).host }
                            .getOrNull()
                            .orEmpty()
                        val config = McpClientConfig(
                            id = existing?.id ?: args.optString("id").ifBlank {
                                UUID.randomUUID().toString()
                            },
                            name = args.optString("name").trim()
                                .ifBlank { existing?.name ?: fallbackName },
                            url = url,
                            token = token,
                            enabled = if (args.has("enabled")) {
                                args.optBoolean("enabled")
                            } else {
                                existing?.enabled ?: true
                            },
                            tools = if (existing != null &&
                                existing.url == url &&
                                existing.token == token
                            ) {
                                existing.tools
                            } else {
                                emptyList()
                            }
                        )
                        McpClientStore.validate(config)?.let { return@ToolSpec invalid(it) }
                        store.upsert(config)
                        McpClientManager.forgetSession(config.id)
                        McpClientManager.reload(app)
                        ToolResult(
                            true,
                            JSONObject()
                                .put("client", mcpClientJson(config))
                                .put("hint", "call action=discover to fetch tools")
                                .toString()
                        )
                    }

                    "delete" -> {
                        if (existing == null) {
                            return@ToolSpec invalid("client not found")
                        }
                        McpClientManager.delete(app, existing.id)
                        ToolResult(
                            true,
                            JSONObject().put("deleted", existing.id).toString()
                        )
                    }

                    "discover" -> {
                        if (existing == null) {
                            return@ToolSpec invalid("client not found")
                        }
                        runCatching {
                            McpClientManager.discover(app, existing)
                        }.fold(
                            onSuccess = { updated ->
                                ToolResult(
                                    true,
                                    JSONObject()
                                        .put("client", mcpClientJson(updated))
                                        .toString()
                                )
                            },
                            onFailure = { error ->
                                ToolResult(
                                    false,
                                    "MCP discover failed: " +
                                        (error.message ?: error.javaClass.simpleName),
                                    errorCode = "MCP_DISCOVER_FAILED"
                                )
                            }
                        )
                    }

                    else -> invalid("action must be list, save, delete or discover")
                }
            },
    )
}
