package com.coke.nestspace.control

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes and coalesces live-preview captures per container slot.
 *
 * Tool actions can complete in bursts while the Agent screen also polls for a
 * new frame. Only one capture per slot may run at a time, and fire-and-forget
 * requests are throttled without delaying the tool result itself.
 */
object ContainerPreviewCapture {

    private const val TAG = "ContainerPreviewCapture"
    private const val MIN_REQUEST_INTERVAL_MS = 600L
    private const val PREVIEW_MAX_WIDTH = 640
    private const val PREVIEW_JPEG_QUALITY = 58

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = ConcurrentHashMap<Int, Mutex>()
    private val lastRequestAt = ConcurrentHashMap<Int, Long>()

    fun request(context: Context, slot: Int, label: String) {
        if (slot < 0) return
        val now = SystemClock.elapsedRealtime()
        val accepted = lastRequestAt.compute(slot) { _, previous ->
            if (previous == null || now - previous >= MIN_REQUEST_INTERVAL_MS) now else previous
        } == now
        if (!accepted) return

        scope.launch {
            val captured = try {
                capture(
                    context = context,
                    slot = slot,
                    label = label,
                    rejectBlank = true
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "preview capture failed for slot=$slot", error)
                null
            }
            if (captured == null) {
                lastRequestAt.remove(slot, now)
            }
        }
    }

    suspend fun capture(
        context: Context,
        slot: Int,
        label: String,
        maxWidth: Int = PREVIEW_MAX_WIDTH,
        quality: Int = PREVIEW_JPEG_QUALITY,
        rejectBlank: Boolean = true
    ): ByteArray? {
        if (slot < 0) return null
        val lock = locks.computeIfAbsent(slot) { Mutex() }
        return lock.withLock {
            val jpeg = withContext(Dispatchers.IO) {
                ContainerUiClient.screenshotJpeg(
                    context = context,
                    slot = slot,
                    maxWidth = maxWidth,
                    quality = quality,
                    rejectBlank = rejectBlank
                )
            } ?: return@withLock null
            LivePreview.publish(
                jpegBytes = jpeg,
                mimeType = "image/jpeg",
                slot = slot,
                label = label
            )
            jpeg
        }
    }
}
