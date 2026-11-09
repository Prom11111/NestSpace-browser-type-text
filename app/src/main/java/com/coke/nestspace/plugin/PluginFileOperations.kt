package com.coke.nestspace.plugin

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal class AndroidPluginFiles(
    private val context: Context
) : PluginFileApi {

    override fun pickFiles(
        request: PluginFilePickerRequest?
    ): CompletableFuture<List<PluginPickedFile>> =
        PluginFileOperationBridge.startPick(
            context.applicationContext,
            requireNotNull(request) { "file picker request is required" }
        )

    override fun export(
        request: PluginFileExportRequest?
    ): CompletableFuture<PluginExportedFile> =
        PluginFileOperationBridge.startExport(
            context.applicationContext,
            requireNotNull(request) { "file export request is required" }
        )
}

internal object PluginFileOperationBridge {
    internal sealed interface Pending {
        val id: String
    }

    internal data class Pick(
        override val id: String,
        val request: PluginFilePickerRequest,
        val result: CompletableFuture<List<PluginPickedFile>>
    ) : Pending

    internal data class Export(
        override val id: String,
        val request: PluginFileExportRequest,
        val result: CompletableFuture<PluginExportedFile>
    ) : Pending

    private val pending = ConcurrentHashMap<String, Pending>()

    fun startPick(
        context: Context,
        request: PluginFilePickerRequest
    ): CompletableFuture<List<PluginPickedFile>> {
        val item = Pick(UUID.randomUUID().toString(), request, CompletableFuture())
        pending[item.id] = item
        launch(context, item.id)
        return item.result
    }

    fun startExport(
        context: Context,
        request: PluginFileExportRequest
    ): CompletableFuture<PluginExportedFile> {
        val item = Export(UUID.randomUUID().toString(), request, CompletableFuture())
        pending[item.id] = item
        launch(context, item.id)
        return item.result
    }

    fun peek(id: String): Pending? = pending[id]

    fun take(id: String): Pending? = pending.remove(id)

    fun cancel(id: String) {
        when (val item = take(id)) {
            is Pick -> item.result.complete(emptyList())
            is Export -> item.result.completeExceptionally(
                IllegalStateException("File export was cancelled")
            )
            null -> Unit
        }
    }

    private fun launch(context: Context, id: String) {
        runCatching {
            context.startActivity(
                Intent(context, PluginFileOperationsActivity::class.java)
                    .putExtra(PluginFileOperationsActivity.EXTRA_REQUEST_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            cancel(id)
        }
    }
}

internal class AndroidPickedFile(
    private val context: Context,
    private val uri: Uri
) : PluginPickedFile {
    private val resolver: ContentResolver = context.contentResolver
    private val executor = Dispatchers.IO.asExecutor()
    private val metadata: Pair<String, Long> by lazy { queryMetadata() }

    override fun getDisplayName(): String = metadata.first

    override fun getMimeType(): String =
        resolver.getType(uri).orEmpty()

    override fun getSizeBytes(): Long = metadata.second

    override fun readBytes(maxBytes: Int): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync({
            require(maxBytes > 0) { "maxBytes must be positive" }
            val output = ByteArrayOutputStream()
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "picked file exceeds read limit" }
                    output.write(buffer, 0, count)
                }
            } ?: error("picked file could not be opened")
            output.toByteArray()
        }, executor)

    private fun queryMetadata(): Pair<String, Long> {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0).orEmpty().ifBlank { name }
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        return name.ifBlank { "selected-file" } to size.coerceAtLeast(0)
    }
}
