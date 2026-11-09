package com.coke.nestspace.plugin

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class PluginFileOperationsActivity : ComponentActivity() {

    private var requestId: String? = null
    private var delivered = false

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = requestId
        val pending = id?.let(PluginFileOperationBridge::take)
        if (pending == null) {
            delivered = true
            finish()
            return@registerForActivityResult
        }
        delivered = true
        when (pending) {
            is PluginFileOperationBridge.Pick ->
                completePick(pending, result.resultCode, result.data)
            is PluginFileOperationBridge.Export ->
                completeExport(pending, result.resultCode, result.data)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        val pending = id?.let(PluginFileOperationBridge::peek)
        if (pending == null) {
            finish()
            return
        }
        requestId = id
        val pickerIntent = when (pending) {
            is PluginFileOperationBridge.Pick -> pickIntent(pending.request)
            is PluginFileOperationBridge.Export -> exportIntent(pending.request)
        }
        runCatching { picker.launch(pickerIntent) }.onFailure {
            PluginFileOperationBridge.cancel(id)
            delivered = true
            finish()
        }
    }

    override fun onDestroy() {
        requestId?.takeIf { !delivered }?.let(PluginFileOperationBridge::cancel)
        super.onDestroy()
    }

    private fun pickIntent(request: PluginFilePickerRequest): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when (request.mimeTypes.size) {
                1 -> request.mimeTypes.first()
                else -> "*/*"
            }
            if (request.mimeTypes.size > 1) {
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    request.mimeTypes.toTypedArray()
                )
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.isMultiple)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

    private fun exportIntent(request: PluginFileExportRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(
                Intent.EXTRA_TITLE,
                request.suggestedName.ifBlank {
                    "nestspace-plugin-export"
                }
            )
        }

    private fun completePick(
        pending: PluginFileOperationBridge.Pick,
        resultCode: Int,
        data: Intent?
    ) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            pending.result.complete(emptyList())
            return
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index)?.uri?.let(uris::add)
            }
        }
        data.data?.let(uris::add)
        val selected = uris.distinct()
            .take(PluginFilePickerRequest.MAX_FILES)
        selected.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        pending.result.complete(
            selected.map { AndroidPickedFile(applicationContext, it) }
        )
    }

    private fun completeExport(
        pending: PluginFileOperationBridge.Export,
        resultCode: Int,
        data: Intent?
    ) {
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            pending.result.completeExceptionally(
                IllegalStateException("File export was cancelled")
            )
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(pending.request.data)
                output.flush()
            } ?: error("Could not open the selected export file")
            pending.result.complete(
                PluginExportedFile(
                    displayName(uri).ifBlank {
                        pending.request.suggestedName
                    },
                    pending.request.mimeType,
                    pending.request.data.size.toLong()
                )
            )
        } catch (error: Throwable) {
            pending.result.completeExceptionally(error)
        }
    }

    private fun displayName(uri: Uri): String {
        var result = ""
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) result = cursor.getString(0).orEmpty()
            }
        }
        return result
    }

    companion object {
        const val EXTRA_REQUEST_ID = "plugin_file_request_id"
    }
}
