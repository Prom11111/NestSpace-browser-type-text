package com.coke.nestspace.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.coke.nestspace.data.ApiKeyCipher
import com.coke.nestspace.task.OperationTaskRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AndroidPluginSettings(
    context: Context,
    pluginId: String
) : PluginSettingsApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val preferences = context.applicationContext
        .getSharedPreferences("plugin_settings", Context.MODE_PRIVATE)
    private val prefix = PluginFileStore.storageNamespace(pluginId) + ":"

    override fun contains(key: String): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            preferences.contains(prefix + validKey(key))
        }, executor)

    override fun getString(
        key: String,
        fallback: String?
    ): CompletableFuture<String> = CompletableFuture.supplyAsync({
        preferences.getString(prefix + validKey(key), fallback).orEmpty()
    }, executor)

    override fun getBoolean(
        key: String,
        fallback: Boolean
    ): CompletableFuture<Boolean> = CompletableFuture.supplyAsync({
        preferences.getBoolean(prefix + validKey(key), fallback)
    }, executor)

    override fun getLong(
        key: String,
        fallback: Long
    ): CompletableFuture<Long> = CompletableFuture.supplyAsync({
        preferences.getLong(prefix + validKey(key), fallback)
    }, executor)

    override fun putString(
        key: String,
        value: String?
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        val text = value.orEmpty()
        require(text.length <= PluginSettingsApi.MAX_STRING_CHARS) {
            "plugin setting exceeds 64 KB"
        }
        check(
            preferences.edit()
                .putString(prefix + validKey(key), text)
                .commit()
        ) { "plugin setting could not be saved" }
    }, executor)

    override fun putBoolean(
        key: String,
        value: Boolean
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        check(
            preferences.edit()
                .putBoolean(prefix + validKey(key), value)
                .commit()
        ) { "plugin setting could not be saved" }
    }, executor)

    override fun putLong(
        key: String,
        value: Long
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        check(
            preferences.edit()
                .putLong(prefix + validKey(key), value)
                .commit()
        ) { "plugin setting could not be saved" }
    }, executor)

    override fun remove(key: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            check(
                preferences.edit()
                    .remove(prefix + validKey(key))
                    .commit()
            ) { "plugin setting could not be removed" }
        }, executor)

    override fun keys(): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
                .toList()
        }, executor)

    private fun validKey(key: String): String {
        val value = key.trim()
        require(value.matches(KEY)) { "invalid plugin setting key" }
        return value
    }

    companion object {
        private val KEY = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}

internal class AndroidPluginSecrets(
    context: Context,
    pluginId: String
) : PluginSecretsApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val preferences = context.applicationContext
        .getSharedPreferences("plugin_secrets", Context.MODE_PRIVATE)
    private val prefix = PluginFileStore.storageNamespace(pluginId) + ":"

    override fun put(key: String, value: String?): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val plain = value.orEmpty()
            require(plain.length <= PluginSecretsApi.MAX_SECRET_CHARS) {
                "plugin secret exceeds 64 KB"
            }
            val encrypted = ApiKeyCipher.encrypt(plain)
            check(plain.isEmpty() || encrypted.isNotEmpty()) {
                "plugin secret encryption failed"
            }
            check(
                preferences.edit()
                    .putString(prefix + validKey(key), encrypted)
                    .commit()
            ) { "plugin secret could not be saved" }
        }, executor)

    override fun get(key: String, fallback: String?): CompletableFuture<String> =
        CompletableFuture.supplyAsync({
            val encrypted = preferences.getString(
                prefix + validKey(key),
                null
            ) ?: return@supplyAsync fallback.orEmpty()
            ApiKeyCipher.decrypt(encrypted)
        }, executor)

    override fun contains(key: String): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            preferences.contains(prefix + validKey(key))
        }, executor)

    override fun remove(key: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            check(
                preferences.edit()
                    .remove(prefix + validKey(key))
                    .commit()
            ) { "plugin secret could not be removed" }
        }, executor)

    override fun keys(): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({
            preferences.all.keys
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
                .toList()
        }, executor)

    private fun validKey(key: String): String {
        val value = key.trim()
        require(value.matches(KEY)) { "invalid plugin secret key" }
        return value
    }

    companion object {
        private val KEY = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}

