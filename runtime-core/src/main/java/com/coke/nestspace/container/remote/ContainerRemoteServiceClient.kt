package com.coke.nestspace.container.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.ContainerSession
import com.coke.nestspace.container.IContainerRemoteService
import com.coke.nestspace.container.identity.HostIdentity
import java.util.concurrent.Executors

/**
 * Client side of the cross-process Service bridge.
 *
 * The calling slot binds a host ContainerRemoteServiceN in the slot that owns
 * the target android:process. The remote side creates the target
 * Application/session and returns the target Service's own IBinder.
 *
 * Slot resolution is asynchronous (it may need the main-process broker), so
 * bind/start return before the host binding exists. This matches Android,
 * where ServiceConnection callbacks are delivered later on the main thread.
 */
object ContainerRemoteServiceClient {

    private const val TAG = "ContainerRemoteClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val bindings = HashMap<ServiceConnection, RemoteBinding>()
    private val started = HashMap<String, StartedService>()

    private class RemoteBinding(
        val session: ContainerSession,
        val info: ServiceInfo,
        val intent: Intent,
        val connection: ServiceConnection,
        val targetProcess: String
    ) {
        var hostIntent: Intent? = null
        var hostConnection: ServiceConnection? = null
        var remote: IContainerRemoteService? = null
        @Volatile
        var released = false
        var connected = false
    }

    private class StartedService(
        val session: ContainerSession,
        val info: ServiceInfo,
        val targetProcess: String
    ) {
        @Volatile
        var remote: IContainerRemoteService? = null
        @Volatile
        var stopped = false
        var hostConnection: ServiceConnection? = null
        val pending = mutableListOf<Pair<Intent, Boolean>>()
    }

    fun bind(
        session: ContainerSession,
        info: ServiceInfo,
        intent: Intent,
        connection: ServiceConnection
    ): Boolean {
        val process = ContainerProcessRouter.normalize(session.spec.packageName, info.processName)
        val binding = RemoteBinding(session, info, Intent(intent), connection, process)
        synchronized(bindings) { bindings[connection] = binding }
        ContainerProcessBrokerClient.acquireAsync(
            session.hostContext,
            session.spec.packageName,
            session.spec.instanceId,
            process
        ) { slot ->
            if (binding.released) return@acquireAsync
            if (slot == null) {
                Log.w(TAG, "no slot for ${info.name} process=$process")
                fail(binding)
            } else {
                bindHost(binding, slot)
            }
        }
        return true
    }

