package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges user-configured external MCP servers into the local AI assistant.
 *
 * These tools are deliberately not registered in [com.coke.nestspace.tools.ToolRegistry]:
 * remote MCP clients connected to NestSpace's own control server cannot reach
 * them, and the local agent still has to pass the external-tool confirmation
 * policy before calling one.
 */
object McpClientManager {

    private data class ToolRef(
        val configId: String,
        val configName: String,
        val remoteName: String
    )

    private val refs = ConcurrentHashMap<String, ToolRef>()
    private val sessions = ConcurrentHashMap<String, String>()
    private val initialized = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var specs: List<ToolSpec> = emptyList()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        reload(context)
    }

    fun toolSpecs(): List<ToolSpec> = specs

    fun forgetSession(configId: String) {
        sessions.remove(configId)
        initialized.remove(configId)
    }

    fun summary(context: Context): String {
        val configs = McpClientStore(context).load()
        val enabled = configs.count { it.enabled && it.url.isNotBlank() }
        val tools = configs.filter { it.enabled }.sumOf { it.tools.size }
        return when {
            configs.isEmpty() -> AppLanguage.tr("未配置", "Not configured")
            else -> AppLanguage.tr(
                "$enabled 个服务 · $tools 个工具",
                "$enabled server(s) · $tools tool(s)"
            )
        }
    }

    /**
     * Load the persisted tool cache into [toolSpecs]. Called at app startup and
     * whenever the user changes a client config; it never performs network I/O.
     */
    fun reload(context: Context) {
        appContext = context.applicationContext
        val configs = McpClientStore(context).load()
        val nextRefs = mutableMapOf<String, ToolRef>()
        val nextSpecs = mutableListOf<ToolSpec>()
        configs.filter { it.enabled && it.url.isNotBlank() }.forEach { config ->
            config.tools.forEach { tool ->
                val generated = uniqueName(config, tool.name, nextRefs)
                val ref = ToolRef(config.id, config.name, tool.name)
                nextRefs[generated] = ref
                val schema = runCatching { JSONObject(tool.inputSchema) }
                    .getOrElse {
                        JSONObject().put("type", "object").put("properties", JSONObject())
                    }
                val description = "[MCP: ${config.name}] ${tool.description}".trim()
                nextSpecs += ToolSpec(
                    name = generated,
                    description = description.take(4_000),
                    inputSchema = schema,
                    mutating = false
                ) { args, ctx ->
                    execute(ref.configId, ref.remoteName, args, ctx)
                }
            }
        }
        refs.clear()
        refs.putAll(nextRefs)
        specs = nextSpecs
    }

    /**
     * Connect to one server, list its tools and persist the cache. Returns the
     * updated config; throws [McpRemoteException] or another failure to the UI.
     */
    suspend fun discover(
        context: Context,
        config: McpClientConfig
    ): McpClientConfig = withContext(Dispatchers.IO) {
        val lock = lockFor(config.id)
        val tools = lock.withLock {
            forgetSession(config.id)
            try {
                val init = McpRemoteClient.initialize(config)
                initialized.add(config.id)
                rememberSession(config.id, init.sessionId)
                val list = McpRemoteClient.listTools(config, init.sessionId)
                rememberSession(config.id, list.sessionId)
                McpRemoteClient.parseTools(list)
            } catch (error: McpRemoteException) {
                if (error.status != 404) throw error
                forgetSession(config.id)
                val init = McpRemoteClient.initialize(config)
                initialized.add(config.id)
                rememberSession(config.id, init.sessionId)
                val list = McpRemoteClient.listTools(config, init.sessionId)
                rememberSession(config.id, list.sessionId)
                McpRemoteClient.parseTools(list)
            }
        }
        val store = McpClientStore(context)
        val current = store.load().firstOrNull { it.id == config.id }
            ?: throw McpRemoteException(
                null,
                "CONFIG_REMOVED",
                "MCP server was removed while discovering tools"
            )
        if (current.url != config.url || current.token != config.token) {
            throw McpRemoteException(
                null,
                "CONFIG_CHANGED",
                "MCP server changed while discovering tools; refresh again"
            )
        }
        // Merge into the latest stored config, not the caller's stale
        // snapshot, so a concurrent toggle/rename is not reverted.
        val next = current.copy(tools = tools)
        store.upsert(next)
        reload(context)
        next
    }

    /** Remove a config from the cache and drop its MCP session. */
    fun delete(context: Context, id: String) {
        McpClientStore(context).delete(id)
        sessions.remove(id)
        initialized.remove(id)
        reload(context)
    }

    private fun lockFor(id: String): Mutex =
        locks.computeIfAbsent(id.ifBlank { "default" }) { Mutex() }

    private fun rememberSession(configId: String, sessionId: String?) {
        if (!sessionId.isNullOrBlank()) sessions[configId] = sessionId
    }

    /**
     * Execute a discovered tool by its prefixed name. Returns null when the
     * name no longer belongs to an enabled external server, which lets replay
     * report a useful error instead of a generic missing-tool failure.
     */
    suspend fun executeNamed(
        name: String,
        arguments: JSONObject,
        ctx: ToolContext
    ): ToolResult? {
        val ref = refs[name] ?: return null
        return execute(ref.configId, ref.remoteName, arguments, ctx)
    }

    private suspend fun execute(
        configId: String,
        remoteName: String,
        arguments: JSONObject,
        ctx: ToolContext
    ): ToolResult {
        val context = appContext
            ?: return fail("MCP client is not initialized", "MCP_NOT_INITIALIZED")
        val config = McpClientStore(context).load()
            .firstOrNull { it.id == configId }
            ?: return fail("MCP server was removed; refresh the tool list", "MCP_SERVER_MISSING")
        if (!config.enabled) {
            return fail("MCP server is disabled", "MCP_SERVER_DISABLED")
        }
        if (config.tools.none { it.name == remoteName }) {
            return fail("MCP tool is no longer available; refresh tools", "MCP_TOOL_MISSING")
        }
        if (ctx.caller != Caller.USER &&
            ToolPermissionSettings.shouldConfirm(context, ToolConfirmationCategory.EXTERNAL)
        ) {
            val allowed = UserGateManager.askConfirmation(
                title = AppLanguage.tr("调用外部 MCP 工具？", "Call external MCP tool?"),
                detail = AppLanguage.tr(
                    "${config.name} · $remoteName\n外部工具可能执行真实操作，确认继续吗？",
                    "${config.name} · $remoteName\nExternal tools can perform real " +
                        "actions. Continue?"
                )
            )
            if (!allowed) {
                return fail(
                    AppLanguage.tr("用户拒绝了这次外部 MCP 调用", "The user denied this MCP call"),
                    "USER_DENIED"
                )
            }
        }
        return try {
            val response = callWithSession(config, remoteName, arguments)
            mapCallResult(response)
        } catch (error: McpRemoteException) {
            fail(
                "MCP ${config.name}: ${error.message ?: error.code}",
                "MCP_${error.code}"
            )
        } catch (error: Throwable) {
            fail(
                "MCP ${config.name}: ${error.message ?: error.javaClass.simpleName}",
                "MCP_CLIENT_ERROR"
            )
        }
    }

    private suspend fun callWithSession(
        config: McpClientConfig,
        remoteName: String,
        arguments: JSONObject
    ): McpResponse {
        val lock = lockFor(config.id)
        return lock.withLock {
            if (config.id !in initialized) {
                val init = McpRemoteClient.initialize(config)
                initialized.add(config.id)
                rememberSession(config.id, init.sessionId)
            }
            val session = sessions[config.id]
            try {
                val result = McpRemoteClient.callTool(config, session, remoteName, arguments)
                rememberSession(config.id, result.sessionId)
                result
            } catch (error: McpRemoteException) {
                if (error.status != 404) throw error
                forgetSession(config.id)
                val init = McpRemoteClient.initialize(config)
                initialized.add(config.id)
                rememberSession(config.id, init.sessionId)
                val result = McpRemoteClient.callTool(
                    config,
                    init.sessionId,
                    remoteName,
                    arguments
                )
                rememberSession(config.id, result.sessionId)
                result
            }
        }
    }

    private fun mapCallResult(response: McpResponse): ToolResult {
        val call = McpRemoteClient.parseCall(response)
        val text = StringBuilder()
        var imageData: String? = null
        var imageMime = "image/jpeg"
        val content = call.content
        if (content != null) {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(block.optString("text"))
                    }
                    "image" -> {
                        if (imageData == null) {
                            imageData = block.optString("data")
                            imageMime = block.optString("mimeType", "image/jpeg")
                        } else {
                            text.append("\n[additional image omitted]")
                        }
                    }
                    else -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(block.toString())
                    }
                }
            }
        }
        if (text.isBlank() && call.structured != null) {
            text.append(call.structured)
        }
        if (text.isBlank() && imageData == null) {
            text.append(AppLanguage.tr("MCP 工具没有返回内容", "MCP tool returned no content"))
        }
        val ok = !call.isError
        return ToolResult(
            ok = ok,
            text = text.toString().take(100_000),
            imageBase64 = imageData,
            errorCode = if (ok) null else "MCP_TOOL_ERROR",
            imageMimeType = imageMime
        )
    }

    private fun fail(message: String, code: String): ToolResult =
        ToolResult(false, message, errorCode = code)

    private fun uniqueName(
        config: McpClientConfig,
        toolName: String,
        existing: Map<String, ToolRef>
    ): String {
        val idPart = config.id.take(8).ifBlank { "server" }
        val base = "mcp_${sanitize(idPart)}_${sanitize(toolName)}"
        var candidate = base
        var suffix = 2
        while (existing.containsKey(candidate)) {
            candidate = "${base}_$suffix"
            suffix += 1
        }
        return candidate
    }

    private fun sanitize(value: String): String {
        val clean = value.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return clean.ifBlank { "tool" }.take(80)
    }
}
