package com.coke.nestspace.container.remote

import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
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

        override fun sendTargetBroadcast(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            intent: Intent?
        ) {
            val pkg = packageName ?: return
            val instance = instanceId ?: return
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return
            onMain { target.broadcastManager.deliverRemoteBroadcast(intent ?: Intent()) }
        }

        override fun queryTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Bundle? {
            val pkg = packageName ?: return null
            val instance = instanceId ?: return null
            val queryUri = uri ?: return null
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return null
            val result = onMain {
                target.queryProviderDirect(
                    queryUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )
            } ?: return null
            return Bundle().apply {
                putStringArray("columns", result.columns)
                putParcelable("window", result.window)
            }
        }

        override fun insertTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            values: ContentValues?
        ): android.net.Uri? = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.insertProviderDirect(queryUri, values)
        }

        override fun updateTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.updateProviderDirect(queryUri, values, selection, selectionArgs)
        } ?: 0

        override fun deleteTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.deleteProviderDirect(queryUri, selection, selectionArgs)
        } ?: 0

        override fun bulkInsertTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            values: Array<out ContentValues>?
        ): Int = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.bulkInsertProviderDirect(queryUri, values ?: emptyArray())
        } ?: 0

        override fun getTypeTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?
        ): String? = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.getProviderTypeDirect(queryUri)
        }

        override fun callTargetProvider(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            authority: String?,
            method: String?,
            arg: String?,
            extras: Bundle?
        ): Bundle? {
            val pkg = packageName ?: return null
            val instance = instanceId ?: return null
            val callAuthority = authority ?: return null
            val callMethod = method ?: return null
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return null
            return onMain {
                target.callProviderDirect(callAuthority, callMethod, arg, extras)
            }
        }

        override fun openTargetProviderFile(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            mode: String?
        ): ParcelFileDescriptor? = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.openProviderFileDirect(queryUri, mode ?: "r")
        }

        override fun openTargetProviderAssetFile(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            mode: String?
        ): AssetFileDescriptor? = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.openProviderAssetFileDirect(queryUri, mode ?: "r")
        }

        override fun openTargetProviderTypedAssetFile(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uri: android.net.Uri?,
            mimeTypeFilter: String?,
            opts: Bundle?
        ): AssetFileDescriptor? = providerSession(
            packageName,
            instanceId,
            targetProcess,
            uri
        ) { session, queryUri ->
            session.openProviderTypedAssetFileDirect(
                queryUri,
                mimeTypeFilter ?: "*/*",
                opts
            )
        }

        override fun notifyTargetContentChange(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            uris: Array<out android.net.Uri>?,
            flags: Int
        ) {
            val pkg = packageName ?: return
            val instance = instanceId ?: return
            val changed = uris?.filterNotNull()?.toTypedArray()
                ?.takeIf { it.isNotEmpty() } ?: return
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return
            onMain {
                target.deliverRemoteContentChange(changed, flags)
            }
        }

        override fun deliverTargetActivityIntent(
            packageName: String?,
            instanceId: String?,
            targetProcess: String?,
            activityClass: String?,
            intent: Intent?
        ): Boolean {
            val pkg = packageName ?: return false
            val instance = instanceId ?: return false
            val className = activityClass ?: return false
            val targetIntent = intent ?: return false
            val process = ContainerProcessRouter.normalize(pkg, targetProcess)
            val target = sessionFor(pkg, instance, process) ?: return false
            return onMain {
                target.deliverTopActivityIntent(className, targetIntent)
            }
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

    private fun <T> providerSession(
        packageName: String?,
        instanceId: String?,
        targetProcess: String?,
        providerUri: android.net.Uri?,
        block: (ContainerSession, android.net.Uri) -> T
    ): T? {
        val pkg = packageName ?: return null
        val instance = instanceId ?: return null
        val uri = providerUri ?: return null
        val process = ContainerProcessRouter.normalize(pkg, targetProcess)
        val target = sessionFor(pkg, instance, process) ?: return null
        return onMain { block(target, uri) }
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