internal class AndroidPluginCache(
    context: Context,
    pluginId: String
) : PluginCacheApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val namespace = PluginFileStore.storageNamespace(pluginId)
    private val root = File(
        context.applicationContext.filesDir,
        "plugins/$namespace/cache"
    ).apply { mkdirs() }
    private val preferences = context.applicationContext
        .getSharedPreferences("plugin_cache", Context.MODE_PRIVATE)

    override fun put(
        key: String,
        value: ByteArray?,
        ttlSeconds: Long
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        val validKey = validateKey(key)
        val data = requireNotNull(value) { "cache value is required" }
        require(data.size <= PluginCacheApi.MAX_ENTRY_BYTES) {
            "plugin cache entry exceeds 8 MB"
        }
        require(ttlSeconds >= 0) { "ttlSeconds cannot be negative" }
        pruneExpired()
        val target = File(root, digest(validKey))
        val temporary = File(root, ".${target.name}.tmp")
        try {
            FileOutputStream(temporary).use {
                it.write(data)
                it.fd.sync()
            }
            check(temporary.renameTo(target)) {
                "plugin cache entry could not be replaced"
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        val expiresAt = if (ttlSeconds == 0L) 0L
        else System.currentTimeMillis() + ttlSeconds * 1_000L
        preferences.edit()
            .putLong(metaKey(validKey, "expires"), expiresAt)
            .putString(metaKey(validKey, "origin"), validKey)
            .commit()
    }, executor)

    override fun get(key: String): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync({
            val validKey = validateKey(key)
            val file = File(root, digest(validKey))
            if (!file.isFile || isExpired(validKey)) {
                file.delete()
                removeMetadata(validKey)
                return@supplyAsync ByteArray(0)
            }
            file.readBytes()
        }, executor)

    override fun contains(key: String): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            val validKey = validateKey(key)
            val exists = File(root, digest(validKey)).isFile &&
                !isExpired(validKey)
            if (!exists) remove(validKey).get()
            exists
        }, executor)

    override fun remove(key: String): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val validKey = validateKey(key)
            File(root, digest(validKey)).delete()
            removeMetadata(validKey)
            Unit
        }, executor)

    override fun clear(): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            root.listFiles()?.forEach(File::delete)
            val editor = preferences.edit()
            preferences.all.keys
                .filter { it.startsWith("$namespace:") }
                .forEach(editor::remove)
            editor.commit()
        }, executor)

    override fun sizeBytes(): CompletableFuture<Long> =
        CompletableFuture.supplyAsync({
            pruneExpired()
            root.listFiles()?.filter(File::isFile)?.sumOf(File::length)
                ?: 0L
        }, executor)

    private fun pruneExpired() {
        preferences.all.keys
            .filter { it.startsWith("$namespace:") && it.endsWith(":expires") }
            .forEach { key ->
                val origin = preferences.getString(
                    key.removeSuffix(":expires") + ":origin",
                    ""
                ).orEmpty()
                if (origin.isBlank() || isExpired(origin)) {
                    File(root, digest(origin)).delete()
                    if (origin.isNotBlank()) removeMetadata(origin)
                }
            }
    }

    private fun isExpired(key: String): Boolean {
        val expires = preferences.getLong(metaKey(key, "expires"), 0L)
        return expires > 0L && System.currentTimeMillis() >= expires
    }

    private fun validateKey(key: String): String {
        val value = key.trim()
        require(value.matches(KEY)) { "invalid plugin cache key" }
        return value
    }

    private fun metaKey(key: String, field: String): String =
        "$namespace:${digest(key)}:$field"

    private fun removeMetadata(key: String) {
        preferences.edit()
            .remove(metaKey(key, "expires"))
            .remove(metaKey(key, "origin"))
            .commit()
    }

    private fun digest(value: String): String =
        PluginFileStore.storageNamespace(value)

    companion object {
        private val KEY = Regex("[A-Za-z0-9_.:/-]{1,128}")
    }
}

