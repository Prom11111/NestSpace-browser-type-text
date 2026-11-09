package com.coke.nestspace.mcp

import android.content.Context
import android.provider.Settings
import com.coke.nestspace.agent.ChildRun
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
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
import com.coke.nestspace.llm.LlmProbe
import com.coke.nestspace.location.MockLocationSettings
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.schedule.ScheduledTaskManager
import com.coke.nestspace.service.McpForegroundService
import com.coke.nestspace.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

internal const val MCP_PREFS = "virtual"
internal const val MCP_TOKEN = "mcp_token"
internal const val MAX_WALLPAPER_BYTES = 25 * 1024 * 1024

internal fun tokenUsageJson(context: Context, limit: Int): JSONObject {
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

internal fun tokenTotalsJson(totals: com.coke.nestspace.agent.TokenUsageTotals): JSONObject =
    JSONObject()
        .put("requests", totals.requests)
        .put("input_tokens", totals.inputTokens)
        .put("output_tokens", totals.outputTokens)
        .put("reasoning_tokens", totals.reasoningTokens)
        .put("cached_input_tokens", totals.cachedInputTokens)
        .put("total_tokens", totals.totalTokens)
        .put("estimated_requests", totals.estimatedRequests)

internal fun childRunJson(item: ChildRun): JSONObject = JSONObject()
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

internal suspend fun llmConfig(context: Context, args: JSONObject): ToolResult {
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
            ).withNormalizedEndpoint()
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
            val target = (findConfig(state, args.optString("id"))
                ?: findConfig(state, args.optString("name"))
                ?: state.configs.firstOrNull { it.id == state.activeId })
                ?.withNormalizedEndpoint()
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

internal fun settingsJson(context: Context): JSONObject {
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

internal fun llmJson(state: LlmConfigState): JSONObject = JSONObject()
    .put("active_id", state.activeId)
    .put("configs", JSONArray().apply {
        state.configs.forEach { put(configJson(it)) }
    })

internal fun configJson(config: LlmConfig): JSONObject = JSONObject()
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

internal fun languageJson(): JSONObject = JSONObject()
    .put("mode", AppLanguage.mode().storageValue)
    .put("effective", if (AppLanguage.isChinese()) "zh" else "en")

internal fun permissionJson(context: Context): JSONObject {
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

internal fun slotsJson(context: Context): JSONObject {
    val mode = ContainerProcessPool.slotLimitMode(context)
    val auto = mode == ContainerProcessPool.AUTO_SLOT_LIMIT
    return JSONObject()
        .put("mode", if (auto) "auto" else "fixed")
        .put("limit", ContainerProcessPool.slotLimit(context))
        .put("max", ContainerProcessPool.SLOT_COUNT)
}

internal fun locationSimulationJson(context: Context): JSONObject {
    val config = MockLocationSettings.load(context)
    return JSONObject()
        .put("enabled", config.enabled)
        .put("latitude", config.latitude)
        .put("longitude", config.longitude)
}

internal fun floatingDotJson(context: Context): JSONObject {
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

internal fun mcpClientsJson(context: Context): JSONObject {
    val clients = McpClientStore(context).load()
    return JSONObject()
        .put(
            "clients",
            JSONArray().apply { clients.forEach { put(mcpClientJson(it)) } }
        )
        .put("enabled", clients.count { it.enabled && it.url.isNotBlank() })
        .put("tool_count", clients.filter { it.enabled }.sumOf { it.tools.size })
}

internal fun mcpClientJson(config: McpClientConfig): JSONObject = JSONObject()
    .put("id", config.id)
    .put("name", config.name)
    .put("url", config.url)
    .put("enabled", config.enabled)
    .put("token_configured", config.token.isNotBlank())
    .put("tool_count", config.tools.size)
    .put("tools", JSONArray(config.tools.map { it.name }))

internal fun mcpJson(context: Context): JSONObject {
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

internal suspend fun applyBrowserProxy(
    context: Context
): BrowserProxyManager.Status =
    withTimeoutOrNull(3_000) {
        suspendCancellableCoroutine { continuation ->
            BrowserProxyManager.apply(context) { status ->
                if (continuation.isActive) continuation.resume(status)
            }
        }
    } ?: BrowserProxyManager.status.value

internal fun browserProxyJson(context: Context): JSONObject {
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

internal fun browserProxyError(
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

internal fun memoryJson(
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

internal fun skillJson(
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

internal fun findConfig(state: LlmConfigState, key: String): LlmConfig? {
    val value = key.trim()
    if (value.isEmpty()) return null
    return state.configs.firstOrNull {
        it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
    }
}

internal fun parseProtocol(value: String): LlmProtocol? =
    when (value.trim().lowercase()) {
        "anthropic", "claude", "claude compatible" -> LlmProtocol.ANTHROPIC
        "openai", "openai compatible" -> LlmProtocol.OPENAI
        else -> null
    }

internal fun parseReasoning(value: String): ReasoningEffort? =
    ReasoningEffort.entries.firstOrNull {
        it.name.equals(value.trim(), ignoreCase = true)
    }

internal fun defaultBase(protocol: LlmProtocol): String = when (protocol) {
    LlmProtocol.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC_BASE
    LlmProtocol.OPENAI -> LlmConfig.DEFAULT_OPENAI_BASE
}

internal fun defaultModel(protocol: LlmProtocol): String = when (protocol) {
    LlmProtocol.ANTHROPIC -> LlmConfig.DEFAULT_ANTHROPIC_MODEL
    LlmProtocol.OPENAI -> LlmConfig.DEFAULT_OPENAI_MODEL
}

internal fun parseColor(value: String): Int? {
    val raw = value.trim()
    if (!raw.startsWith("#")) return null
    val hex = raw.substring(1)
    return when (hex.length) {
        6 -> hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> hex.toLongOrNull(16)?.toInt()
        else -> null
    }
}

internal fun isLaunchable(context: Context, packageName: String): Boolean {
    if (packageName.isBlank() || packageName == context.packageName) return false
    return context.packageManager.getLaunchIntentForPackage(packageName) != null
}

internal fun stringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }.distinct()
}

internal fun invalid(message: String): ToolResult =
    ToolResult(false, message, errorCode = "INVALID_ARGUMENT")

internal fun objectSchema(
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

internal fun stringProp(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

internal fun intProp(description: String = "integer"): JSONObject =
    JSONObject().put("type", "integer").put("description", description)

internal fun numberProp(description: String = "number"): JSONObject =
    JSONObject().put("type", "number").put("description", description)

internal fun boolProp(description: String = "boolean"): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

internal fun arrayProp(description: String): JSONObject = JSONObject()
    .put("type", "array")
    .put("description", description)
    .put("items", JSONObject().put("type", "string"))
