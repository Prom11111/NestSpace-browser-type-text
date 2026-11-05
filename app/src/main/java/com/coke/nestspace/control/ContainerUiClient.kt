package com.coke.nestspace.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Base64
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerUiService1
import com.coke.nestspace.container.ContainerUiService2
import com.coke.nestspace.container.ContainerUiService3
import com.coke.nestspace.container.ContainerUiService4
import com.coke.nestspace.container.ContainerUiService5
import com.coke.nestspace.container.ContainerUiService6
import com.coke.nestspace.container.ContainerUiService7
import com.coke.nestspace.container.ContainerUiService8
import com.coke.nestspace.container.ContainerUiService9
import com.coke.nestspace.container.ContainerUiService10
import com.coke.nestspace.container.ContainerUiService11
import com.coke.nestspace.container.ContainerUiService12
import com.coke.nestspace.container.ContainerUiService13
import com.coke.nestspace.container.ContainerUiService14
import com.coke.nestspace.container.ContainerUiService15
import com.coke.nestspace.container.ContainerUiService16
import com.coke.nestspace.container.IContainerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Main-process client for [ContainerUiService]. Binds to the service in the
 * container process hosting the requested package/instance, so tools can
 * control the target view hierarchy without touching system apps.
 */
object ContainerUiClient {

    private const val BIND_TIMEOUT_MS = 5_000L

    private val serviceClasses = arrayOf(
        ContainerUiService1::class.java,
        ContainerUiService2::class.java,
        ContainerUiService3::class.java,
        ContainerUiService4::class.java,
        ContainerUiService5::class.java,
        ContainerUiService6::class.java,
        ContainerUiService7::class.java,
        ContainerUiService8::class.java,
        ContainerUiService9::class.java,
        ContainerUiService10::class.java,
        ContainerUiService11::class.java,
        ContainerUiService12::class.java,
        ContainerUiService13::class.java,
        ContainerUiService14::class.java,
        ContainerUiService15::class.java,
        ContainerUiService16::class.java
    )

    suspend fun observeJson(context: Context, slot: Int? = null): String? =
        withService(context, slot) { it.observeJson() }

    suspend fun screenshotJpegBase64(context: Context, slot: Int? = null): String? =
        withService(context, slot) { it.screenshotJpegBase64() }

    suspend fun screenshotBase64(context: Context, slot: Int? = null): String? =
        withService(context, slot) { binder ->
            val path = binder.screenshotPngPath()
            if (path.isNullOrBlank()) {
                null
            } else {
                val file = File(path)
                if (file.isFile) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                } else {
                    null
                }
            }
        }

    suspend fun tapNode(context: Context, slot: Int?, id: String): Boolean? =
        withService(context, slot) { it.tapNode(id) }

    suspend fun tapText(context: Context, slot: Int?, text: String): Boolean? =
        withService(context, slot) { it.tapText(text) }

    suspend fun tapAt(context: Context, slot: Int?, x: Int, y: Int): Boolean? =
        withService(context, slot) { it.tapAt(x, y) }

    suspend fun setText(context: Context, slot: Int?, id: String, text: String): Boolean? =
        withService(context, slot) { it.setText(id, text) }

    suspend fun bringToFront(context: Context, slot: Int? = null): Boolean? =
        withService(context, slot) { it.bringToFront() }

    suspend fun waitForText(context: Context, slot: Int?, text: String, timeoutMs: Long): Boolean? =
        withService(
            context,
            slot,
            // The binder call itself polls for timeoutMs; leave room for the
            // service bind so a long wait is not mistaken for a dead container.
            timeoutMs = timeoutMs.coerceAtLeast(0L) + BIND_TIMEOUT_MS
        ) { it.waitForText(text, timeoutMs) }

    suspend fun pressBack(context: Context, slot: Int?): Boolean? =
        withService(context, slot) { it.pressBack() }

    private suspend fun <T> withService(
        context: Context,
        slotHint: Int?,
        timeoutMs: Long = BIND_TIMEOUT_MS,
        block: (IContainerUi) -> T
    ): T? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main.immediate) {
            val slot = slotHint ?: ContainerProcessPool.lastSlot() ?: return@withContext null
            if (slot !in serviceClasses.indices) return@withContext null
            var connection: ServiceConnection? = null
            try {
                val binder = suspendCancellableCoroutine<IContainerUi?> { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            continuation.resume(service?.let { IContainerUi.Stub.asInterface(it) })
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                    connection = conn
                    val bound = runCatching {
                        context.bindService(
                            Intent(context, serviceClasses[slot]),
                            conn,
                            Context.BIND_AUTO_CREATE
                        )
                    }.getOrDefault(false)
                    if (!bound) continuation.resume(null)
                } ?: return@withContext null
                withContext(Dispatchers.IO) { block(binder) }
            } finally {
                connection?.let { runCatching { context.unbindService(it) } }
            }
        }
    }
}
