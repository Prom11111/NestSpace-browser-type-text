package com.coke.nestspace.plugin

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.coke.nestspace.R
import com.coke.nestspace.BuildConfig
import com.coke.nestspace.browser.BrowserActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Capability-scoped host services.
 */
internal class AndroidPluginHostApi(
    context: Context,
    private val manifest: PluginManifest,
    capabilities: Set<PluginCapability>,
    allowedHttpHosts: Set<String>,
    allowedWebSocketHosts: Set<String>
) : PluginContext, AutoCloseable {
    private val context = context.applicationContext
    private val pluginId = manifest.id
    private val notifications = AndroidPluginNotifications(context, pluginId)
    private val network = AndroidPluginNetwork(allowedHttpHosts)
    private val webSockets = AndroidPluginWebSockets(allowedWebSocketHosts)
    private val android = AndroidPluginAndroidApi(context)
    private val storage = PluginFileStore.forPlugin(context, pluginId)
    private val files = AndroidPluginFiles(context)
    private val settings = AndroidPluginSettings(context, pluginId)
    private val secrets = AndroidPluginSecrets(context, pluginId)
    private val cache = AndroidPluginCache(context, pluginId)
    private val databases = AndroidPluginDatabases(context, pluginId)
    private val commands = AndroidPluginCommands()
    private val tasks = AndroidPluginTasks(pluginId)
    private val events = AndroidPluginEvents(pluginId)
    private val clipboard = AndroidPluginClipboard(context)
    private val share = AndroidPluginShare(context, pluginId)
    private val ui = AndroidPluginUi()
    private val llm = AndroidPluginLlm()
    private val capabilities = capabilities.toSet()

    override fun getPluginId(): String = pluginId

    override fun getDescriptor(): PluginDescriptor = manifest.descriptor

    override fun getManifest(): PluginManifest = manifest

    override fun getHostVersion(): String = BuildConfig.VERSION_NAME

    override fun getLocale(): Locale = Locale.getDefault()

    override fun android(): PluginAndroidApi {
        requireCapability(PluginCapability.ANDROID_APIS)
        return android
    }

    override fun notifications(): PluginNotificationApi {
        requireCapability(PluginCapability.NOTIFICATIONS)
        return notifications
    }

    override fun network(): PluginNetworkApi {
        requireCapability(PluginCapability.NETWORK)
        return network
    }

    override fun webSockets(): PluginWebSocketApi {
        requireCapability(PluginCapability.WEBSOCKET)
        return webSockets
    }

    override fun storage(): PluginStorageApi {
        requireCapability(PluginCapability.STORAGE)
        return storage
    }

    override fun settings(): PluginSettingsApi {
        requireCapability(PluginCapability.SETTINGS)
        return settings
    }

    override fun files(): PluginFileApi {
        if (PluginCapability.FILE_PICKER !in capabilities &&
            PluginCapability.FILE_EXPORT !in capabilities
        ) {
            error("plugin $pluginId does not have file access")
        }
        return object : PluginFileApi {
            override fun pickFiles(
                request: PluginFilePickerRequest?
            ): CompletableFuture<List<PluginPickedFile>> {
                requireCapability(PluginCapability.FILE_PICKER)
                return files.pickFiles(request)
            }

            override fun export(
                request: PluginFileExportRequest?
            ): CompletableFuture<PluginExportedFile> {
                requireCapability(PluginCapability.FILE_EXPORT)
                return files.export(request)
            }
        }
    }

    override fun secrets(): PluginSecretsApi {
        requireCapability(PluginCapability.SECRETS)
        return secrets
    }

    override fun cache(): PluginCacheApi {
        requireCapability(PluginCapability.CACHE)
        return cache
    }

    override fun databases(): PluginDatabaseApi {
        requireCapability(PluginCapability.DATABASE)
        return databases
    }

    override fun commands(): PluginCommandApi {
        requireCapability(PluginCapability.COMMANDS)
        return commands
    }

    override fun tasks(): PluginTaskApi {
        requireCapability(PluginCapability.TASKS)
        return tasks
    }

    override fun events(): PluginEventApi {
        requireCapability(PluginCapability.EVENTS)
        return events
    }

    override fun clipboard(): PluginClipboardApi {
        requireCapability(PluginCapability.CLIPBOARD)
        return clipboard
    }

    override fun share(): PluginShareApi {
        requireCapability(PluginCapability.SHARE)
        return share
    }

    override fun ui(): PluginUiApi {
        if (PluginCapability.UI_PAGES !in capabilities &&
            PluginCapability.UI_RENDERERS !in capabilities
        ) {
            error("plugin $pluginId does not have UI access")
        }
        return ui
    }

    override fun llm(): PluginLlmApi {
        requireCapability(PluginCapability.LLM_PROVIDERS)
        return llm
    }

    override fun close() {
        network.closeAll()
        webSockets.closeAll()
        tasks.closeAll()
        events.close()
        databases.closeAll()
    }

    private fun requireCapability(capability: PluginCapability) {
        require(capability in capabilities) {
            "plugin $pluginId does not have $capability"
        }
    }
}

