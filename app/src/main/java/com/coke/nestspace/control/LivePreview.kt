package com.coke.nestspace.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Latest container screenshot for the assistant's live preview.
 *
 * Tool calls publish after observing, tapping or typing; the assistant screen
 * keeps refreshing the same slot while a run is active. Only the newest frame
 * is retained, so the preview does not grow with conversation history.
 */
object LivePreview {

    data class Frame(
        val base64: String,
        val mimeType: String,
        val slot: Int,
        val label: String,
        val capturedAt: Long
    )

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame

    fun publish(
        base64: String,
        mimeType: String = "image/jpeg",
        slot: Int,
        label: String = ""
    ) {
        if (base64.isBlank() || slot < 0) return
        _frame.value = Frame(
            base64 = base64,
            mimeType = mimeType,
            slot = slot,
            label = label,
            capturedAt = System.currentTimeMillis()
        )
    }

    fun clear() {
        _frame.value = null
    }
}
