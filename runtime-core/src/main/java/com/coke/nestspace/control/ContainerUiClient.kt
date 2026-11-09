package com.coke.nestspace.control

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Main-process client for [ContainerUiService]. Binds to the service in the
 * container process hosting the requested package/instance, so tools can
 * control the target view hierarchy without touching system apps.
 */
object ContainerUiClient {

    private const val TAG = "ContainerUiClient"
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val IDLE_UNBIND_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindLocks = ConcurrentHashMap<Int, Mutex>()
    private val bindings = ConcurrentHashMap<Int, SlotBinding>()
    private val retainedSlots = ConcurrentHashMap.newKeySet<Int>()

    internal val serviceClasses = arrayOf(
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

    suspend fun screenshotJpeg(
        context: Context,
        slot: Int? = null,
        maxWidth: Int = 640,
        quality: Int = 58,
        rejectBlank: Boolean = false
    ): ByteArray? = withService(context, slot) {
        it.screenshotJpeg(maxWidth, quality, rejectBlank)
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

    suspend fun isMirrorMode(context: Context, slot: Int?): Boolean? =
        withService(context, slot) { it.isMirrorMode() }

    /**
     * Keep the process-backed UI service alive for a headless slot without
     * creating a second binding alongside normal tool calls.
     */
    fun retainConnection(context: Context, slot: Int) {
        if (slot !in serviceClasses.indices || !retainedSlots.add(slot)) return
        scope.launch {
            val binding = acquireBinding(context.applicationContext, slot)
            if (binding == null) {
                retainedSlots.remove(slot)
                return@launch
            }
            binding.setRetained(true)
            binding.releaseLease()
        }
    }

    fun releaseRetainedConnection(context: Context, slot: Int) {
        if (!retainedSlots.remove(slot)) return
        scope.launch {
            bindings[slot]?.setRetained(false)
        }
    }

    fun releaseAllRetainedConnections(context: Context) {
        retainedSlots.toList().forEach { releaseRetainedConnection(context, it) }
    }

    internal fun isConnectionRetained(slot: Int): Boolean = slot in retainedSlots

    private suspend fun <T> withService(
        context: Context,
        slotHint: Int?,
        timeoutMs: Long = BIND_TIMEOUT_MS,
        block: (IContainerUi) -> T
    ): T? = withTimeoutOrNull(timeoutMs) {
        val slot = slotHint ?: ContainerProcessPool.lastSlot() ?: return@withTimeoutOrNull null
        if (slot !in serviceClasses.indices) return@withTimeoutOrNull null

        var attempt = 0
        while (attempt < 2) {
            val binding = acquireBinding(context.applicationContext, slot)
                ?: return@withTimeoutOrNull null
            val binder = binding.binder
            if (binder == null) {
                binding.close("binder unavailable")
                attempt += 1
                continue
            }
            try {
                return@withTimeoutOrNull withContext(Dispatchers.IO) { block(binder) }
            } catch (error: DeadObjectException) {
                binding.close("dead object")
                attempt += 1
            } catch (error: RemoteException) {
                binding.close("remote failure")
                attempt += 1
            } finally {
                binding.releaseLease()
            }
        }
        null
    }

    private suspend fun acquireBinding(context: Context, slot: Int): SlotBinding? {
        val existing = bindings[slot]
        if (existing?.tryAcquire() != null) {
            if (slot in retainedSlots) existing.setRetained(true)
            return existing
        }

        val lock = bindLocks.computeIfAbsent(slot) { Mutex() }
        return lock.withLock {
            val current = bindings[slot]
            if (current?.tryAcquire() != null) {
                if (slot in retainedSlots) current.setRetained(true)
                return@withLock current
            }
            current?.close("reconnect")

            val created = SlotBinding(
                context = context,
                slot = slot,
                scope = scope,
                idleMs = IDLE_UNBIND_MS,
                onClosed = { binding, reason ->
                    bindings.remove(slot, binding)
                    runCatching { context.unbindService(binding) }
                    Log.d(TAG, "slot $slot binding closed: $reason")
                }
            )
            bindings[slot] = created
            try {
                created.connect()
                if (created.tryAcquire() != null) {
                    if (slot in retainedSlots) created.setRetained(true)
                    created
                } else {
                    created.close("connect failed")
                    null
                }
            } catch (error: Throwable) {
                created.close("connect error: ${error.javaClass.simpleName}")
                throw error
            }
        }
    }

    private class SlotBinding(
        private val context: Context,
        val slot: Int,
        private val scope: CoroutineScope,
        private val idleMs: Long,
        private val onClosed: (SlotBinding, String) -> Unit
    ) : ServiceConnection {

        private val ready = CompletableDeferred<IContainerUi?>()
        private val stateLock = Any()

        @Volatile
        var binder: IContainerUi? = null
            private set

        private var calls = 0
        private var retained = false
        private var closed = false
        private var idleJob: Job? = null

        suspend fun connect() {
            val bound = withContext(Dispatchers.Main.immediate) {
                runCatching {
                    context.bindService(
                        Intent(context, serviceClasses[slot]),
                        this@SlotBinding,
                        Context.BIND_AUTO_CREATE
                    )
                }.getOrDefault(false)
            }
            if (!bound) {
                ready.complete(null)
                return
            }
            ready.await()
        }

        fun tryAcquire(): IContainerUi? = synchronized(stateLock) {
            val current = binder
            if (closed || current == null) {
                null
            } else {
                calls += 1
                idleJob?.cancel()
                idleJob = null
                current
            }
        }

        fun releaseLease() {
            val shouldSchedule = synchronized(stateLock) {
                if (calls > 0) calls -= 1
                calls == 0 && !retained && !closed
            }
            if (shouldSchedule) scheduleIdleClose()
        }

        fun setRetained(value: Boolean) {
            val shouldSchedule = synchronized(stateLock) {
                retained = value
                if (value) {
                    idleJob?.cancel()
                    idleJob = null
                }
                !value && calls == 0 && !closed
            }
            if (shouldSchedule) scheduleIdleClose()
        }

        private fun scheduleIdleClose() {
            synchronized(stateLock) {
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(idleMs)
                    closeIfIdle("idle")
                }
            }
        }

        private fun closeIfIdle(reason: String) {
            val shouldClose = synchronized(stateLock) {
                calls == 0 && !retained && !closed
            }
            if (shouldClose) close(reason)
        }

        fun close(reason: String) {
            val shouldClose = synchronized(stateLock) {
                if (closed) {
                    false
                } else {
                    closed = true
                    binder = null
                    idleJob?.cancel()
                    idleJob = null
                    ready.complete(null)
                    true
                }
            }
            if (shouldClose) onClosed(this, reason)
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val value = service?.let { IContainerUi.Stub.asInterface(it) }
            binder = value
            ready.complete(value)
            if (value == null) {
                close("null binder")
            } else {
                Log.d(TAG, "slot $slot connected")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            close("service disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            close("binding died")
        }

        override fun onNullBinding(name: ComponentName?) {
            close("null binding")
        }
    }
}
