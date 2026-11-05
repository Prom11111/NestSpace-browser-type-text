package com.coke.nestspace.container.remote

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.ContainerSession
import com.coke.nestspace.container.ContainerSessions
import com.coke.nestspace.container.ContainerSpecFactory
import com.coke.nestspace.container.IContainerRemoteService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hosts one target subprocess in a dedicated slot process.
 *
 * Only Service components are bridged here initially. Activities declared in a
 * subprocess are launched directly as StageA hosts in the same slot (see
 * ContainerSession.startTargetActivity), and share this process identity.
 */
abstract class ContainerRemoteService : Service() {

    protected abstract val slot: Int

    private val mainHandler = Handler(Looper.getMainLooper())
    private var session: ContainerSession? = null

    private val binder = object : IContainerRemoteService.Stub() {
        override fun bindTargetService(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            intent: Intent?
        ): IBinder? {
            val pkg = packageName ?: return null
            val instance = instanceId ?: return null
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return null
            return onMain { target.bindServiceDirect(intent ?: Intent()) }
        }

        override fun unbindTargetService(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            intent: Intent?
        ) {
            val pkg = packageName ?: return
            val instance = instanceId ?: return
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return
            onMain { target.unbindServiceDirect(intent ?: Intent()) }
        }

        override fun startTargetService(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            intent: Intent?,
            foreground: Boolean
        ): ComponentName? {
            val pkg = packageName ?: return null
            val instance = instanceId ?: return null
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return null
            return onMain { target.startServiceDirect(intent ?: Intent(), foreground) }
        }

        override fun stopTargetService(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            intent: Intent?
        ) {
            val pkg = packageName ?: return
            val instance = instanceId ?: return
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return
            onMain { target.stopServiceDirect(intent ?: Intent()) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContainerProcessPool.markCurrentSlot(slot)
        ContainerProcessPool.markSlotProcess(this, slot)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun sessionFor(
        packageName: String,
        instanceId: String,
        targetProcess: String
    ): ContainerSession? = onMain {
        session?.let { return@onMain it }
        val sessionId = ContainerProcessRouter.sessionId(packageName, instanceId, targetProcess)
        ContainerSessions.retain(sessionId)?.also {
            session = it
            return@onMain it
        }
        runCatching {
            val spec = ContainerSpecFactory.build(
                this,
                packageName,
                instanceId,
                sessionId,
                targetProcess
            )
            ContainerSessions.acquire(this, spec).also { session = it }
        }.onFailure {
            Log.w(TAG, "create remote session $sessionId failed", it)
        }.getOrNull()
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw IllegalStateException("remote service main-thread timeout")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val TAG = "ContainerRemoteService"
    }
}

class ContainerRemoteService1 : ContainerRemoteService() {
    override val slot: Int = 0
}

class ContainerRemoteService2 : ContainerRemoteService() {
    override val slot: Int = 1
}

class ContainerRemoteService3 : ContainerRemoteService() {
    override val slot: Int = 2
}

class ContainerRemoteService4 : ContainerRemoteService() {
    override val slot: Int = 3
}

class ContainerRemoteService5 : ContainerRemoteService() {
    override val slot: Int = 4
}

class ContainerRemoteService6 : ContainerRemoteService() {
    override val slot: Int = 5
}

class ContainerRemoteService7 : ContainerRemoteService() {
    override val slot: Int = 6
}

class ContainerRemoteService8 : ContainerRemoteService() {
    override val slot: Int = 7
}
class ContainerRemoteService9 : ContainerRemoteService() {
    override val slot: Int = 8
}

class ContainerRemoteService10 : ContainerRemoteService() {
    override val slot: Int = 9
}

class ContainerRemoteService11 : ContainerRemoteService() {
    override val slot: Int = 10
}

class ContainerRemoteService12 : ContainerRemoteService() {
    override val slot: Int = 11
}

class ContainerRemoteService13 : ContainerRemoteService() {
    override val slot: Int = 12
}

class ContainerRemoteService14 : ContainerRemoteService() {
    override val slot: Int = 13
}

class ContainerRemoteService15 : ContainerRemoteService() {
    override val slot: Int = 14
}

class ContainerRemoteService16 : ContainerRemoteService() {
    override val slot: Int = 15
}


object ContainerRemoteServices {
    private val classes = arrayOf(
        ContainerRemoteService1::class.java,
        ContainerRemoteService2::class.java,
        ContainerRemoteService3::class.java,
        ContainerRemoteService4::class.java,
        ContainerRemoteService5::class.java,
        ContainerRemoteService6::class.java,
        ContainerRemoteService7::class.java,
        ContainerRemoteService8::class.java,
        ContainerRemoteService9::class.java,
        ContainerRemoteService10::class.java,
        ContainerRemoteService11::class.java,
        ContainerRemoteService12::class.java,
        ContainerRemoteService13::class.java,
        ContainerRemoteService14::class.java,
        ContainerRemoteService15::class.java,
        ContainerRemoteService16::class.java
    )

    fun classForSlot(slot: Int): Class<*> = classes[slot.coerceIn(0, classes.lastIndex)]

    fun componentFor(context: android.content.Context, slot: Int): ComponentName =
        ComponentName(context.packageName, classForSlot(slot).name)
}
