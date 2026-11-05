package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.data.ApiKeyCipher
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** One tool discovered from an external MCP server. */
data class McpRemoteTool(
    val name: String,
    val description: String,
    val inputSchema: String
)

/** A user-configured external MCP server used by the local AI assistant. */
data class McpClientConfig(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val enabled: Boolean = true,
    val tools: List<McpRemoteTool> = emptyList()
) {
    val hasTools: Boolean get() = tools.isNotEmpty()
}

class McpClientStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<McpClientConfig> = synchronized(LOCK) {
        val raw = prefs.getString(KEY_CLIENTS, null) ?: return emptyList()
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val tools = json.optJSONArray("tools")?.let { toolArray ->
                    (0 until toolArray.length()).mapNotNull { toolIndex ->
                        val tool = toolArray.optJSONObject(toolIndex)
                            ?: return@mapNotNull null
                        val name = tool.optString("name").trim()
                        if (name.isBlank()) return@mapNotNull null
                        McpRemoteTool(
                            name = name,
                            description = tool.optString("description"),
                            inputSchema = tool.optString("input_schema", "{}")
                        )
                    }
                }.orEmpty()
                McpClientConfig(
                    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = json.optString("name"),
                    url = json.optString("url"),
                    token = ApiKeyCipher.decrypt(json.optString("token")),
                    enabled = json.optBoolean("enabled", true),
                    tools = tools
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(configs: List<McpClientConfig>) = synchronized(LOCK) {
        val array = JSONArray()
        configs.forEach { config ->
            val tools = JSONArray()
            config.tools.forEach { tool ->
                tools.put(
                    JSONObject()
                        .put("name", tool.name)
                        .put("description", tool.description)
                        .put("input_schema", tool.inputSchema)
                )
            }
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("url", config.url)
                    .put("token", ApiKeyCipher.encrypt(config.token))
                    .put("enabled", config.enabled)
                    .put("tools", tools)
            )
        }
        prefs.edit().putString(KEY_CLIENTS, array.toString()).apply()
    }

    fun upsert(config: McpClientConfig): List<McpClientConfig> = synchronized(LOCK) {
        val next = load().filterNot { it.id == config.id } + config
        save(next)
        next
    }

    fun delete(id: String): List<McpClientConfig> = synchronized(LOCK) {
        val next = load().filterNot { it.id == id }
        save(next)
        next
    }

    companion object {
        // Shared across store instances so concurrent saves cannot drop each
        // other's config changes.
        private val LOCK = Any()
        private const val PREFS = "mcp_clients"
        private const val KEY_CLIENTS = "clients"

        fun validate(config: McpClientConfig): String? {
            if (config.name.isBlank()) return "name is required"
            val url = config.url.trim()
            if (url.isBlank()) return "url is required"
            val uri = runCatching { URI(url) }.getOrNull()
                ?: return "invalid url"
            if (uri.scheme != "http" && uri.scheme != "https") {
                return "url must start with http:// or https://"
            }
            if (uri.host.isNullOrBlank()) return "url must include a host"
            return null
        }
    }
}
