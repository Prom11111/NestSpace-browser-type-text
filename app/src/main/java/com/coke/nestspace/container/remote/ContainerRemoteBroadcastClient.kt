package com.coke.nestspace.container.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.ContainerSession
import com.coke.nestspace.container.IContainerRemoteService
import com.coke.nestspace.container.identity.HostIdentity
import java.util.concurrent.Executors

/** Fire-and-forget delivery of a manifest broadcast to a target subprocess. */
object ContainerRemoteBroadcastClient {
    private const val TAG = "ContainerRemoteBroadcast"
    private val io = Executors.newCachedThreadPool()
    private val directExecutor = java.util.concurrent.Executor { it.run() }

    fun send(session: ContainerSession, targetProcess: String, intent: Intent) {
        val normalized = ContainerProcessRouter.normalize(
            session.spec.packageName,
            targetProcess
        )
        if (normalized == session.spec.targetProcessName) {
            session.broadcastManager.deliverRemoteBroadcast(intent)
            return
        }
        ContainerProcessBrokerClient.acquireAsync(
            session.hostContext,
            session.spec.packageName,
            session.spec.instanceId,
            normalized
        ) { slot ->
            if (slot == null) {
                Log.w(TAG, "no slot for $normalized")
                return@acquireAsync
            }
            bindAndSend(session, normalized, slot, intent)
        }
    }

    private fun bindAndSend(
        session: ContainerSession,
        targetProcess: String,
        slot: Int,
        intent: Intent
    ) {
        val hostIntent = Intent().setClassName(
            HostIdentity.packageName,
            ContainerRemoteServices.classForSlot(slot).name
        )
        var connection: ServiceConnection? = null
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val remote = IContainerRemoteService.Stub.asInterface(binder) ?: return
                io.execute {
                    runCatching {
                        remote.sendTargetBroadcast(
                            session.spec.packageName,
                            session.spec.instanceId,
                            targetProcess,
                            intent
                        )
                    }.onFailure {
                        Log.w(TAG, "remote broadcast failed process=$targetProcess", it)
                    }
                    connection?.let {
                        runCatching { session.hostContext.unbindService(it) }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onBindingDied(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) = Unit
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                session.hostContext.bindService(
                    hostIntent,
                    Context.BIND_AUTO_CREATE,
                    directExecutor,
                    connection
                )
            } else {
                @Suppress("DEPRECATION")
                session.hostContext.bindService(
                    hostIntent,
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }.onFailure {
            Log.w(TAG, "bind remote broadcast failed slot=$slot", it)
        }.getOrDefault(false)
        if (!ok) {
            connection = null
        }
    }
}
