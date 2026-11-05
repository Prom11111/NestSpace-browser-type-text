package com.coke.nestspace.container

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.coke.nestspace.container.remote.ContainerRemoteServiceClient

/**
 * Local Service host for one ContainerSession.
 *
 * Services declared by the target package are instantiated with the target
 * ClassLoader and ContainerContext, then driven directly (onCreate /
 * onStartCommand / onBind / onUnbind / onDestroy). No AMS token or host UID is
 * involved. External services fall back to the host Context.
 */
class ContainerServiceManager(
    private val session: ContainerSession
) : ContainerServiceBridge {

    private data class Binding(
        val connection: ServiceConnection,
        val intent: Intent,
        var connected: Boolean = false
    )

    private class PendingBind(val connection: ServiceConnection)

    private class Record(
        val info: ServiceInfo,
        val service: Service
    ) {
        var started = false
        var nextStartId = 0
        var binder: android.os.IBinder? = null
        var bindPending = false
        var directRefs = 0
        val bindings = mutableListOf<Binding>()
        var destroyed = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val records = linkedMapOf<String, Record>()

    /**
     * Connections whose bind is queued on the main handler. A worker can call
     * unbindService immediately after bindService; without this list the
     * unbind would fall through to the real Context and throw "Service not
     * registered" for a connection the host never saw.
     */
    private val pendingBinds = mutableListOf<PendingBind>()

    override fun startService(intent: Intent, foreground: Boolean): ComponentName? {
        val info = resolve(intent) ?: return null
        if (isRemoteService(info)) {
            return ContainerRemoteServiceClient.start(session, info, Intent(intent), foreground)
        }
        val copy = Intent(intent)
        // Service.onCreate/onStartCommand always run on the main thread in
        // Android. Calling them on the caller (often a worker such as Xiaomi
        // push) makes BaseService's `Handler()` constructor throw.
        mainHandler.post { startServiceNow(info, copy, foreground) }
        return ComponentName(session.spec.packageName, info.name)
    }

    private fun startServiceNow(info: ServiceInfo, intent: Intent, foreground: Boolean) {
        val record = obtain(info) ?: return
        Log.d(TAG, "startService ${info.name} foreground=$foreground")
        try {
            synchronized(this) { record.started = true }
            val startId = synchronized(this) { ++record.nextStartId }
            val flags = if (foreground) 1 else 0
            record.service.onStartCommand(intent, flags, startId)
        } catch (t: Throwable) {
            Log.w(TAG, "startService failed: ${info.name}", t)
        }
    }

    override fun bindService(
        intent: Intent,
        connection: ServiceConnection,
        flags: Int
    ): Boolean {
        val info = resolve(intent) ?: return false
        if (isRemoteService(info)) {
            return ContainerRemoteServiceClient.bind(session, info, Intent(intent), connection)
        }
        val copy = Intent(intent)
        val pending = PendingBind(connection)
        synchronized(this) { pendingBinds.add(pending) }
        mainHandler.post { bindServiceNow(info, copy, connection, pending) }
        return true
    }

    private fun bindServiceNow(
        info: ServiceInfo,
        intent: Intent,
        connection: ServiceConnection,
        pending: PendingBind
    ) {
        Log.d(TAG, "bindService ${info.name}")
        // unbindService may have run while this task was queued.
        if (synchronized(this) { pendingBinds.none { it === pending } }) return
        val record = obtain(info) ?: run {
            synchronized(this) { pendingBinds.remove(pending) }
            return
        }
        val binding = Binding(connection, intent)
        val component = ComponentName(session.spec.packageName, info.name)
        var canceled = false
        val binder = synchronized(this) {
            if (!pendingBinds.remove(pending)) {
                canceled = true
                null
            } else {
                record.bindings.add(binding)
                record.binder
            }
        }
        if (canceled) return
        if (binder != null) {
            binding.connected = true
            runCatching { connection.onServiceConnected(component, binder) }
            return
        }
        // Real Android services bind asynchronously: onBind runs after
        // bindService returns. Bilibili's KDownloadService.onBind resolves a
        // lazy producer that itself calls bindService for the same service;
        // calling onBind inline recurses until the stack overflows.
        val shouldBind = synchronized(this) {
            if (record.bindPending) {
                false
            } else {
                record.bindPending = true
                true
            }
        }
        if (shouldBind) completeBind(record, info, Intent(intent))
    }

    /** Cross-process bridge entry points; callers run these on the main thread. */
    fun bindDirect(intent: Intent): android.os.IBinder? {
        val info = resolve(intent) ?: return null
        val record = obtain(info) ?: return null
        return synchronized(this) {
            try {
                record.directRefs++
                if (record.binder == null) {
                    record.binder = record.service.onBind(Intent(intent))
                }
                record.binder
            } catch (t: Throwable) {
                Log.w(TAG, "bindDirect failed: ${info.name}", t)
                record.directRefs = (record.directRefs - 1).coerceAtLeast(0)
                null
            }
        }
    }

    fun unbindDirect(intent: Intent): Boolean {
        val info = resolve(intent) ?: return false
        val key = normalize(info.name)
        val record = synchronized(this) {
            records[key]?.also { it.directRefs = (it.directRefs - 1).coerceAtLeast(0) }
        } ?: return false
        if (record.directRefs == 0 && record.bindings.isEmpty() && !record.started) {
            runCatching { record.service.onUnbind(Intent(intent)) }
            destroy(key, record)
        }
        return true
    }

    fun startDirect(intent: Intent, foreground: Boolean): ComponentName? {
        val info = resolve(intent) ?: return null
        startServiceNow(info, Intent(intent), foreground)
        return ComponentName(session.spec.packageName, info.name)
    }

    fun stopDirect(intent: Intent): Boolean {
        val info = resolve(intent) ?: return false
        stopServiceNow(info)
        return true
    }

    private fun completeBind(record: Record, info: ServiceInfo, intent: Intent) {
        val component = ComponentName(session.spec.packageName, info.name)
        val binder = synchronized(this) {
            if (record.destroyed) {
                record.bindPending = false
                return
            }
            try {
                if (record.binder == null) {
                    record.binder = record.service.onBind(intent)
                }
                record.binder
            } catch (t: Throwable) {
                Log.w(TAG, "bindService failed: ${info.name}", t)
                null
            } finally {
                record.bindPending = false
            }
        }
        val pending = synchronized(this) {
            record.bindings.filter { !it.connected }.also { list ->
                list.forEach { it.connected = true }
            }
        }
        if (binder == null || pending.isEmpty()) return
        mainHandler.post {
            pending.forEach { binding ->
                runCatching {
                    binding.connection.onServiceConnected(component, binder)
                }.onFailure {
                    Log.w(TAG, "onServiceConnected failed: ${info.name}", it)
                }
            }
        }
    }

    override fun unbindService(connection: ServiceConnection): Boolean {
        if (ContainerRemoteServiceClient.unbind(session, connection)) return true
        // The bind may still be sitting in mainHandler's queue.
        val pending = synchronized(this) {
            pendingBinds.firstOrNull { it.connection === connection }?.also {
                pendingBinds.remove(it)
            }
        }
        if (pending != null) return true
        val unbindable = synchronized(this) {
            val matched = records.entries.filter { entry ->
                entry.value.bindings.any { it.connection === connection }
            }
            if (matched.isEmpty()) return false
            matched.forEach { entry ->
                entry.value.bindings.removeAll { it.connection === connection }
            }
            matched.filter { it.value.bindings.isEmpty() }
                .map { it.key to it.value }
        }
        unbindable.forEach { (key, record) ->
            mainHandler.post { unbindNow(key, record) }
        }
        return true
    }

    private fun unbindNow(key: String, record: Record) {
        // A new connection may have bound this service while the unbind was
        // queued; in that case keep it alive.
        if (synchronized(this) { record.bindings.isNotEmpty() }) return
        runCatching { record.service.onUnbind(Intent()) }
        if (!record.started) destroy(key, record)
    }

    override fun stopService(intent: Intent): Boolean {
        val info = resolve(intent) ?: return false
        if (isRemoteService(info)) {
            return ContainerRemoteServiceClient.stop(session, info, Intent(intent))
        }
        mainHandler.post { stopServiceNow(info) }
        return true
    }

    private fun serviceProcessName(info: ServiceInfo): String =
        ContainerProcessRouter.normalize(session.spec.packageName, info.processName)

    private fun isRemoteService(info: ServiceInfo): Boolean =
        serviceProcessName(info) != session.spec.targetProcessName

    private fun stopServiceNow(info: ServiceInfo) {
        val key = normalize(info.name)
        val record = synchronized(this) {
            records[key]?.also { it.started = false }
        } ?: return
        if (record.bindings.isEmpty()) destroy(key, record)
    }

    @Synchronized
    fun destroyAll() {
        synchronized(this) { pendingBinds.clear() }
        records.entries.toList().forEach { (key, record) ->
            runCatching { record.service.onUnbind(Intent()) }
            destroy(key, record)
        }
    }

    @Synchronized
    private fun obtain(info: ServiceInfo): Record? {
        val key = normalize(info.name)
        records[key]?.let { if (!it.destroyed) return it }
        return try {
            val serviceClass = session.loader.loadClass(info.name)
                .asSubclass(Service::class.java)
            val service = serviceClass.getDeclaredConstructor().newInstance()
            attachBaseContext(service)
            runCatching {
                Service::class.java.getDeclaredField("mApplication").apply {
                    isAccessible = true
                }.set(service, session.application)
            }
            service.onCreate()
            Log.d(TAG, "service created: ${info.name}")
            Record(info, service).also { records[key] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "create service failed: ${info.name}", t)
            null
        }
    }

    private fun attachBaseContext(service: Service) {
        var cls: Class<*>? = service.javaClass
        while (cls != null) {
            val method = runCatching {
                cls.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
            }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                method.invoke(service, session.context)
                return
            }
            cls = cls.superclass
        }
        Log.w(TAG, "Service.attachBaseContext not found: ${service.javaClass.name}")
    }

    @Synchronized
    private fun destroy(key: String, record: Record) {
        if (record.destroyed) return
        record.destroyed = true
        // NetEase's MainProcessPlayService blocks the UI thread in
        // AudioPlayConfigHelper -> CustomConfigImpl while being torn down
        // during its loading/permission flow. The service is only a local
        // object here; skip the synchronous target callback so finishing the
        // splash cannot ANR the process. The session-level destroyAll path
        // remains available for diagnostics.
        if (!ContainerCompat.isNetEase(session.spec.packageName)) {
            runCatching { record.service.onDestroy() }
        }
        records.remove(key)
    }

    private fun resolve(intent: Intent): ServiceInfo? {
        val component = intent.component
        if (component != null) {
            if (component.packageName != session.spec.packageName) return null
            return session.intents.findService(component.className).also {
                if (it == null) Log.w(TAG, "Service not declared: ${component.className}")
            }
        }

        val targetPackage = intent.`package`
        if (targetPackage != null && targetPackage != session.spec.packageName) return null
        return session.intents.resolveService(intent, 0)?.info.also {
            if (it == null) {
                Log.w(
                    TAG,
                    "implicit Service intent is not resolved: action=${intent.action} " +
                        "data=${intent.data}"
                )
            }
        }
    }

    private fun normalize(name: String?): String {
        val value = name.orEmpty()
        return when {
            value.startsWith(".") -> session.spec.packageName + value
            value.isNotEmpty() && !value.contains('.') -> "${session.spec.packageName}.$value"
            else -> value
        }
    }

    private companion object {
        const val TAG = "ContainerService"
    }
}
