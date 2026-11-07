package com.coke.nestspace.container.remote

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.IContainerBroker
import com.coke.nestspace.container.identity.HostIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lives in the main NestSpace process. Slot processes ask it for a dedicated
 * host process for a target subprocess (android:process). Only one process
 * owns eviction, so slot processes never allocate from their local pools.
 */
class ContainerProcessBrokerService : Service() {

    private val binder = object : IContainerBroker.Stub() {
        override fun acquireProcessSlot(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            callerSlot: Int
        ): Int {
            val pkg = packageName ?: return -1
            val instance = instanceId ?: return -1
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val key = ContainerProcessRouter.slotKey(pkg, instance, process)
            val callerPid = Binder.getCallingPid()
            val resolvedCallerSlot = ContainerProcessPool
                .slotForPid(applicationContext, callerPid)
                ?: callerSlot
            Log.d(
                TAG,
                "acquire $process callerPid=$callerPid " +
                    "callerSlot=$callerSlot resolved=$resolvedCallerSlot"
            )
            return runCatching {
                ContainerProcessPool.acquireProcess(
                    applicationContext,
                    key,
                    resolvedCallerSlot
                )
            }.onFailure {
                Log.w(TAG, "acquire $process failed", it)
            }.getOrDefault(-1)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "ContainerProcessBroker"
    }
}

/**
 * Resolves a host slot for a target subprocess.
 *
 * Allocation must be centralized in the main process: its pool owns eviction
 * and may kill a slot process to reuse it. Slot processes therefore bind this
 * broker service instead of touching their local pool.
 *
 * The blocking [acquire] entry point waits for a binder reply; callers must be
 * on a worker thread. [acquireAsync] delivers its result on the main thread
 * and is safe to call from any thread, including a slot's main thread.
 */
object ContainerProcessBrokerClient {

    private const val TAG = "ContainerBrokerClient"
    // The slot's main looper delivers the broker connection. During target
    // startup that looper can be busy for several seconds with native init.
    private const val TIMEOUT_SECONDS = 30L

    private val io = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val directExecutor = java.util.concurrent.Executor { it.run() }
    private val lock = Any()
    private var service: IContainerBroker? = null
    private var serviceConnection: ServiceConnection? = null

    /** Non-blocking; [callback] runs on the main thread. */
    fun acquireAsync(
        context: Context,
        packageName: String,
        instanceId: String,
        targetProcess: String?,
        callback: (Int?) -> Unit
    ) {
        val app = context.applicationContext
        io.execute {
            val slot = acquire(app, packageName, instanceId, targetProcess)
            mainHandler.post { callback(slot) }
        }
    }

    /**
     * Blocking resolution. Must not run on a slot process's main thread: the
     * broker reply is delivered by that same looper.
     */
    fun acquire(
        context: Context,
        packageName: String,
        instanceId: String,
        targetProcess: String?
    ): Int? {
        val normalized = ContainerProcessRouter.normalize(packageName, targetProcess)

        // Do not shortcut on the persisted slot mapping: the process in that
        // slot may still be hosting another app (or a stale session after a
        // host restart). The main-process pool kills and reassigns it.
        if (ContainerProcessPool.currentSlot() < 0) {
            // Main process: allocation is local and cheap.
            return runCatching {
                ContainerProcessPool.acquireProcess(
                    context.applicationContext,
                    ContainerProcessRouter.slotKey(packageName, instanceId, normalized)
                )
            }.onFailure {
                Log.w(TAG, "direct acquire $normalized failed", it)
            }.getOrNull()
        }

        val broker = broker(context) ?: return null
        return runCatching {
            broker.acquireProcessSlot(
                packageName,
                instanceId,
                normalized,
                ContainerProcessPool.currentSlot()
            )
        }.onFailure {
            Log.w(TAG, "broker acquire $normalized failed", it)
            synchronized(lock) { if (service === broker) service = null }
        }.getOrNull()?.takeIf { it in 0 until ContainerProcessPool.SLOT_COUNT }
    }

    private fun broker(context: Context): IContainerBroker? {
        synchronized(lock) { service?.let { return it } }
        if (Looper.myLooper() == Looper.getMainLooper() &&
            ContainerProcessPool.currentSlot() >= 0
        ) {
            Log.w(TAG, "broker acquire requested on slot main thread")
            return null
        }
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val remote = IContainerBroker.Stub.asInterface(binder)
                // The awaiting worker holds no lock; never take one that it
                // may be holding while it waits on the latch.
                synchronized(lock) { service = remote }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(lock) { service = null }
            }
        }
        // Context identity may be rewritten to the target app; the host
        // component must always use the real host package.
        val intent = Intent().setClassName(
            HostIdentity.packageName,
            ContainerProcessBrokerService::class.java.name
        )
        val ok = runCatching {
            // Deliver onServiceConnected on this worker instead of the
            // target's main looper: during target startup that looper can
            // be busy for tens of seconds.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.bindService(intent, Context.BIND_AUTO_CREATE, directExecutor, connection)
            } else {
                app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }.onFailure {
            Log.w(
                TAG,
                "bind broker service failed app=${app.javaClass.name} " +
                    "pkg=${app.packageName}",
                it
            )
        }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "bind broker service returned false")
            return null
        }
        serviceConnection = connection
        val connected = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!connected) {
            Log.w(TAG, "broker service connect timeout")
        }
        // Even after a timeout the callback may have populated the binding.
        return synchronized(lock) { service }
    }
}
