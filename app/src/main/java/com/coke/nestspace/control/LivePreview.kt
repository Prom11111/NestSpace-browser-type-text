package com.coke.nestspace.control

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
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
        val base64: String = "",
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
        base64: String,
        mimeType: String = "image/jpeg",
        slot: Int,
        label: String = ""
    ) {
        if (suppressed) return
        if (base64.isBlank() || slot < 0) return
        // A headless app that is starting or showing a launch ad can return
        // an almost blank frame while its window is in the back. Keep the
        // last meaningful picture instead of flashing a white card.
        if (isBlankFrame(base64)) return
        _frame.value = Frame(
            base64 = base64,
            mimeType = mimeType,
            slot = slot,
            label = label,
            capturedAt = System.currentTimeMillis(),
            kind = KIND_CONTAINER
        )
    }

    /** Cheap uniform-white detector for a downsampled container frame. */
    private fun isBlankFrame(base64: String): Boolean = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val sample = BitmapFactory.Options().apply {
            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 64).coerceAtLeast(1)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)
            ?: return false
        var light = true
        var min = 255
        var max = 0
        loop@ for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val value = (r + g + b) / 3
                if (value < min) min = value
                if (value > max) max = value
                if (r < 235 || g < 235 || b < 235) {
                    light = false
                    break@loop
                }
            }
        }
        bitmap.recycle()
        light && max - min < 8
    }.getOrDefault(false)

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