internal class AndroidPluginDatabases(
    private val context: Context,
    pluginId: String
) : PluginDatabaseApi {
    private val executor = Dispatchers.IO.asExecutor()
    private val root = File(
        context.applicationContext.filesDir,
        "plugins/${PluginFileStore.storageNamespace(pluginId)}/databases"
    ).apply { mkdirs() }
    private val openDatabases = ConcurrentHashMap<String, SQLiteDatabase>()

    override fun open(
        databaseName: String?
    ): CompletableFuture<SQLiteDatabase> =
        CompletableFuture.supplyAsync({
            val name = validateName(databaseName)
            openDatabases[name] ?: SQLiteDatabase.openOrCreateDatabase(
                File(root, "$name.db"),
                null
            ).apply {
                setForeignKeyConstraintsEnabled(true)
                openDatabases[name] = this
            }
        }, executor)

    override fun list(): CompletableFuture<List<String>> =
        CompletableFuture.supplyAsync({
            root.listFiles()
                ?.filter { it.isFile && it.extension == "db" }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
                ?: emptyList()
        }, executor)

    override fun delete(databaseName: String?): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            val name = validateName(databaseName)
            openDatabases.remove(name)?.close()
            File(root, "$name.db").delete()
            File(root, "$name.db-wal").delete()
            File(root, "$name.db-shm").delete()
            Unit
        }, executor)

    fun closeAll() {
        openDatabases.values.forEach {
            runCatching { it.close() }
        }
        openDatabases.clear()
    }

    private fun validateName(value: String?): String {
        val name = value?.trim().orEmpty()
        require(name.matches(NAME)) {
            "database name must contain 1-64 letters, digits, '.', '_' or '-'"
        }
        return name
    }

    companion object {
        private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    }
}

internal object PluginEventBus {
    private data class Subscription(
        val pluginId: String,
        val types: Set<String>,
        val listener: PluginEventListener
    )

    private val nextId = AtomicLong()
    private val subscriptions = ConcurrentHashMap<Long, Subscription>()
    private val executor = Executors.newCachedThreadPool(
        threadFactory("PluginEvents")
    )

    fun subscribe(
        pluginId: String,
        types: Set<String>,
        listener: PluginEventListener
    ): PluginSubscription {
        require(types.size in 1..64) {
            "subscribe to between 1 and 64 event types"
        }
        val normalized = types.map { value ->
            value.trim().also {
                require(it.isNotEmpty() && it.length <= 160) {
                    "invalid plugin event type"
                }
            }
        }.toSet()
        val id = nextId.incrementAndGet()
        subscriptions[id] = Subscription(pluginId, normalized, listener)
        return object : PluginSubscription {
            private val closed = AtomicBoolean()

            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    subscriptions.remove(id)
                }
            }
        }
    }

    fun publish(event: PluginEvent) {
        subscriptions.values
            .filter { "*" in it.types || event.type in it.types }
            .forEach { subscription ->
                executor.execute {
                    runCatching {
                        subscription.listener.onEvent(event)
                    }.onFailure {
                        Log.w(
                            "PluginEventBus",
                            "event listener failed: ${subscription.pluginId}",
                            it
                        )
                    }
                }
            }
    }

    fun removePlugin(pluginId: String) {
        subscriptions.entries.removeIf {
            it.value.pluginId == pluginId
        }
    }
}

