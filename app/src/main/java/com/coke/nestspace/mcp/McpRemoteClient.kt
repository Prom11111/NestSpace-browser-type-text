package com.coke.nestspace.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class McpRemoteException(
    val status: Int?,
    val code: String,
    message: String
) : Exception(message)

data class McpResponse(
    val json: JSONObject?,
    val sessionId: String?
)

/**
 * Minimal MCP Streamable HTTP client used for user-configured external MCP
 * servers. It only consumes tool lists and tool calls; the resulting content is
 * always treated as external data by the local agent.
 */
object McpRemoteClient {

    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 120_000

    private val nextId = AtomicLong(1)

    suspend fun initialize(config: McpClientConfig): McpResponse {
        val response = post(
            config = config,
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject()
                        .put("name", "nestspace-android")
                        .put("version", "1.0")
                ),
            sessionId = null
        )
        runCatching {
            post(
                config = config,
                method = "notifications/initialized",
                params = null,
                sessionId = response.sessionId,
                notification = true
            )
        }
        return response
    }

    suspend fun listTools(
        config: McpClientConfig,
        sessionId: String?
    ): McpResponse = post(
        config = config,
        method = "tools/list",
        params = null,
        sessionId = sessionId
    )

    suspend fun callTool(
        config: McpClientConfig,
        sessionId: String?,
        toolName: String,
        arguments: JSONObject
    ): McpResponse = post(
        config = config,
        method = "tools/call",
        params = JSONObject()
            .put("name", toolName)
            .put("arguments", arguments),
        sessionId = sessionId
    )

    fun parseTools(response: McpResponse): List<McpRemoteTool> {
        val result = response.json?.optJSONObject("result")
            ?: throw McpRemoteException(null, "EMPTY_RESULT", "empty tools/list result")
        val tools = result.optJSONArray("tools") ?: return emptyList()
        return (0 until tools.length()).mapNotNull { index ->
            val tool = tools.optJSONObject(index) ?: return@mapNotNull null
            val name = tool.optString("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val schema = tool.optJSONObject("inputSchema")
                ?: JSONObject().put("type", "object").put("properties", JSONObject())
            val schemaText = schema.toString()
            McpRemoteTool(
                name = name,
                description = tool.optString("description").take(8_000),
                inputSchema = if (schemaText.length <= 50_000) {
                    schemaText
                } else {
                    "{\"type\":\"object\",\"properties\":{}}"
                }
            )
        }.distinctBy { it.name }.take(100)
    }

    fun parseCall(response: McpResponse): McpCallResult {
        val result = response.json?.optJSONObject("result")
            ?: return McpCallResult(null, true, "empty tools/call result")
        return McpCallResult(
            content = result.optJSONArray("content"),
            isError = result.optBoolean("isError", false),
            structured = result.opt("structuredContent")?.toString()
        )
    }

    private suspend fun post(
        config: McpClientConfig,
        method: String,
        params: JSONObject?,
        sessionId: String?,
        notification: Boolean = false
    ): McpResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("id", nextId.getAndIncrement())
        if (params != null) payload.put("params", params)
        if (notification) {
            payload.remove("id")
        }
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val url = runCatching { URL(config.url.trim()) }.getOrElse {
            throw McpRemoteException(null, "INVALID_URL", "invalid MCP server URL")
        }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Never replay the bearer token/session id to a redirect target.
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
            setRequestProperty("User-Agent", "NestSpace-MCP-Client/1.0")
            if (config.token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.token}")
            }
            if (!sessionId.isNullOrBlank()) {
                setRequestProperty("Mcp-Session-Id", sessionId)
            }
        }
        try {
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status in 300..399) {
                throw McpRemoteException(
                    status,
                    "HTTP_REDIRECT",
                    "MCP server redirected (HTTP $status); configure the final URL"
                )
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readLimited(it) }.orEmpty()
            val responseSession = connection.getHeaderField("Mcp-Session-Id")
            if (status !in 200..299) {
                throw McpRemoteException(
                    status,
                    "HTTP_$status",
                    "MCP HTTP $status: ${errorMessage(text)}"
                )
            }
            if (text.isBlank()) {
                return@withContext McpResponse(null, responseSession)
            }
            val json = parseJsonResponse(text, connection.contentType.orEmpty())
            val error = json.optJSONObject("error")
            if (error != null) {
                throw McpRemoteException(
                    status,
                    "RPC_ERROR",
                    error.optString("message", "MCP JSON-RPC error")
                )
            }
            McpResponse(json, responseSession)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonResponse(text: String, contentType: String): JSONObject {
        if (contentType.contains("text/event-stream", ignoreCase = true)) {
            val payloads = text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    runCatching { JSONObject(line) }.getOrNull()
                }
                .toList()
            if (payloads.isEmpty()) {
                throw McpRemoteException(null, "EMPTY_SSE", "empty MCP SSE response")
            }
            return payloads.lastOrNull { it.has("result") || it.has("error") }
                ?: payloads.last()
        }
        return runCatching { JSONObject(text) }.getOrElse {
            throw McpRemoteException(
                null,
                "BAD_JSON",
                "MCP returned non-JSON: ${text.take(300)}"
            )
        }
    }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw McpRemoteException(
                    null,
                    "RESPONSE_TOO_LARGE",
                    "MCP response exceeds 2 MB"
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun errorMessage(text: String): String {
        if (text.isBlank()) return "no details"
        return runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: text
        }.getOrDefault(text).take(500)
    }
}

data class McpCallResult(
    val content: JSONArray?,
    val isError: Boolean,
    val structured: String?
)
