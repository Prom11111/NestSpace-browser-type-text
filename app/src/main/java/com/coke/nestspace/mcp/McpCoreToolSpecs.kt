package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.browser.BrowserProxySettings
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec

internal fun mcpCoreToolSpecs(context: Context): List<ToolSpec> {
    val app = context.applicationContext
    return listOf(
            ToolSpec(
                name = "nestspace_settings_get",
                description = "Read all NestSpace settings: language, container slots, " +
                    "location simulation, floating dot, browser proxy, external MCP clients, " +
                    "desktop apps, wallpaper state, LLM configs (without API keys), " +
                    "memory/skill counts and MCP status.",
                inputSchema = objectSchema()
            ) { _, _ -> ToolResult(true, settingsJson(app).toString()) },

            ToolSpec(
                name = "nestspace_browser_proxy",
                description = "Read or set the built-in browser proxy. WebView proxy " +
                    "overrides require Android 10+ and affect all browser tabs. Actions: " +
                    "get, set. set accepts enabled, type (http or socks), host, port and " +
                    "bypass (comma-separated rules). Set enabled=false for a direct connection.",
                inputSchema = objectSchema(
                    "action" to stringProp("get or set"),
                    "enabled" to boolProp("enable or disable the proxy"),
                    "type" to stringProp("http or socks"),
                    "host" to stringProp("proxy hostname or IP, without scheme or port"),
                    "port" to intProp("proxy port, 1-65535"),
                    "bypass" to stringProp("comma-separated hosts that skip the proxy")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                when (action) {
                    "get" -> ToolResult(true, browserProxyJson(app).toString())

                    "set" -> {
                        val current = BrowserProxySettings.load(app)
                        val requestedType = if (args.has("type")) {
                            BrowserProxySettings.parseType(args.optString("type"))
                                ?: return@ToolSpec invalid("type must be http or socks")
                        } else {
                            current.type
                        }
                        val config = current.copy(
                            enabled = if (args.has("enabled")) {
                                args.optBoolean("enabled")
                            } else {
                                current.enabled
                            },
                            type = requestedType,
                            host = if (args.has("host")) {
                                args.optString("host").trim()
                            } else {
                                current.host
                            },
                            port = if (args.has("port")) {
                                args.optInt("port", current.port)
                            } else {
                                current.port
                            },
                            bypass = if (args.has("bypass")) {
                                args.optString("bypass").trim()
                            } else {
                                current.bypass
                            }
                        )
                        BrowserProxySettings.validate(config)?.let { error ->
                            return@ToolSpec invalid(browserProxyError(error))
                        }
                        BrowserProxySettings.save(app, config)
                        val status = applyBrowserProxy(app)
                        val result = browserProxyJson(app)
                        if (config.enabled && !status.applied) {
                            ToolResult(
                                false,
                                result.toString(),
                                errorCode = "PROXY_NOT_APPLIED"
                            )
                        } else {
                            ToolResult(true, result.toString())
                        }
                    }

                    else -> invalid("action must be get or set")
                }
            },
    )
}