private class AndroidPluginAndroidApi(
    private val context: Context
) : PluginAndroidApi {
    private val executor = ContextCompat.getMainExecutor(context)

    override fun getHostPackageName(): String = context.packageName

    override fun getAndroidApi(): Int = Build.VERSION.SDK_INT

    override fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun getLaunchablePackages(): List<PluginPackageInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        return infos.asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .map { packageName ->
                val label = runCatching {
                    context.packageManager.getApplicationLabel(
                        context.packageManager.getApplicationInfo(packageName, 0)
                    ).toString()
                }.getOrDefault(packageName)
                PluginPackageInfo(packageName, label)
            }
            .sortedBy { it.label.lowercase() }
            .take(200)
            .toList()
    }

    override fun launchPackage(packageName: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: error("package has no launch activity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }, executor)

    override fun openUrl(url: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val uri = PluginUrlPolicy.requireHttpUrl(url)
            context.startActivity(
                Intent(context, BrowserActivity::class.java)
                    .putExtra(BrowserActivity.EXTRA_URL, uri.toString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }, executor)
}

internal object PluginUrlPolicy {
    fun requireHttpUrl(url: String): URI =
        requireUrl(url, setOf("http", "https"))

    fun requireWebSocketUrl(url: String): URI =
        requireUrl(url, setOf("ws", "wss"))

    private fun requireUrl(url: String, allowedSchemes: Set<String>): URI {
        val uri = URI(url.trim())
        require(uri.scheme?.lowercase()?.let { it in allowedSchemes } == true) {
            "plugin URL scheme is not allowed"
        }
        require(!uri.host.isNullOrBlank()) {
            "plugin URL host is missing"
        }
        require(uri.userInfo == null) {
            "userinfo is not allowed in plugin URLs"
        }
        return uri
    }
}

internal object PluginNetworkPolicy {
    fun requireHostAllowed(host: String, allowedHosts: Set<String>) {
        val normalizedHost = host.lowercase()
        require(
            allowedHosts.any { allowed ->
                val normalized = allowed.trim().lowercase()
                when {
                    normalized.startsWith("*.") ->
                        normalizedHost.endsWith(normalized.removePrefix("*"))
                    else -> normalizedHost == normalized
                }
            }
        ) {
            "network host is not allowed for this plugin: $normalizedHost"
        }
    }
}

internal class PluginFileStore(
    root: File
) : PluginStorageApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val root = root.canonicalFile

    init {
        this.root.mkdirs()
        require(this.root.isDirectory) {
            "plugin storage directory could not be created"
        }
    }

    override fun writeBytes(
        path: String,
        data: ByteArray
    ): CompletableFuture<PluginStoredFile> = CompletableFuture.supplyAsync({
        require(data.size <= PluginStorageApi.MAX_FILE_BYTES) {
            "plugin file exceeds 10 MB"
        }
        val target = resolve(path)
        require(target != root) { "plugin storage path must name a file" }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use {
                it.write(data)
                it.fd.sync()
            }
            check(temp.renameTo(target)) {
                "plugin file could not be replaced atomically"
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        PluginStoredFile(target.relativeTo(root).invariantSeparatorsPath, target.length(), target.lastModified())
    }, executor)

    override fun readBytes(
        path: String,
        maxBytes: Int
    ): CompletableFuture<ByteArray> = CompletableFuture.supplyAsync({
        val limit = maxBytes.coerceIn(1, PluginStorageApi.MAX_READ_BYTES)
        val target = resolve(path)
        require(target.isFile) { "plugin file does not exist" }
        require(target.length() <= limit) { "plugin file exceeds read limit" }
        target.readBytes()
    }, executor)

    override fun delete(path: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val target = resolve(path)
            require(target.isFile) { "plugin file does not exist" }
            require(target.delete()) { "plugin file could not be deleted" }
        }, executor)

    override fun list(
        directory: String
    ): CompletableFuture<List<PluginStoredFile>> = CompletableFuture.supplyAsync({
        val base = if (directory.isBlank()) root.canonicalFile else resolve(directory)
        if (!base.exists()) return@supplyAsync emptyList()
        require(base.isDirectory) { "storage path is not a directory" }
        base.walkTopDown()
            .filter { it.isFile }
            .take(PluginStorageApi.MAX_LIST_ENTRIES + 1)
            .toList()
            .also {
                require(it.size <= PluginStorageApi.MAX_LIST_ENTRIES) {
                    "plugin storage contains too many files"
                }
            }
            .map {
                PluginStoredFile(
                    it.relativeTo(root).invariantSeparatorsPath,
                    it.length(),
                    it.lastModified()
                )
            }
    }, executor)

    private fun resolve(path: String): File {
        val relative = path.replace('\\', '/').trim()
        require(relative.isNotEmpty()) { "plugin storage path is required" }
        require('\u0000' !in relative) { "plugin storage path is invalid" }
        require(!relative.startsWith('/')) { "absolute paths are not allowed" }
        require(relative.split('/').none { it == ".." }) {
            "parent path traversal is not allowed"
        }
        val target = File(root, relative).canonicalFile
        val rootPath = root.canonicalPath + File.separator
        require(target.path == root.canonicalPath || target.path.startsWith(rootPath)) {
            "plugin storage path escapes its private directory"
        }
        return target
    }

    companion object {
        fun forPlugin(context: Context, pluginId: String): PluginFileStore {
            return PluginFileStore(
                File(context.filesDir, "plugins/${storageNamespace(pluginId)}/storage")
            )
        }

        internal fun storageNamespace(pluginId: String): String {
            require(pluginId.isNotBlank()) { "plugin id is required" }
            return MessageDigest.getInstance("SHA-256")
                .digest(pluginId.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

private class AndroidPluginNotifications(
    private val context: Context,
    private val pluginId: String
) : PluginNotificationApi {
    private val channelId = "plugin_${safeId(pluginId)}"

    override fun post(notification: PluginNotification): CompletableFuture<Void> =
        CompletableFuture.supplyAsync({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                error("notification permission is not granted")
            }
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                error("notifications are disabled")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(
                        NotificationChannel(
                            channelId,
                            "Plugins",
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
            }
            val notificationId = (pluginId + ':' + notification.id).hashCode()
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ai_assistant_cutout)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            notification.actions.forEach { action ->
                val actionIntent = Intent(
                    context,
                    PluginNotificationActionReceiver::class.java
                ).apply {
                    setPackage(context.packageName)
                    putExtra(
                        PluginNotificationActionReceiver.EXTRA_PLUGIN_ID,
                        pluginId
                    )
                    putExtra(
                        PluginNotificationActionReceiver.EXTRA_NOTIFICATION_ID,
                        notification.id
                    )
                    putExtra(
                        PluginNotificationActionReceiver.EXTRA_ACTION_ID,
                        action.id
                    )
                }
                builder.addAction(
                    0,
                    action.title,
                    PendingIntent.getBroadcast(
                        context,
                        (pluginId + notification.id + action.id).hashCode(),
                        actionIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or
                            PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            val built = builder.build()
            NotificationManagerCompat.from(context)
                .notify(pluginId, notificationId, built)
            null
        }, Dispatchers.IO.asExecutor())

    override fun cancel(notificationId: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            NotificationManagerCompat.from(context).cancel(
                pluginId,
                (pluginId + ':' + notificationId).hashCode()
            )
        }, Dispatchers.IO.asExecutor())

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
}

internal class AndroidPluginNetwork(
    private val allowedHosts: Set<String>
) : PluginNetworkApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val activeSse = java.util.concurrent.ConcurrentHashMap.newKeySet<AndroidSseConnection>()

    override fun execute(request: PluginHttpRequest): CompletableFuture<PluginHttpResponse> =
        CompletableFuture.supplyAsync({ executeBlocking(request) }, executor)

    override fun openSse(
        request: PluginSseRequest,
        listener: PluginSseListener
    ): PluginSseConnection {
        val uri = PluginUrlPolicy.requireHttpUrl(request.url)
        val host = requireNotNull(uri.host) { "SSE host is missing" }
        PluginNetworkPolicy.requireHostAllowed(host, allowedHosts)
        val connection = AndroidSseConnection(uri.toString(), listener)
        activeSse += connection
        CompletableFuture.runAsync({
            try {
                runSse(uri, request, connection)
            } finally {
                activeSse -= connection
            }
        }, executor)
        return connection
    }

    fun closeAll() {
        activeSse.toList().forEach { it.close() }
        activeSse.clear()
    }

    private fun executeBlocking(request: PluginHttpRequest): PluginHttpResponse {
        val uri = PluginUrlPolicy.requireHttpUrl(request.url)
        val host = uri.host?.lowercase()
            ?: error("network request host is missing")
        PluginNetworkPolicy.requireHostAllowed(host, allowedHosts)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.useCaches = false
            connection.connectTimeout = request.timeoutMs
            connection.readTimeout = request.timeoutMs
            connection.requestMethod = request.method.name
            connection.setRequestProperty("User-Agent", "NestSpace-Plugin/1.0")
            request.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            if (request.body.isNotBlank()) {
                require(
                    request.method != PluginHttpMethod.GET &&
                        request.method != PluginHttpMethod.DELETE
                ) { "GET/DELETE cannot include a request body" }
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream?.use { readLimited(it, MAX_RESPONSE_BYTES) }.orEmpty()
            return PluginHttpResponse(
                status,
                connection.contentType.orEmpty(),
                body
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun runSse(
        uri: URI,
        request: PluginSseRequest,
        session: AndroidSseConnection
    ) {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        session.attach(connection)
        try {
            connection.useCaches = false
            connection.connectTimeout = request.connectTimeoutMs
            connection.readTimeout = request.readTimeoutMs
            connection.requestMethod = request.method.name
            connection.setRequestProperty("User-Agent", "NestSpace-Plugin/1.0")
            if (request.headers.keys.none {
                    it.equals("Accept", ignoreCase = true)
                }
            ) {
                connection.setRequestProperty("Accept", "text/event-stream")
            }
            request.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            if (request.body.isNotEmpty()) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("SSE request failed with HTTP $status")
            }
            session.markOpen(status, connection.contentType.orEmpty())
            val events = SseEventAccumulator()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null && !session.isCloseRequested()) {
                    require(
                        line.length <= SseEventAccumulator.MAX_EVENT_BYTES
                    ) { "SSE line exceeds 1 MB" }
                    events.accept(line)?.let(session::dispatchEvent)
                    line = reader.readLine()
                }
            }
            session.markClosed()
        } catch (error: Throwable) {
            if (session.isCloseRequested()) {
                session.markClosed()
            } else {
                session.markFailed(error)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(
        stream: java.io.InputStream,
        maxBytes: Int
    ): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "network response exceeds 1 MB" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
    }
}

private class AndroidSseConnection(
    private val url: String,
    private val listener: PluginSseListener
) : PluginSseConnection {
    private val connection = AtomicReference<HttpURLConnection?>()
    private val terminal = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    @Volatile
    private var open = false

    fun attach(value: HttpURLConnection) {
        connection.set(value)
        if (closeRequested.get()) value.disconnect()
    }

    fun markOpen(statusCode: Int, contentType: String) {
        if (closeRequested.get()) return
        open = true
        dispatch {
            listener.onOpen(this, statusCode, contentType)
        }
    }

    fun dispatchEvent(event: PluginSseEvent) {
        if (closeRequested.get()) return
        dispatch { listener.onEvent(this, event) }
    }

    fun markClosed() {
        open = false
        if (terminal.compareAndSet(false, true)) {
            dispatch { listener.onClosed(this) }
        }
    }

    fun markFailed(error: Throwable) {
        open = false
        if (terminal.compareAndSet(false, true)) {
            dispatch { listener.onFailure(this, error) }
        }
    }

    fun isCloseRequested(): Boolean = closeRequested.get()

    override fun getUrl(): String = url

    override fun isOpen(): Boolean = open && !terminal.get()

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        open = false
        connection.get()?.disconnect()
    }

    private inline fun dispatch(block: () -> Unit) {
        runCatching(block).onFailure {
            Log.w("PluginSse", "listener callback failed", it)
        }
    }
}

internal class AndroidPluginWebSockets(
    private val allowedHosts: Set<String>
) : PluginWebSocketApi {
    private val baseClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<OkHttpPluginWebSocket>()

    override fun connect(
        request: PluginWebSocketRequest,
        listener: PluginWebSocketListener
    ): PluginWebSocket {
        val uri = PluginUrlPolicy.requireWebSocketUrl(request.url)
        val host = requireNotNull(uri.host) { "WebSocket host is missing" }
        PluginNetworkPolicy.requireHostAllowed(host, allowedHosts)

        val requestBuilder = Request.Builder()
            .url(uri.toString())
            .header("User-Agent", "NestSpace-Plugin/1.0")
        request.headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }

        val client = baseClient.newBuilder()
            .connectTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val session = OkHttpPluginWebSocket(uri.toString(), listener)
        active += session
        val transport = client.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    session.markOpen(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.toByteArray(Charsets.UTF_8).size >
                        PluginWebSocket.MAX_MESSAGE_BYTES
                    ) {
                        session.rejectOversizedMessage(webSocket)
                    } else {
                        session.dispatchText(text)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    if (bytes.size > PluginWebSocket.MAX_MESSAGE_BYTES) {
                        session.rejectOversizedMessage(webSocket)
                    } else {
                        session.dispatchBinary(bytes.toByteArray())
                    }
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    session.dispatchClosing(code, reason)
                    webSocket.close(code, reason)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    session.markClosed(code, reason)
                    active -= session
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    session.markFailed(t)
                    active -= session
                }
            }
        )
        session.attach(transport)
        return session
    }

    fun closeAll() {
        active.toList().forEach { it.close(1000, "plugin detached") }
        active.clear()
    }
}

private class OkHttpPluginWebSocket(
    private val url: String,
    private val listener: PluginWebSocketListener
) : PluginWebSocket {
    private val transport = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
    private val terminal = AtomicBoolean(false)

    @Volatile
    private var open = false

    fun attach(webSocket: WebSocket) {
        transport.compareAndSet(null, webSocket)
    }

    fun markOpen(webSocket: WebSocket) {
        attach(webSocket)
        open = true
        dispatch("open") { listener.onOpen(this) }
    }

    fun dispatchText(text: String) {
        dispatch("text") { listener.onText(this, text) }
    }

    fun dispatchBinary(data: ByteArray) {
        dispatch("binary") { listener.onBinary(this, data) }
    }

    fun dispatchClosing(code: Int, reason: String) {
        open = false
        dispatch("closing") { listener.onClosing(this, code, reason) }
    }

    fun markClosed(code: Int, reason: String) {
        open = false
        if (terminal.compareAndSet(false, true)) {
            dispatch("closed") { listener.onClosed(this, code, reason) }
        }
    }

    fun markFailed(error: Throwable) {
        open = false
        if (terminal.compareAndSet(false, true)) {
            dispatch("failure") { listener.onFailure(this, error) }
        }
    }

    fun rejectOversizedMessage(webSocket: WebSocket) {
        open = false
        if (terminal.compareAndSet(false, true)) {
            dispatch("failure") {
                listener.onFailure(
                    this,
                    IllegalArgumentException(
                        "WebSocket message exceeds 1 MB"
                    )
                )
            }
        }
        webSocket.close(1009, "message too large")
    }

    override fun getUrl(): String = url

    override fun isOpen(): Boolean = open && !terminal.get()

    override fun sendText(text: String): Boolean {
        if (!isOpen() ||
            text.toByteArray(Charsets.UTF_8).size >
            PluginWebSocket.MAX_MESSAGE_BYTES
        ) {
            return false
        }
        return transport.get()?.send(text) == true
    }

    override fun sendBinary(data: ByteArray): Boolean {
        if (!isOpen() || data.size > PluginWebSocket.MAX_MESSAGE_BYTES) {
            return false
        }
        return transport.get()?.send(data.toByteString()) == true
    }

    override fun close(code: Int, reason: String): Boolean {
        require(code == 1000 || code in 3000..4999) {
            "WebSocket close code must be 1000 or 3000-4999"
        }
        require(
            reason.toByteArray(Charsets.UTF_8).size <=
                PluginWebSocket.MAX_CLOSE_REASON_BYTES
        ) {
            "WebSocket close reason is too long"
        }
        if (terminal.get()) return false
        return transport.get()?.close(code, reason) == true
    }

    private inline fun dispatch(event: String, block: () -> Unit) {
        runCatching(block).onFailure {
            Log.w("PluginWebSocket", "listener $event callback failed", it)
        }
    }
}