internal class AndroidPluginEvents(
    private val pluginId: String
) : PluginEventApi {
    override fun subscribe(
        eventTypes: Set<String>?,
        listener: PluginEventListener?
    ): PluginSubscription {
        requireNotNull(listener) { "event listener is required" }
        return PluginEventBus.subscribe(
            pluginId,
            eventTypes?.toSet().orEmpty(),
            listener
        )
    }

    override fun publish(event: PluginEvent?): CompletableFuture<Void> =
        CompletableFuture.runAsync({
            requireNotNull(event) { "event is required" }
            PluginEventBus.publish(
                PluginEvent(
                    event.type,
                    pluginId,
                    event.payloadJson,
                    System.currentTimeMillis()
                )
            )
        }, Dispatchers.IO.asExecutor())

    fun close() {
        PluginEventBus.removePlugin(pluginId)
    }
}

internal class AndroidPluginTasks(
    private val pluginId: String
) : PluginTaskApi {
    private val executor = Executors.newFixedThreadPool(
        MAX_CONCURRENT_TASKS,
        threadFactory("PluginTask-${pluginId.take(24)}")
    )
    private val active = ConcurrentHashMap<String, AndroidPluginTaskHandle>()

    override fun submit(
        request: PluginTaskRequest?,
        body: PluginTaskBody?
    ): PluginTaskHandle {
        requireNotNull(request) { "task request is required" }
        requireNotNull(body) { "task body is required" }
        require(active.size < MAX_ACTIVE_TASKS) {
            "too many active plugin tasks"
        }
        val handle = AndroidPluginTaskHandle(
            request,
            body,
            executor,
            active::remove
        )
        val task = OperationTaskRegistry.start(
            ownerId = "plugin:$pluginId",
            kind = "plugin_task",
            title = request.name,
            requestId = handle.getId(),
            requestHash = "$pluginId:${request.name}:${request.timeoutMs}",
            onCancel = handle::cancelFromHost
        ).task ?: error("could not create plugin task")
        handle.attachHostTask(task.id)
        active[handle.id] = handle
        handle.start()
        return handle
    }

    fun closeAll() {
        active.values.toList().forEach { it.cancel() }
        active.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val MAX_ACTIVE_TASKS = 8
        private const val MAX_CONCURRENT_TASKS = 4
    }
}

