package com.coke.nestspace.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Latest live preview shown next to the assistant conversation.
 *
 * Container frames are screenshots of the virtual app. Browser frames are
 * rendered by a small in-app WebView, so the card can show just the page body
 * without any browser chrome. Only the newest frame is retained.
 */
object LivePreview {

    const val KIND_CONTAINER = "container"
    const val KIND_BROWSER = "browser"

    data class Frame(
        val jpegBytes: ByteArray = ByteArray(0),
        val mimeType: String = "image/jpeg",
        val slot: Int = -1,
        val label: String = "",
        val capturedAt: Long = 0L,
        val kind: String = KIND_CONTAINER,
        val url: String = "",
        val title: String = ""
    )

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame

    @Volatile
    private var suppressed = false

    /** Hide the screenshot card while the real app is shown in the mirror. */
    fun setSuppressed(value: Boolean) {
        suppressed = value
        if (value && _frame.value?.kind == KIND_CONTAINER) {
            _frame.value = null
        }
    }

    fun publish(
        jpegBytes: ByteArray,
        mimeType: String = "image/jpeg",
        slot: Int,
        label: String = ""
    ) {
        if (suppressed) return
        if (jpegBytes.isEmpty() || slot < 0) return
        _frame.value = Frame(
            jpegBytes = jpegBytes,
            mimeType = mimeType,
            slot = slot,
            label = label,
            capturedAt = System.currentTimeMillis(),
            kind = KIND_CONTAINER
        )
    }

    fun publishBrowser(url: String, title: String = "", label: String = "") {
        if (url.isBlank()) return
        _frame.value = Frame(
            label = label.ifBlank { title.ifBlank { url } },
            capturedAt = System.currentTimeMillis(),
            kind = KIND_BROWSER,
            url = url,
            title = title
        )
    }

    fun clear() {
        _frame.value = null
    }
}
