package com.coke.nestspace.mcp

import android.content.Context
import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import com.coke.nestspace.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP Streamable HTTP server for the basic build.
 *
 * Why not Ktor here: Ktor 3.x references java.nio.file classes that are not
 * available on Android 7.x (API 24/25). This implementation only uses
 * java.net/java.io, so it runs on minSdk 24.
 *
 * Supported: POST /mcp with initialize / tools/list / tools/call / ping.
 * SSE GET is intentionally not implemented (405); clients should use POST.
 *
 * Production should replace this with the official MCP Kotlin SDK + Ktor.
 */
class McpServer(
    private val registry: ToolRegistry,
    private val token: String,
    context: Context,
    private val port: Int = McpNetwork.PORT,
    private val bindAddress: String = "127.0.0.1"
) {
    private companion object {
        const val MAX_BODY_BYTES = 40 * 1024 * 1024
        const val MAX_HEADER_LINE = 8 * 1024
        const val MAX_HEADERS = 100
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_CONNECTIONS = 32
        const val SESSION_IDLE_MS = 30 * 60_000L
        const val SSE_HEARTBEAT_MS = 15_000L
        const val HEADER_TIMEOUT_MS = 10_000L
        const val BODY_TIMEOUT_MS = 120_000L
        const val MAX_SSE_STREAMS = 4
        const val SSE_MAX_LIFETIME_MS = 10 * 60_000L
    }

    private class Session(val id: String, @Volatile var lastSeen: Long) {
        val active = AtomicInteger(0)
        @Volatile
        var releaseWhenIdle = false
    }

    private class HttpException(
        val status: Int,
        val reason: String,
        val body: String
    ) : Exception(body)

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var sessionCleaner: Thread? = null
    private val sessions = ConcurrentHashMap<String, Session>()
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    private val sseSlots = Semaphore(MAX_SSE_STREAMS)
    private val sseSockets = ConcurrentHashMap<Socket, ScheduledFuture<*>>()
    private val sseScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mcp-sse-watchdog").apply { isDaemon = true }
    }
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-client").apply { isDaemon = true }
    }
    /** Guides already sent to this MCP server session; one copy per app/site. */
    private val seenKnowledge: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val taskManager = McpTaskManager.get(context.applicationContext)
    private val taskSpecs = McpTaskTools.specs(taskManager)
    private val settingsSpecs = McpSettingsTools.specs(context.applicationContext)

    fun start() {
        if (running) return
        sessions.clear()
        val socket = openServerSocket()
        serverSocket = socket
        running = true
        startSessionCleaner()
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (t: Throwable) {
                    break
                }
                if (!connectionSlots.tryAcquire()) {
                    runCatching { client.close() }
                    continue
                }
                try {
                    executor.execute {
                        try {
                            if (running) {
                                handle(client)
                            } else {
                                runCatching { client.close() }
                            }
                        } finally {
                            connectionSlots.release()
                        }
                    }
                } catch (t: Throwable) {
                    connectionSlots.release()
                    runCatching { client.close() }
                }
            }
        }, "mcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Rebinding right after a restart can race sockets left in TIME_WAIT.
     * SO_REUSEADDR handles the common case; retry briefly for the rest.
     */
    private fun openServerSocket(): ServerSocket {
        var last: Throwable? = null
        repeat(3) { attempt ->
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(
                    java.net.InetSocketAddress(InetAddress.getByName(bindAddress), port),
                    50
                )
                return candidate
            } catch (t: Throwable) {
                last = t
                runCatching { candidate.close() }
                if (attempt < 2) {
                    runCatching { Thread.sleep(100L * (attempt + 1)) }
                }
            }
        }
        throw last ?: IllegalStateException("cannot bind $bindAddress:$port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        sessionCleaner?.interrupt()
        sessionCleaner = null
        sseSockets.keys.forEach { runCatching { it.close() } }
        sseSockets.clear()
        sseScheduler.shutdownNow()
        // Do not drop a lease while a request still owns it. Sessions that
        // are idle can release now; in-flight requests release in their
        // finally block when they become idle.
        sessions.values.forEach { session ->
            if (session.active.get() == 0) {
                registry.releaseSession(session.id)
            } else {
                session.releaseWhenIdle = true
            }
        }
        sessions.clear()
        // Do not interrupt the worker that may be handling this very request
        // (for example, the tool call that switches the server to LAN mode).
        // Already-accepted clients finish normally; idle threads exit.
        executor.shutdown()
    }

    private fun startSessionCleaner() {
        sessionCleaner = Thread({
            while (running) {
                try {
                    Thread.sleep(60_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val cutoff = System.currentTimeMillis() - SESSION_IDLE_MS
                val expired = sessions.entries
                    .filter { it.value.lastSeen < cutoff && it.value.active.get() == 0 }
                    .map { it.key }
                expired.forEach {
                    sessions.remove(it)
                    registry.releaseSession(it)
                }
            }
        }, "mcp-session-cleaner").apply {
            isDaemon = true
            start()
        }
    }

    private fun createSession(): String {
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(id, System.currentTimeMillis())
        return id
    }

    /**
     * Proper MCP clients send Mcp-Session-Id after initialize. Clients that
     * omit it still work, isolated by their remote IP instead of sharing one
     * global session (which would defeat control leases).
     */
    private fun resolveSession(provided: String?, remoteHost: String?): String? {
        val now = System.currentTimeMillis()
        if (!provided.isNullOrBlank()) {
            val session = sessions[provided] ?: return null
            session.lastSeen = now
            return session.id
        }
        val id = "anon:${remoteHost ?: "unknown"}"
        val session = sessions.computeIfAbsent(id) { Session(it, now) }
        session.lastSeen = now
        return id
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val header = headers["authorization"]?.trim().orEmpty()
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val supplied = header.substring(7).trim()
        return MessageDigest.isEqual(
            token.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun readHeaders(
        input: InputStream,
        deadline: Long
    ): MutableMap<String, String> {
        val headers = mutableMapOf<String, String>()
        var bytes = 0
        var count = 0
        while (true) {
            val line = readLine(input, deadline) ?: break
            if (line.isEmpty()) break
            bytes += line.length + 2
            count += 1
            if (count > MAX_HEADERS || bytes > MAX_HEADER_BYTES) {
                throw HttpException(
                    431,
                    "Request Header Fields Too Large",
                    "request headers too large"
                )
            }
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] =
                    line.substring(index + 1).trim()
            }
        }
        return headers
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = HEADER_TIMEOUT_MS.toInt()
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val headerDeadline = System.currentTimeMillis() + HEADER_TIMEOUT_MS
            try {
                val requestLine = readLine(input, headerDeadline) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeResponse(output, 400, "Bad Request", "")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = readHeaders(input, headerDeadline)
                val needsAuth = (method == "POST" || method == "DELETE") && path == "/mcp"
                // Check auth before reading a body, so unauthenticated LAN
                // clients cannot make the app allocate up to MAX_BODY_BYTES.
                if (needsAuth && !isAuthorized(headers)) {
                    writeResponse(output, 401, "Unauthorized", "unauthorized")
                    return
                }

                when {
                    method == "OPTIONS" -> writeResponse(
                        output, 200, "OK", "",
                        extraHeaders = mapOf("Allow" to "GET, POST, DELETE, OPTIONS")
                    )

                    method == "GET" && path == "/health" ->
                        writeResponse(output, 200, "OK", "ok")

                    method == "GET" && path == "/mcp" -> {
                        if (!isAuthorized(headers)) {
                            writeResponse(output, 401, "Unauthorized", "unauthorized")
                            return
                        }
                        val sessionId = headers["mcp-session-id"]?.trim().orEmpty()
                        if (sessionId.isBlank()) {
                            writeResponse(
                                output, 400, "Bad Request",
                                "Mcp-Session-Id is required for SSE"
                            )
                            return
                        }
                        val session = sessions[sessionId]
                        if (session == null) {
                            writeResponse(output, 404, "Not Found", "session not found")
                            return
                        }
                        if (!headers["accept"].orEmpty()
                                .contains("text/event-stream", ignoreCase = true)
                        ) {
                            writeResponse(
                                output, 406, "Not Acceptable",
                                "Accept must include text/event-stream"
                            )
                            return
                        }
                        session.lastSeen = System.currentTimeMillis()
                        if (!sseSlots.tryAcquire()) {
                            writeResponse(
                                output, 503, "Service Unavailable",
                                "too many SSE streams"
                            )
                            return
                        }
                        var lifetime: ScheduledFuture<*>? = null
                        try {
                            lifetime = sseScheduler.schedule(
                                { runCatching { socket.close() } },
                                SSE_MAX_LIFETIME_MS,
                                TimeUnit.MILLISECONDS
                            )
                            sseSockets[socket] = lifetime
                            streamSse(socket, output, sessionId)
                        } finally {
                            lifetime?.cancel(false)
                            sseSockets.remove(socket)
                            sseSlots.release()
                        }
                    }

                    method == "POST" && path == "/mcp" -> {
                        socket.soTimeout = BODY_TIMEOUT_MS.toInt()
                        val body = readBody(
                            input,
                            headers,
                            System.currentTimeMillis() + BODY_TIMEOUT_MS
                        )
                        val request = runCatching { JSONObject(body) }.getOrNull()
                        if (request == null) {
                            writeResponse(
                                output, 200, "OK",
                                errorResponse(null, -32700, "Parse error")
                            )
                            return
                        }
                        val initialize = request.optString("method") == "initialize"
                        val sessionId = if (initialize) {
                            val previousId = headers["mcp-session-id"]?.trim().orEmpty()
                            if (previousId.isNotBlank()) {
                                val previous = sessions[previousId]
                                if (previous != null && previous.active.get() == 0) {
                                    sessions.remove(previousId)
                                    registry.releaseSession(previousId)
                                }
                            }
                            createSession()
                        } else {
                            resolveSession(
                                headers["mcp-session-id"],
                                socket.inetAddress?.hostAddress
                            ) ?: run {
                                writeResponse(
                                    output, 404, "Not Found",
                                    "session not found; initialize again"
                                )
                                return
                            }
                        }
                        val session = sessions[sessionId]
                        session?.active?.incrementAndGet()
                        try {
                            if (!running) {
                                writeResponse(output, 503, "Service Unavailable", "server stopping")
                                return
                            }
                            val response = process(request, sessionId)
                            val headersOut = mapOf("Mcp-Session-Id" to sessionId)
                            if (response == null) {
                                writeResponse(output, 202, "Accepted", "", headersOut)
                            } else {
                                writeResponse(output, 200, "OK", response, headersOut)
                            }
                        } finally {
                            val remaining = session?.active?.decrementAndGet()
                            session?.lastSeen = System.currentTimeMillis()
                            if (session != null && remaining == 0 &&
                                session.releaseWhenIdle
                            ) {
                                session.releaseWhenIdle = false
                                registry.releaseSession(session.id)
                            }
                        }
                    }

                    method == "DELETE" && path == "/mcp" -> {
                        val sessionId = headers["mcp-session-id"]?.trim().orEmpty()
                        val removed = if (sessionId.isBlank()) null else sessions.remove(sessionId)
                        when {
                            sessionId.isBlank() -> writeResponse(
                                output, 400, "Bad Request", "Mcp-Session-Id is required"
                            )
                            removed == null -> writeResponse(
                                output, 404, "Not Found", "session not found"
                            )
                            else -> {
                                // Also cover a request that resolved this
                                // session but has not incremented active yet.
                                removed.releaseWhenIdle = true
                                if (removed.active.get() == 0) {
                                    registry.releaseSession(sessionId)
                                }
                                writeResponse(output, 204, "No Content", "")
                            }
                        }
                    }

                    else -> writeResponse(output, 404, "Not Found", "not found")
                }
            } catch (e: HttpException) {
                runCatching { writeResponse(output, e.status, e.reason, e.body) }
            } catch (e: SocketTimeoutException) {
                runCatching { writeResponse(output, 408, "Request Timeout", "request timeout") }
            } catch (t: Throwable) {
                runCatching { writeResponse(output, 500, "Internal Server Error", "") }
            }
        }
    }

    /**
     * Minimal Streamable HTTP server-to-client stream. NestSpace has no
     * unsolicited notifications yet, so this keeps the session alive with SSE
     * comments; clients that open GET /mcp no longer receive a 405.
     */
    private fun streamSse(socket: Socket, output: OutputStream, sessionId: String) {
        val headers = linkedMapOf(
            "Content-Type" to "text/event-stream",
            "Cache-Control" to "no-cache",
            "Connection" to "keep-alive",
            "Mcp-Session-Id" to sessionId
        )
        headers.putAll(corsHeaders())
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        runCatching {
            output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
            output.write(": connected\n\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }.onFailure { return }
        try {
            while (running) {
                Thread.sleep(SSE_HEARTBEAT_MS)
                if (!running || socket.isClosed) break
                val session = sessions[sessionId] ?: break
                session.lastSeen = System.currentTimeMillis()
                output.write(": ping\n\n".toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Throwable) {
            // The client closed the stream; nothing to clean up.
        }
    }

    private fun process(request: JSONObject, sessionId: String): String? {
        val id = request.opt("id")
        val method = request.optString("method")

        if (method.startsWith("notifications/")) {
            return null
        }

        return when (method) {
            "initialize" -> {
                val result = JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put(
                        "capabilities",
                        JSONObject().put("tools", JSONObject().put("listChanged", false))
                    )
                    .put(
                        "serverInfo",
                        JSONObject().put("name", "nestspace").put("version", "0.2.0")
                    )
                resultResponse(id, result)
            }

            "ping" -> resultResponse(id, JSONObject())

            "tools/list" -> {
                val tools = JSONArray()
                registry.list().forEach { spec ->
                    tools.put(
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("inputSchema", spec.inputSchema)
                    )
                }
                taskSpecs.forEach { spec ->
                    tools.put(
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("inputSchema", spec.inputSchema)
                    )
                }
                settingsSpecs.forEach { spec ->
                    tools.put(
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("inputSchema", spec.inputSchema)
                    )
                }
                resultResponse(id, JSONObject().put("tools", tools))
            }

            "tools/call" -> {
                val params = request.optJSONObject("params") ?: JSONObject()
                val name = params.optString("name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val ctx = ToolContext(
                    sessionId = sessionId,
                    caller = Caller.REMOTE_MCP,
                    // The control plane operates the virtual space by default;
                    // system-targeted calls still pass space_id=system.
                    spaceId = args.optString("space_id", "virtual-1"),
                    seenKnowledge = seenKnowledge
                )
                val extraSpec = taskSpecs.firstOrNull { it.name == name }
                    ?: settingsSpecs.firstOrNull { it.name == name }
                val result = runCatching {
                    runBlocking {
                        if (extraSpec != null) {
                            extraSpec.handler(args, ctx)
                        } else {
                            registry.execute(name, args, ctx)
                        }
                    }
                }.getOrElse {
                    com.coke.nestspace.tools.ToolResult(
                        ok = false,
                        text = "Tool $name failed: ${it.message ?: it.javaClass.simpleName}",
                        errorCode = "TOOL_ERROR"
                    )
                }

                val content = JSONArray()
                if (result.imageBase64 != null) {
                    content.put(
                        JSONObject()
                            .put("type", "image")
                            .put("data", result.imageBase64)
                            .put("mimeType", result.imageMimeType)
                    )
                }
                content.put(JSONObject().put("type", "text").put("text", result.text))
                resultResponse(
                    id,
                    JSONObject()
                        .put("content", content)
                        .put("isError", !result.ok)
                )
            }

            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun resultResponse(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun errorResponse(id: Any?, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
            .toString()

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to
            "Authorization, Content-Type, Mcp-Session-Id, MCP-Protocol-Version",
        "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
        "Access-Control-Expose-Headers" to "Mcp-Session-Id"
    )

    private fun readBody(
        input: InputStream,
        headers: Map<String, String>,
        deadline: Long
    ): String {
        val transferEncoding = headers["transfer-encoding"].orEmpty()
        if (transferEncoding.contains("chunked", ignoreCase = true)) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLine(input, deadline) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLine(input, deadline)
                    break
                }
                if (size < 0 || out.size().toLong() + size > MAX_BODY_BYTES) {
                    throw HttpException(413, "Payload Too Large", "request body too large")
                }
                val buffer = ByteArray(size)
                readFully(input, buffer, deadline)
                out.write(buffer)
                readLine(input, deadline)
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }

        val lengthText = headers["content-length"] ?: return ""
        val length = lengthText.toIntOrNull()
            ?: throw HttpException(400, "Bad Request", "invalid content-length")
        if (length < 0) {
            throw HttpException(400, "Bad Request", "invalid content-length")
        }
        if (length == 0) return ""
        if (length > MAX_BODY_BYTES) {
            throw HttpException(413, "Payload Too Large", "request body too large")
        }
        val buffer = ByteArray(length)
        readFully(input, buffer, deadline)
        return String(buffer, StandardCharsets.UTF_8)
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
        deadline: Long? = null
    ) {
        var offset = 0
        while (offset < buffer.size) {
            if (deadline != null && System.currentTimeMillis() > deadline) {
                throw HttpException(408, "Request Timeout", "request body timeout")
            }
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
    }

    private fun readLine(input: InputStream, deadline: Long? = null): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            if (deadline != null && System.currentTimeMillis() > deadline) {
                throw HttpException(408, "Request Timeout", "request header timeout")
            }
            val value = input.read()
            if (value < 0) {
                return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (value == '\n'.code) {
                val bytes = out.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, StandardCharsets.ISO_8859_1)
            }
            if (out.size() >= MAX_HEADER_LINE) {
                throw HttpException(
                    431,
                    "Request Header Fields Too Large",
                    "request line or header too long"
                )
            }
            out.write(value)
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = linkedMapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Connection" to "close"
        )
        headers.putAll(corsHeaders())
        headers.putAll(extraHeaders)

        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }
}