private class AndroidPluginTaskHandle(
    private val request: PluginTaskRequest,
    private val body: PluginTaskBody,
    private val executor: ExecutorService,
    private val onFinished: (String) -> Unit
) : PluginTaskHandle {
    private val future = AtomicReference<Future<*>?>()
    private val result = CompletableFuture<PluginTaskResult>()
    private val cancelled = AtomicBoolean()
    private val progress = AtomicReference<Pair<Int, String>>(0 to "")

    @Volatile
    private var done = false

    @Volatile
    private var hostTaskId: String? = null

    private val taskId: String = UUID.randomUUID().toString()

    fun attachHostTask(id: String) {
        hostTaskId = id
    }

    fun start() {
        hostTaskId?.let {
            OperationTaskRegistry.transition(
                it,
                OperationTaskRegistry.State.RUNNING,
                "plugin task running"
            )
        }
        Scheduler.schedule({
            if (!result.isDone) {
                cancelled.set(true)
                future.get()?.cancel(true)
                finish(
                    PluginTaskResult.failure(
                        "Plugin task timed out",
                        "TIMEOUT"
                    )
                )
            }
        }, request.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        future.set(executor.submit {
            try {
                body.run(TaskScope())
                if (!result.isDone) {
                    finish(PluginTaskResult.success(progress.get().second))
                }
            } catch (_: CancellationException) {
                finish(PluginTaskResult.cancelled("Plugin task cancelled"))
            } catch (error: Throwable) {
                finish(
                    PluginTaskResult.failure(
                        error.message ?: error.javaClass.simpleName,
                        "TASK_ERROR"
                    )
                )
            }
        })
    }

    override fun getId(): String = taskId

    override fun isDone(): Boolean = done

    override fun isCancelled(): Boolean = cancelled.get()

    override fun getProgressPercent(): Int = progress.get().first

    override fun getProgressMessage(): String = progress.get().second

    override fun cancel(): Boolean {
        if (result.isDone) return false
        cancelled.set(true)
        future.get()?.cancel(true)
        return finish(PluginTaskResult.cancelled("Plugin task cancelled"))
    }

    fun cancelFromHost() {
        cancel()
    }

    override fun getResult(): CompletableFuture<PluginTaskResult> = result

    private fun finish(value: PluginTaskResult): Boolean {
        val completed = result.complete(value)
        if (completed) {
            done = true
            hostTaskId?.let { id ->
                when {
                    value.isSuccessful -> OperationTaskRegistry.succeed(
                        id,
                        value.message
                    )
                    value.isCancelled -> OperationTaskRegistry.markCancelled(
                        id,
                        value.message.ifBlank { "plugin task cancelled" }
                    )
                    else -> OperationTaskRegistry.fail(
                        id,
                        value.message.ifBlank { value.errorCode }
                    )
                }
            }
            onFinished(taskId)
        }
        return completed
    }

    private inner class TaskScope : PluginTaskContext {
        override fun getTaskId(): String = taskId

        override fun isCancelled(): Boolean = cancelled.get()

        override fun checkCancelled() {
            if (cancelled.get()) throw CancellationException()
        }

        override fun reportProgress(percent: Int, message: String?) {
            require(percent in 0..100) { "progress must be between 0 and 100" }
            progress.set(percent to message.orEmpty())
            hostTaskId?.let {
                OperationTaskRegistry.progress(
                    it,
                    percent,
                    message
                )
            }
        }
    }

    private companion object {
        val Scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor(
                threadFactory("PluginTaskTimeout")
            )
    }
}

internal class AndroidPluginCommands : PluginCommandApi {
    private val executor = Dispatchers.IO.asExecutor()

    override fun listAsync(): CompletableFuture<List<PluginCommandInfo>> =
        CompletableFuture.supplyAsync({
            PluginCommandPlugins.snapshot().flatMap { provider ->
                val specs = runCatching {
                    provider.listCommandsAsync().get(2, TimeUnit.SECONDS)
                }.onFailure {
                    Log.w(
                        "PluginCommands",
                        "list failed: ${provider.descriptor.id}",
                        it
                    )
                }.getOrDefault(emptyList())
                specs.map {
                    PluginCommandInfo(provider.descriptor.id, it)
                }
            }
        }, executor)

    override fun invokeAsync(
        commandId: String?,
        argumentsJson: String?
    ): CompletableFuture<PluginCommandResult> =
        CompletableFuture.supplyAsync({
            val id = requireNotNull(commandId) { "command id is required" }
            val separator = id.indexOf('/')
            require(separator > 0) { "invalid qualified command id" }
            val pluginId = id.substring(0, separator)
            val localId = id.substring(separator + 1)
            val provider = PluginCommandPlugins.snapshot()
                .firstOrNull { it.descriptor.id == pluginId }
                ?: return@supplyAsync PluginCommandResult.failure(
                    "Plugin command is not available",
                    "COMMAND_NOT_FOUND"
                )
            val spec = provider.listCommandsAsync()
                .get(2, TimeUnit.SECONDS)
                .firstOrNull { it.id == localId }
                ?: return@supplyAsync PluginCommandResult.failure(
                    "Plugin command is not available",
                    "COMMAND_NOT_FOUND"
                )
            provider.invokeCommandAsync(
                PluginCommandCall(
                    spec.id,
                    argumentsJson.orEmpty().ifBlank { "{}" }
                )
            ).get(30, TimeUnit.SECONDS)
        }, executor)
}

internal class AndroidPluginUi : PluginUiApi {
    private val executor = Dispatchers.IO.asExecutor()

    override fun listPagesAsync(): CompletableFuture<List<PluginPageSpec>> =
        CompletableFuture.supplyAsync({
            PluginUiPlugins.snapshot()
                .filter {
                    PluginCapability.UI_PAGES in
                        it.descriptor.capabilities
                }
                .flatMap { provider ->
                    provider.listPagesAsync()
                        .get(2, TimeUnit.SECONDS)
                }
        }, executor)

    override fun listRenderersAsync():
        CompletableFuture<List<PluginRendererSpec>> =
        CompletableFuture.supplyAsync({
            PluginUiPlugins.snapshot()
                .filter {
                    PluginCapability.UI_RENDERERS in
                        it.descriptor.capabilities
                }
                .flatMap { provider ->
                    provider.listRenderersAsync()
                        .get(2, TimeUnit.SECONDS)
                }
        }, executor)

    override fun readPageAsync(
        pluginId: String?,
        pageId: String?
    ): CompletableFuture<String> = CompletableFuture.supplyAsync({
        val provider = PluginUiPlugins.snapshot()
            .firstOrNull { it.descriptor.id == pluginId }
            ?: error("plugin UI is not available")
        val page = provider.listPagesAsync()
            .get(2, TimeUnit.SECONDS)
            .firstOrNull { it.id == pageId }
            ?: error("plugin page is not available")
        val document = provider.readPageAsync(page.id)
            .get(5, TimeUnit.SECONDS)
        require(document.length <= 1_000_000) {
            "plugin page document exceeds 1 MB"
        }
        document
    }, executor)
}

internal class AndroidPluginLlm : PluginLlmApi {
    override fun listModelsAsync():
        CompletableFuture<List<PluginLlmModel>> =
        CompletableFuture.supplyAsync({
            PluginLlmProviders.snapshot().flatMap { it.models }
        }, Dispatchers.IO.asExecutor())
}

internal class AndroidPluginClipboard(
    private val context: Context
) : PluginClipboardApi {
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    override fun readText(): CompletableFuture<String> =
        CompletableFuture.supplyAsync({
            val clipboard = context.getSystemService(
                ClipboardManager::class.java
            )
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(context)
                ?.toString()
                .orEmpty()
                .take(PluginClipboardApi.MAX_TEXT_CHARS)
        }, mainExecutor)

    override fun hasText(): CompletableFuture<Boolean> =
        CompletableFuture.supplyAsync({
            context.getSystemService(ClipboardManager::class.java)
                .hasPrimaryClip()
        }, mainExecutor)

    override fun writeText(
        text: String?,
        sensitive: Boolean
    ): CompletableFuture<Void> = CompletableFuture.runAsync({
        val value = text.orEmpty()
        require(value.length <= PluginClipboardApi.MAX_TEXT_CHARS) {
            "clipboard text is too long"
        }
        val clip = ClipData.newPlainText("NestSpace plugin", value)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(clip)
    }, mainExecutor)
}

internal class AndroidPluginShare(
    private val context: Context,
    private val pluginId: String
) : PluginShareApi {
    private val ioExecutor = Dispatchers.IO.asExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    override fun share(
        request: PluginShareRequest?
    ): CompletableFuture<Void> {
        val value = requireNotNull(request) { "share request is required" }
        return CompletableFuture.runAsync({
            val uri = if (value.hasFile()) {
                val directory = File(
                    context.cacheDir,
                    "plugin_shares/${PluginFileStore.storageNamespace(pluginId)}"
                ).apply { mkdirs() }
                directory.listFiles()
                    ?.filter {
                        System.currentTimeMillis() - it.lastModified() >
                            86_400_000L
                    }
                    ?.forEach(File::delete)
                val file = File(
                    directory,
                    "${UUID.randomUUID()}_${safeName(value.fileName)}"
                )
                file.writeBytes(value.data)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file
                )
            } else {
                null
            }
            CompletableFuture.runAsync({
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = value.mimeType
                    putExtra(Intent.EXTRA_SUBJECT, value.title)
                    putExtra(Intent.EXTRA_TEXT, value.text)
                    if (uri != null) {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                context.startActivity(
                    Intent.createChooser(send, value.title)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }, mainExecutor).get(10, TimeUnit.SECONDS)
        }, ioExecutor)
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160)
            .ifBlank { "shared.bin" }
}

private fun threadFactory(prefix: String): ThreadFactory {
    val sequence = AtomicLong()
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
