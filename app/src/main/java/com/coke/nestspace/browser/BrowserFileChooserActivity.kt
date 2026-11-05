package com.coke.nestspace.browser

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline that turns a WebView file chooser into the system
 * document picker. Keeping this in a small Activity lets headless browser
 * tabs upload files without replacing the browser's own Activity.
 */
class BrowserFileChooserActivity : ComponentActivity() {

    private var delivered = false

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        delivered = true
        result.data?.let(::takeReadPermissions)
        BrowserSession.completeFileChooser(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BrowserSession.hasPendingFileChooser()) {
            finish()
            return
        }
        val pickerIntent = buildPickerIntent()
        try {
            picker.launch(pickerIntent)
        } catch (_: ActivityNotFoundException) {
            delivered = true
            BrowserSession.completeFileChooser(Activity.RESULT_CANCELED, null)
            finish()
        } catch (_: Throwable) {
            delivered = true
            BrowserSession.completeFileChooser(Activity.RESULT_CANCELED, null)
            finish()
        }
    }

    override fun onDestroy() {
        if (!delivered && !isChangingConfigurations) {
            BrowserSession.completeFileChooser(Activity.RESULT_CANCELED, null)
        }
        super.onDestroy()
    }

    /**
     * The picker grants a read permission to this short-lived Activity. Persist
     * it before finishing, otherwise a headless WebView can receive the URI
     * but fail with NotReadableError after the proxy Activity dies.
     */
    private fun takeReadPermissions(data: Intent) {
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let(uris::add)
            }
        }
        data.data?.let { uris += it }
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun buildPickerIntent(): Intent {
        val acceptTypes = intent.getStringArrayExtra(EXTRA_ACCEPT_TYPES).orEmpty()
        val mode = intent.getIntExtra(EXTRA_MODE, FileChooserParams.MODE_OPEN)
        val mimeTypes = acceptTypes.mapNotNull(::normalizeMimeType).distinct()
        val save = mode == FileChooserParams.MODE_SAVE
        val action = if (save) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT
        return Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                mimeTypes.isEmpty() -> "*/*"
                mimeTypes.size == 1 -> mimeTypes.first()
                else -> "*/*"
            }
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            if (mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            if (save) {
                putExtra(Intent.EXTRA_TITLE, "upload")
            }
        }
    }

    private fun normalizeMimeType(value: String): String? {
        val text = value.trim().lowercase()
        if (text.isBlank()) return null
        if (text.contains('/')) return text
        if (text.startsWith('.')) {
            return MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(text.removePrefix("."))
        }
        return null
    }

    companion object {
        const val EXTRA_ACCEPT_TYPES = "browser_accept_types"
        const val EXTRA_MODE = "browser_chooser_mode"
    }
}
