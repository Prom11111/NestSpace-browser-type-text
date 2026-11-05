package com.coke.nestspace.mcp

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.coke.nestspace.agent.ChildRun
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.agent.ChildRunStore
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.browser.BrowserProxyManager
import com.coke.nestspace.browser.BrowserProxySettings
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.DesktopAppsStore
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmConfigState
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.data.WallpaperStore
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.i18n.AppLanguageMode
import com.coke.nestspace.llm.LlmProbe
import com.coke.nestspace.location.MockLocationSettings
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.schedule.ScheduledTaskManager
import com.coke.nestspace.service.McpForegroundService
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Settings tools exposed only by the local MCP server, never by the assistant.
 * They let a trusted MCP client configure the app without reinstalling it or
 * writing encrypted preferences by hand.
 *
 * API keys are write-only: `api_key` may be supplied, but it is never returned
 * by any tool and is stored through [LlmConfigStore]'s encrypted store.
 */
internal object McpSettingsTools {

    private const val MCP_PREFS = "virtual"
    private const val MCP_TOKEN = "mcp_token"
    private const val MAX_WALLPAPER_BYTES = 25 * 1024 * 1024

    fun specs(context: Context): List<ToolSpec> {
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

            ToolSpec(
                name = "nestspace_language_set",
                description = "Set the host UI language mode: system, zh or en.",
                inputSchema = objectSchema(
                    "mode" to stringProp("system, zh or en"),
                    required = listOf("mode")
                )
            ) { args, _ ->
                val mode = when (args.optString("mode").trim().lowercase()) {
                    "system", "auto", "follow" -> AppLanguageMode.SYSTEM
                    "zh", "chinese", "中文" -> AppLanguageMode.CHINESE
                    "en", "english" -> AppLanguageMode.ENGLISH
                    else -> return@ToolSpec invalid("mode must be system, zh or en")
                }
                AppLanguage.setMode(app, mode)
                AppSettingsEvents.notifyChanged()
                ToolResult(
                    true,
                    JSONObject()
                        .put("mode", mode.storageValue)
                        .put("effective", if (AppLanguage.isChinese()) "zh" else "en")
                        .toString()
                )
            },

            ToolSpec(
                name = "nestspace_slots_set",
                description = "Set the container slot pool. mode=auto uses the full pool with " +
                    "memory-pressure eviction; mode=fixed requires limit (1-16).",
                inputSchema = objectSchema(
                    "mode" to stringProp("auto or fixed"),
                    "limit" to intProp("fixed slot count, 1-16")
                )
            ) { args, _ ->
                val mode = args.optString("mode").trim().lowercase()
                val limit = if (args.has("limit")) args.optInt("limit") else null
                when (mode) {
                    "auto" -> ContainerProcessPool.applySlotLimit(
                        app,
                        ContainerProcessPool.AUTO_SLOT_LIMIT
                    )

                    "fixed" -> {
                        if (limit == null || limit !in 1..ContainerProcessPool.SLOT_COUNT) {
                            return@ToolSpec invalid(
                                "fixed mode needs limit between 1 and " +
                                    ContainerProcessPool.SLOT_COUNT
                            )
                        }
                        ContainerProcessPool.applySlotLimit(app, limit)
                    }

                    "" -> {
                        if (limit == null) return@ToolSpec invalid("mode or limit is required")
                        ContainerProcessPool.applySlotLimit(
                            app,
                            if (limit == ContainerProcessPool.AUTO_SLOT_LIMIT) limit
                            else limit.coerceIn(1, ContainerProcessPool.SLOT_COUNT)
                        )
                    }

                    else -> return@ToolSpec invalid("mode must be auto or fixed")
                }
                AppSettingsEvents.notifyChanged()
                ToolResult(true, slotsJson(app).toString())
            },

            ToolSpec(
                name = "nestspace_floating_dot_set",
                description = "Configure the floating dot. Colors accept #RRGGBB or " +
                    "#AARRGGBB. speech_language is zh or en. opacity is 10-100, where " +
                    "100 is fully opaque. Omitted fields are unchanged.",
                inputSchema = objectSchema(
                    "enabled" to boolProp("show the floating dot"),
                    "host_color" to stringProp("#RRGGBB over NestSpace"),
                    "container_color" to stringProp("#RRGGBB over virtual apps"),
                    "speech_language" to stringProp("zh or en"),
                    "opacity" to intProp("10-100; 100 is fully opaque"),
                    "x" to intProp("saved screen x"),
                    "y" to intProp("saved screen y")
                )
            ) { args, _ ->
                val current = FloatingDotSettings.load(app)
                val hostColor = if (args.has("host_color")) {
                    parseColor(args.optString("host_color"))
                        ?: return@ToolSpec invalid("host_color must be #RRGGBB or #AARRGGBB")
                } else {
                    current.hostColor
                }
                val containerColor = if (args.has("container_color")) {
                    parseColor(args.optString("container_color"))
                        ?: return@ToolSpec invalid("container_color must be #RRGGBB or #AARRGGBB")
                } else {
                    current.containerColor
                }
                val speech = if (args.has("speech_language")) {
                    args.optString("speech_language").trim().lowercase().also {
                        if (it != FloatingDotSettings.SPEECH_LANG_ZH &&
                            it != FloatingDotSettings.SPEECH_LANG_EN
                        ) {
                            return@ToolSpec invalid("speech_language must be zh or en")
                        }
                    }
                } else {
                    current.speechLanguage
                }
                val opacity = if (args.has("opacity")) {
                    args.optInt("opacity").also {
                        if (it !in FloatingDotSettings.MIN_OPACITY..
                            FloatingDotSettings.MAX_OPACITY
                        ) {
                            return@ToolSpec invalid(
                                "opacity must be between ${FloatingDotSettings.MIN_OPACITY} " +
                                    "and ${FloatingDotSettings.MAX_OPACITY}"
                            )
                        }
                    }
                } else {
                    current.opacity
                }
                FloatingDotSettings.save(
                    app,
                    current.copy(
                        enabled = if (args.has("enabled")) args.optBoolean("enabled")
                        else current.enabled,
                        hostColor = hostColor,
                        containerColor = containerColor,
                        x = if (args.has("x")) args.optInt("x") else current.x,
                        y = if (args.has("y")) args.optInt("y") else current.y,
                        speechLanguage = speech,
                        opacity = opacity
                    )
                )
                AppSettingsEvents.notifyChanged()
                withContext(Dispatchers.Main.immediate) { FloatingDotOverlay.reload(app) }
                ToolResult(true, floatingDotJson(app).toString())
            },

            ToolSpec(
                name = "nestspace_desktop_apps",
                description = "Read or change system-app shortcuts on the desktop. Actions: " +
                    "get, set, add, remove. apps is a JSON array of package names.",
                inputSchema = objectSchema(
                    "action" to stringProp("get, set, add or remove"),
                    "apps" to arrayProp("array of package names")
                )
            ) { args, _ ->
                val action = args.optString("action", "get").trim().lowercase()
                val requested = stringList(args.optJSONArray("apps"))
                val current = DesktopAppsStore.load(app)
                val next = when (action) {
                    "get" -> current
                    "set" -> requested.toSet()
                    "add" -> current + requested
                    "remove" -> current - requested.toSet()
                    else -> return@ToolSpec invalid("action must be get, set, add or remove")
                }
                if (action != "get") {
                    val invalidPackages = next.filter { !isLaunchable(app, it) }
                    if (invalidPackages.isNotEmpty()) {
                        return@ToolSpec ToolResult(
                            false,
                            "not launchable: ${invalidPackages.joinToString()}",
                            errorCode = "INVALID_PACKAGE"
                        )
                    }
                    DesktopAppsStore.save(app, next)
                    AppSettingsEvents.notifyChanged()
                }
                ToolResult(
                    true,
                    JSONObject()
                        .put("apps", JSONArray(next.sorted()))
                        .put("count", next.size)
                        .toString()
                )
            },

            ToolSpec(
                name = "nestspace_wallpaper",
                description = "Get, set or clear the desktop wallpaper. set accepts a base64 " +
                    "image in image_base64 (data:image/...;base64, prefix is allowed).",
                inputSchema = objectSchema(
                    "action" to stringProp("get, set or clear"),
                    "image_base64" to stringProp("base64 image data")
                )
            ) { args, _ ->
                val store = WallpaperStore(app)
                when (args.optString("action", "get").trim().lowercase()) {
                    "get" -> ToolResult(
                        true,
                        JSONObject().put("wallpaper_set", store.exists()).toString()
                    )

                    "clear" -> {
                        store.clear()
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, JSONObject().put("wallpaper_set", false).toString())
                    }

                    "set" -> {
                        val encoded = args.optString("image_base64").trim()
                            .substringAfter("base64,", args.optString("image_base64").trim())
                        val bytes = runCatching {
                            Base64.decode(encoded, Base64.DEFAULT)
                        }.getOrNull()
                        if (bytes == null || bytes.isEmpty()) {
                            return@ToolSpec invalid("image_base64 is not valid base64")
                        }
                        if (bytes.size > MAX_WALLPAPER_BYTES) {
                            return@ToolSpec ToolResult(
                                false,
                                "image is larger than ${MAX_WALLPAPER_BYTES / 1024 / 1024} MB",
                                errorCode = "IMAGE_TOO_LARGE"
                            )
                        }
                        if (!store.saveBytes(bytes)) {
                            return@ToolSpec ToolResult(
                                false,
                                "image could not be decoded",
                                errorCode = "INVALID_IMAGE"
                            )
                        }
                        AppSettingsEvents.notifyChanged()
                        ToolResult(true, JSONObject().put("wallpaper_set", true).toString())
                    }

                    else -> invalid("action must be get, set or clear")
                }
            },

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
            }
        )
    }

    private fun tokenUsageJson(context: Context, limit: Int): JSONObject {
        val snapshot = TokenUsageStore.get(context).snapshot()
        return JSONObject()
            .put("total", tokenTotalsJson(snapshot.total))
            .put("today", tokenTotalsJson(snapshot.today))
            .put("days", JSONObject().apply {
                snapshot.days.forEach { (date, totals) ->
                    put(date, tokenTotalsJson(totals))
                }
            })
            .put("models", JSONArray().apply {
                snapshot.models.forEach { model ->
                    put(
                        JSONObject()
                            .put("id", model.id)
                            .put("name", model.name)
                            .put("model", model.model)
                            .put("totals", tokenTotalsJson(model.totals))
                    )
                }
            })
            .put("recent", JSONArray().apply {
                snapshot.recent.take(limit).forEach { entry ->
                    put(
                        JSONObject()
                            .put("at", entry.at)
                            .put("config", entry.configId)
                            .put("name", entry.name)
                            .put("model", entry.model)
                            .put("kind", entry.kind)
                            .put("input_tokens", entry.inputTokens)
                            .put("output_tokens", entry.outputTokens)
                            .put("reasoning_tokens", entry.reasoningTokens)
                            .put("cached_input_tokens", entry.cachedInputTokens)
                            .put("total_tokens", entry.totalTokens)
                            .put("estimated", entry.estimated)
                    )
                }
            })
    }

    private fun tokenTotalsJson(totals: com.coke.nestspace.agent.TokenUsageTotals): JSONObject =
        JSONObject()
            .put("requests", totals.requests)
            .put("input_tokens", totals.inputTokens)
            .put("output_tokens", totals.outputTokens)
            .put("reasoning_tokens", totals.reasoningTokens)
            .put("cached_input_tokens", totals.cachedInputTokens)
            .put("total_tokens", totals.totalTokens)
            .put("estimated_requests", totals.estimatedRequests)

    private fun childRunJson(item: ChildRun): JSONObject = JSONObject()
        .put("id", item.id)
        .put("parent_id", item.parentId)
        .put("goal", item.goal)
        .put("phase", item.phase.api)
        .put("active", ChildRunRegistry.isActive(item.id))
        .put("result", item.result)
        .put("max_steps", item.maxSteps)
        .put("tool_messages", item.toolMessages)
        .put("had_errors", item.hadErrors)
        .put("started_at", item.startedAt)
        .put("updated_at", item.updatedAt)

    private suspend fun llmConfig(context: Context, args: JSONObject): ToolResult {
        val store = LlmConfigStore(context)
        val state = store.load()
        return when (args.optString("action").trim().lowercase()) {
            "list" -> ToolResult(true, llmJson(state).toString())

            "save" -> {
                val existing = findConfig(state, args.optString("id"))
                    ?: findConfig(state, args.optString("name"))
                val protocolValue = args.optString("protocol")
                val protocol = if (protocolValue.isBlank()) {
                    existing?.protocol ?: LlmProtocol.ANTHROPIC
                } else {
                    parseProtocol(protocolValue) ?: return invalid("protocol must be anthropic or openai")
                }
                val baseUrl = args.optString("base_url").trim().ifBlank {
                    existing?.takeIf { it.protocol == protocol }?.baseUrl
                        ?: defaultBase(protocol)
                }
                val model = args.optString("model").trim().ifBlank {
                    existing?.takeIf { it.protocol == protocol }?.model
                        ?: defaultModel(protocol)
                }
                val apiKey = when {
                    args.optString("api_key").isNotBlank() -> args.optString("api_key").trim()
                    existing?.apiKey?.isNotBlank() == true -> existing.apiKey
                    else -> findConfig(state, args.optString("copy_key_from"))?.apiKey.orEmpty()
                }
                if (apiKey.isBlank()) {
                    return invalid("api_key or copy_key_from is required for a new config")
                }
                val contextTokens = if (args.has("context_tokens")) {
                    args.optInt("context_tokens")
                        .coerceAtLeast(LlmConfig.MIN_CONTEXT_TOKENS)
                } else {
                    existing?.contextTokens ?: LlmConfig.DEFAULT_CONTEXT_TOKENS
                }
                val maxToolRounds = if (args.has("max_tool_rounds")) {
                    args.optInt("max_tool_rounds")
                        .coerceIn(LlmConfig.MIN_TOOL_ROUNDS, LlmConfig.MAX_TOOL_ROUNDS)
                } else {
                    existing?.maxToolRounds ?: LlmConfig.DEFAULT_MAX_TOOL_ROUNDS
                }
                val reasoning = if (args.has("reasoning_effort")) {
                    parseReasoning(args.optString("reasoning_effort"))
                        ?: return invalid("reasoning_effort must be off, low, medium or high")
                } else {
                    existing?.reasoningEffort ?: ReasoningEffort.MEDIUM
                }
                val config = LlmConfig(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = args.optString("name").trim()
                        .ifBlank { existing?.name ?: model },
                    protocol = protocol,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    contextTokens = contextTokens,
                    maxToolRounds = maxToolRounds,
                    maxTokens = if (args.has("max_tokens")) {
                        args.optInt("max_tokens").coerceIn(16, 65_536)
                    } else {
                        existing?.maxTokens ?: 2048
                    },
                    temperature = if (args.has("temperature")) {
                        args.optDouble("temperature").coerceIn(0.0, 2.0)
                    } else {
                        existing?.temperature ?: 0.7
                    },
                    reasoningEffort = reasoning
                )
                val replace = existing?.id ?: config.id
                val next = state.configs.filterNot { it.id == replace } + config
                val select = args.optBoolean("select", existing == null)
                val activeId = if (select || state.activeId.isBlank()) config.id else state.activeId
                store.save(LlmConfigState(next, activeId))
                AppSettingsEvents.notifyChanged()
                ToolResult(
                    true,
                    JSONObject()
                        .put("config", configJson(config))
                        .put("active_id", activeId)
                        .put("created", existing == null)
                        .toString()
                )
            }

            "delete" -> {
                val target = findConfig(state, args.optString("id"))
                    ?: findConfig(state, args.optString("name"))
                    ?: return ToolResult(
                        false,
                        "config not found",
                        errorCode = "LLM_CONFIG_NOT_FOUND"
                    )
                val next = state.configs.filterNot { it.id == target.id }
                val activeId = when {
                    state.activeId != target.id -> state.activeId
                    else -> next.firstOrNull()?.id.orEmpty()
                }
                store.save(LlmConfigState(next, activeId))
                AppSettingsEvents.notifyChanged()
                ToolResult(
                    true,
                    JSONObject()
                        .put("deleted", target.id)
                        .put("active_id", activeId)
                        .toString()
                )
            }

            "select" -> {
                val target = findConfig(state, args.optString("id"))
                    ?: findConfig(state, args.optString("name"))
                    ?: return ToolResult(
                        false,
                        "config not found",
                        errorCode = "LLM_CONFIG_NOT_FOUND"
                    )
                store.save(state.copy(activeId = target.id))
                AppSettingsEvents.notifyChanged()
                ToolResult(true, configJson(target).put("active", true).toString())
            }

            "test" -> {
                val target = findConfig(state, args.optString("id"))
                    ?: findConfig(state, args.optString("name"))
                    ?: state.configs.firstOrNull { it.id == state.activeId }
                    ?: return ToolResult(
                        false,
                        "config not found",
                        errorCode = "LLM_CONFIG_NOT_FOUND"
                    )
                fun redact(text: String): String =
                    if (target.apiKey.isBlank()) text
                    else text.replace(target.apiKey, "<redacted>")
                runCatching { LlmProbe.test(target, context) }.fold(
                    onSuccess = {
                        ToolResult(
                            true,
                            JSONObject()
                                .put("id", target.id)
                                .put("endpoint", target.endpoint())
                                .put("reply", redact(it))
                                .toString()
                        )
                    },
                    onFailure = {
                        ToolResult(
                            false,
                            redact(it.message ?: "connection failed"),
                            errorCode = "LLM_TEST_FAILED"
                        )
                    }
                )
            }

            else -> invalid("action must be list, save, delete, select or test")
        }
    }

    private fun settingsJson(context: Context): JSONObject {
        val llm = LlmConfigStore(context).load()
        val memory = MemoryStore(context).list()
        val skills = SkillStore(context).list()
        val schedules = ScheduledTaskManager(context).list()
        return JSONObject()
            .put("language", languageJson())
            .put("permissions", permissionJson(context))
            .put("slots", slotsJson(context))
            .put("location", locationSimulationJson(context))
            .put("floating_dot", floatingDotJson(context))
            .put("browser_proxy", browserProxyJson(context))
            .put(
                "desktop",
                JSONObject()
                    .put("system_apps", JSONArray(DesktopAppsStore.load(context).sorted()))
                    .put("wallpaper_set", WallpaperStore(context).exists())
            )
            .put("llm", llmJson(llm))
            .put("mcp", mcpJson(context))
            .put("mcp_clients", mcpClientsJson(context))
            .put(
                "memory",
                JSONObject()
                    .put("total", memory.size)
                    .put("approved", memory.count { it.approved })
                    .put("pending", memory.count { !it.approved })
            )
            .put(
                "skills",
                JSONObject()
                    .put("total", skills.size)
                    .put("enabled", skills.count { it.enabled })
            )
            .put(
                "schedules",
                JSONObject()
                    .put("total", schedules.size)
                    .put("enabled", schedules.count { it.enabled })
            )
    }

    private fun llmJson(state: LlmConfigState): JSONObject = JSONObject()
        .put("active_id", state.activeId)
        .put("configs", JSONArray().apply {
            state.configs.forEach { put(configJson(it)) }
        })

    private fun configJson(config: LlmConfig): JSONObject = JSONObject()
        .put("id", config.id)
        .put("name", config.name)
        .put("protocol", config.protocol.name.lowercase())
        .put("base_url", config.baseUrl)
        .put("model", config.model)
        .put("api_key_set", config.apiKey.isNotBlank())
        .put("context_tokens", config.contextTokens)
        .put("max_tool_rounds", config.maxToolRounds)
        .put("max_tokens", config.maxTokens)
        .put("reasoning_reserve_tokens", config.reasoningEffort.reasoningReserveTokens)
        .put("temperature", config.temperature)
        .put("reasoning_effort", config.reasoningEffort.name.lowercase())

    private fun languageJson(): JSONObject = JSONObject()
        .put("mode", AppLanguage.mode().storageValue)
        .put("effective", if (AppLanguage.isChinese()) "zh" else "en")

    private fun permissionJson(context: Context): JSONObject {
        val level = ToolPermissionSettings.load(context)
        val enabled = ToolPermissionSettings.enabledCategories(context)
        val custom = ToolPermissionSettings.customCategories(context)
        return JSONObject()
            .put("level", level.storageValue)
            .put("label", level.label)
            .put("description", level.description)
            .put(
                "enabled",
                JSONArray(
                    ToolConfirmationCategory.entries.filter { it in enabled }.map { it.api }
                )
            )
            .put(
                "custom",
                JSONArray(
                    ToolConfirmationCategory.entries.filter { it in custom }.map { it.api }
                )
            )
            .put("categories", JSONArray().apply {
                ToolConfirmationCategory.entries.forEach { category ->
                    put(
                        JSONObject()
                            .put("id", category.api)
                            .put("label", category.label)
                            .put("description", category.description)
                    )
                }
            })
    }

    private fun slotsJson(context: Context): JSONObject {
        val mode = ContainerProcessPool.slotLimitMode(context)
        val auto = mode == ContainerProcessPool.AUTO_SLOT_LIMIT
        return JSONObject()
            .put("mode", if (auto) "auto" else "fixed")
            .put(
                "limit",
                if (auto) ContainerProcessPool.SLOT_COUNT
                else ContainerProcessPool.slotLimit(context)
            )
            .put("max", ContainerProcessPool.SLOT_COUNT)
    }

    private fun locationSimulationJson(context: Context): JSONObject {
        val config = MockLocationSettings.load(context)
        return JSONObject()
            .put("enabled", config.enabled)
            .put("latitude", config.latitude)
            .put("longitude", config.longitude)
    }

    private fun floatingDotJson(context: Context): JSONObject {
        val config = FloatingDotSettings.load(context)
        return JSONObject()
            .put("enabled", config.enabled)
            .put("host_color", FloatingDotSettings.colorLabel(config.hostColor))
            .put("container_color", FloatingDotSettings.colorLabel(config.containerColor))
            .put("speech_language", config.speechLanguage)
            .put("opacity", config.opacity)
            .put("x", config.x)
            .put("y", config.y)
            .put("overlay_granted", Settings.canDrawOverlays(context))
    }

    private fun mcpClientsJson(context: Context): JSONObject {
        val clients = McpClientStore(context).load()
        return JSONObject()
            .put(
                "clients",
                JSONArray().apply { clients.forEach { put(mcpClientJson(it)) } }
            )
            .put("enabled", clients.count { it.enabled && it.url.isNotBlank() })
            .put("tool_count", clients.filter { it.enabled }.sumOf { it.tools.size })
    }

    private fun mcpClientJson(config: McpClientConfig): JSONObject = JSONObject()
        .put("id", config.id)
        .put("name", config.name)
        .put("url", config.url)
        .put("enabled", config.enabled)
        .put("token_configured", config.token.isNotBlank())
        .put("tool_count", config.tools.size)
        .put("tools", JSONArray(config.tools.map { it.name }))

    private fun mcpJson(context: Context): JSONObject {
        val status = McpForegroundService.status.value
        val lanEnabled = status.lanEnabled || McpForegroundService.isLanEnabled(context)
        val lanPermitted = McpNetwork.hasLocalNetworkPermission(context)
        val advertiseLan = lanEnabled && lanPermitted && status.error == null
        val lanAddress = if (advertiseLan) {
            status.lanAddress.ifBlank { McpNetwork.lanAddress(McpNetwork.lanIpv4()) }
        } else {
            ""
        }
        val localAddress = status.localAddress.ifBlank { McpNetwork.LOCAL_ADDRESS }
        val tokenConfigured = context.getSharedPreferences(MCP_PREFS, Context.MODE_PRIVATE)
            .getString(MCP_TOKEN, null)
            .isNullOrBlank()
            .not()
        return JSONObject()
            .put("running", status.running)
            .put(
                "address",
                if (status.running && advertiseLan) {
                    status.address.ifBlank { localAddress }
                } else if (status.running) {
                    localAddress
                } else {
                    ""
                }
            )
            .put("local_address", localAddress)
            .put("lan_enabled", lanEnabled)
            .put("lan_address", lanAddress)
            .put("port", McpNetwork.PORT)
            .put("token_configured", tokenConfigured)
            .put(
                "local_network_permission",
                McpNetwork.hasLocalNetworkPermission(context)
            )
            .put("error", status.error ?: "")
    }

    private suspend fun applyBrowserProxy(
        context: Context
    ): BrowserProxyManager.Status =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { continuation ->
                BrowserProxyManager.apply(context) { status ->
                    if (continuation.isActive) continuation.resume(status)
                }
            }
        } ?: BrowserProxyManager.status.value

    private fun browserProxyJson(context: Context): JSONObject {
        val config = BrowserProxySettings.load(context)
        val status = BrowserProxyManager.status.value
        return JSONObject()
            .put("enabled", config.enabled)
            .put("type", config.type)
            .put("host", config.host)
            .put("port", config.port)
            .put("bypass", config.bypass)
            .put("supported", status.supported)
            .put("applied", status.applied)
            .put("message", status.message)
    }

    private fun browserProxyError(
        error: BrowserProxySettings.ValidationError
    ): String = when (error) {
        BrowserProxySettings.ValidationError.INVALID_TYPE ->
            "type must be http or socks"
        BrowserProxySettings.ValidationError.HOST_REQUIRED ->
            "host is required when the proxy is enabled"
        BrowserProxySettings.ValidationError.INVALID_HOST ->
            "host must not contain a scheme, port, slash or spaces"
        BrowserProxySettings.ValidationError.INVALID_PORT ->
            "port must be between 1 and 65535"
    }

    private fun memoryJson(
        item: com.coke.nestspace.memory.MemoryItem,
        includeContent: Boolean
    ): JSONObject = JSONObject()
        .put("id", item.id)
        .put("kind", item.kind.api)
        .put("approved", item.approved)
        .put("pinned", item.pinned)
        .put("keywords", JSONArray(item.keywords))
        .put("source", item.source)
        .put("created_at", item.createdAt)
        .put("updated_at", item.updatedAt)
        .apply { if (includeContent) put("content", item.content) }

    private fun skillJson(
        item: com.coke.nestspace.memory.SkillItem,
        includeBody: Boolean
    ): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("description", item.description)
        .put("enabled", item.enabled)
        .put("built_in", item.builtIn)
        .put("source", item.source)
        .put("created_at", item.createdAt)
        .put("updated_at", item.updatedAt)
        .put("use_count", item.useCount)
        .put("success_count", item.successCount)
        .put("failure_count", item.failureCount)
        .put("auto_suspended", item.autoSuspended)
        .put("revision_count", item.revisions.size)
        .apply { if (includeBody) put("body", item.body) }

    private fun findConfig(state: LlmConfigState, key: String): LlmConfig? {
        val value = key.trim()
        if (value.isEmpty()) return null
        return state.configs.firstOrNull {
            it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
        }
    }

    private fun parseProtocol(value: String): LlmProtocol? =
        when (value.trim().lowercase()) {
            "anthropic", "claude", "claude compatible" -> LlmProtocol.ANTHROPIC
            "openai", "openai compatible" -> LlmProtocol.OPENAI
            else -> null
        }

    private fun parseReasoning(value: String): ReasoningEffort? =
        ReasoningEffort.entries.firstOrNull {
            it.name.equals(value.trim(), ignoreCase = true)
        }

    private fun defaultBase(protocol: LlmProtocol): String = when (protocol) {
        LlmProtocol.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC_BASE
        LlmProtocol.OPENAI -> LlmConfig.DEFAULT_OPENAI_BASE
    }

    private fun defaultModel(protocol: LlmProtocol): String = when (protocol) {
        LlmProtocol.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC_MODEL
        LlmProtocol.OPENAI -> LlmConfig.DEFAULT_OPENAI_MODEL
    }

    private fun parseColor(value: String): Int? {
        val raw = value.trim()
        if (!raw.startsWith("#")) return null
        val hex = raw.substring(1)
        return when (hex.length) {
            6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    private fun isLaunchable(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        return context.packageManager.getLaunchIntentForPackage(packageName) != null
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.distinct()
    }

    private fun invalid(message: String): ToolResult =
        ToolResult(false, message, errorCode = "INVALID_ARGUMENT")

    private fun objectSchema(
        vararg props: Pair<String, JSONObject>,
        required: List<String> = emptyList()
    ): JSONObject {
        val properties = JSONObject()
        props.forEach { (name, schema) -> properties.put(name, schema) }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
    }

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String = "integer"): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun numberProp(description: String = "number"): JSONObject =
        JSONObject().put("type", "number").put("description", description)

    private fun boolProp(description: String = "boolean"): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun arrayProp(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string"))
}