    private fun bindHost(binding: RemoteBinding, slot: Int) {
        val hostIntent = Intent().setClassName(
            HostIdentity.packageName,
            ContainerRemoteServices.classForSlot(slot).name
        )
        val hostConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (binding.released) return
                val remote = IContainerRemoteService.Stub.asInterface(service) ?: run {
                    fail(binding)
                    return
                }
                binding.remote = remote
                val info = binding.info
                io.execute {
                    try {
                        val targetBinder = remote.bindTargetService(
                            binding.session.spec.packageName,
                            binding.session.spec.instanceId,
                            binding.targetProcess,
                            binding.intent
                        )
                        mainHandler.post {
                            if (binding.released) {
                                // unbind() already ran before the reply arrived.
                                runCatching {
                                    remote.unbindTargetService(
                                        binding.session.spec.packageName,
                                        binding.session.spec.instanceId,
                                        binding.targetProcess,
                                        binding.intent
                                    )
                                }
                                return@post
                            }
                            binding.connected = true
                            if (targetBinder != null) {
                                binding.connection.onServiceConnected(
                                    ComponentName(binding.session.spec.packageName, info.name),
                                    targetBinder
                                )
                            } else {
                                notifyNullBinding(
                                    binding.connection,
                                    ComponentName(binding.session.spec.packageName, info.name)
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "remote bind ${info.name} failed", t)
                        fail(binding)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!binding.released) {
                    binding.connection.onServiceDisconnected(
                        ComponentName(binding.session.spec.packageName, binding.info.name)
                    )
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (!binding.released) fail(binding)
            }
        }
        binding.hostIntent = hostIntent
        binding.hostConnection = hostConnection
        val ok = runCatching {
            bindToHost(binding.session.hostContext, hostIntent, hostConnection)
        }.onFailure {
            Log.w(TAG, "bind host ${hostIntent.component} failed", it)
        }.getOrDefault(false)
        if (!ok) fail(binding)
    }

    fun unbind(session: ContainerSession, connection: ServiceConnection): Boolean {
        val binding = synchronized(bindings) { bindings.remove(connection) } ?: return false
        binding.released = true
        val host = binding.hostConnection ?: return true
        val remote = binding.remote
        io.execute {
            runCatching {
                remote?.unbindTargetService(
                    session.spec.packageName,
                    session.spec.instanceId,
                    binding.targetProcess,
                    binding.intent
                )
            }
            mainHandler.post {
                runCatching { session.hostContext.unbindService(host) }
            }
        }
        return true
    }

    fun start(
        session: ContainerSession,
        info: ServiceInfo,
        intent: Intent,
        foreground: Boolean
    ): ComponentName? {
        val process = ContainerProcessRouter.normalize(session.spec.packageName, info.processName)
        val key = "${session.spec.sessionId}|$process|${info.name}"
        val component = ComponentName(session.spec.packageName, info.name)
        val existing = synchronized(started) {
            started[key]?.also { handle ->
                handle.remote?.let { remote ->
                    io.execute { startRemote(handle, remote, intent, foreground) }
                    return component
                }
                synchronized(handle.pending) {
                    handle.pending.add(Intent(intent) to foreground)
                }
                return component
            }
        }
        if (existing != null) return component

        val handle = StartedService(session, info, process)
        synchronized(handle.pending) { handle.pending.add(Intent(intent) to foreground) }
        synchronized(started) { started[key] = handle }
        ContainerProcessBrokerClient.acquireAsync(
            session.hostContext,
            session.spec.packageName,
            session.spec.instanceId,
            process
        ) { slot ->
            if (handle.stopped) return@acquireAsync
            if (slot == null) {
                Log.w(TAG, "no slot for ${info.name} process=$process")
                synchronized(started) { started.remove(key) }
                return@acquireAsync
            }
            val hostIntent = Intent().setClassName(
                HostIdentity.packageName,
                ContainerRemoteServices.classForSlot(slot).name
            )
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val remote = IContainerRemoteService.Stub.asInterface(service) ?: return
                    handle.remote = remote
                    val pending = synchronized(handle.pending) {
                        handle.pending.toList().also { handle.pending.clear() }
                    }
                    io.execute {
                        pending.forEach { (queued, fg) ->
                            startRemote(handle, remote, queued, fg)
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    handle.remote = null
                }
            }
            handle.hostConnection = connection
            val ok = runCatching {
                bindToHost(session.hostContext, hostIntent, connection)
            }.getOrDefault(false)
            if (!ok) {
                synchronized(started) { started.remove(key) }
            }
        }
        return component
    }

    fun stop(session: ContainerSession, info: ServiceInfo, intent: Intent): Boolean {
        val process = ContainerProcessRouter.normalize(session.spec.packageName, info.processName)
        val key = "${session.spec.sessionId}|$process|${info.name}"
        val handle = synchronized(started) { started.remove(key) } ?: return true
        handle.stopped = true
        val remote = handle.remote
        val hostConnection = handle.hostConnection
        io.execute {
            if (remote != null) {
                runCatching {
                    remote.stopTargetService(
                        session.spec.packageName,
                        session.spec.instanceId,
                        handle.targetProcess,
                        intent
                    )
                }.onFailure { Log.w(TAG, "remote stop ${info.name} failed", it) }
            }
            if (hostConnection != null) {
                mainHandler.post {
                    runCatching { session.hostContext.unbindService(hostConnection) }
                }
            }
        }
        return true
    }

    private fun startRemote(
        handle: StartedService,
        remote: IContainerRemoteService,
        intent: Intent,
        foreground: Boolean
    ) {
        if (handle.stopped) return
        runCatching {
            remote.startTargetService(
                handle.session.spec.packageName,
                handle.session.spec.instanceId,
                handle.targetProcess,
                intent,
                foreground
            )
        }.onFailure { Log.w(TAG, "remote start ${handle.info.name} failed", it) }
    }

    private fun bindToHost(context: Context, intent: Intent, connection: ServiceConnection): Boolean {
        val app = context.applicationContext
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The host slot may be starting the target app; do not make the
            // binding wait behind that app's busy main looper.
            app.bindService(
                intent,
                Context.BIND_AUTO_CREATE,
                java.util.concurrent.Executor { it.run() },
                connection
            )
        } else {
            app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun fail(binding: RemoteBinding) {
        if (binding.released) return
        binding.released = true
        mainHandler.post {
            runCatching {
                binding.connection.onServiceDisconnected(
                    ComponentName(binding.session.spec.packageName, binding.info.name)
                )
            }
            binding.hostConnection?.let { host ->
                runCatching { binding.session.hostContext.unbindService(host) }
            }
        }
    }

    private fun notifyNullBinding(connection: ServiceConnection, component: ComponentName) {
        runCatching {
            connection.javaClass
                .getMethod("onNullBinding", ComponentName::class.java)
                .invoke(connection, component)
        }.onFailure {
            Log.w(TAG, "service ${component.className} returned null binder")
        }
    }
}
