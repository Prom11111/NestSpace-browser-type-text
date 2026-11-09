package com.coke.nestspace.container.remote

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.AssetFileDescriptor
import android.database.CursorWindow
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.ContainerSession
import com.coke.nestspace.container.IContainerRemoteService
import com.coke.nestspace.container.identity.HostIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class RemoteProviderCursor(
    val columns: Array<String>,
    val window: CursorWindow
)

/**
 * Synchronous ContentProvider bridge for an authority owned by another
 * target process.
 *
 * ContentProvider operations run synchronously on the caller's thread, so
 * process allocation and Binder work happen on a worker while the caller
 * waits. API 29+ can deliver bind callbacks on that worker. On API 24-28 the
 * callback is delivered on the caller process's main thread, so those calls
 * work only when initiated off-main; returning null while already on main is
 * safer than deadlocking the virtual app.
 */
object ContainerRemoteProviderClient {
    private const val TAG = "ContainerRemoteProvider"
    private const val WAIT_SECONDS = 75L

    private val io = Executors.newCachedThreadPool()
    private val directExecutor = java.util.concurrent.Executor { it.run() }
    private val bindings = HashMap<BindingKey, RemoteBinding>()

    private data class BindingKey(
        val sessionId: String,
        val packageName: String,
        val instanceId: String,
        val processName: String
    )

    private class RemoteBinding(
        val key: BindingKey,
        val session: ContainerSession,
        val processName: String
    ) {
        val ready = CountDownLatch(1)
        val started = AtomicBoolean(false)
        @Volatile var remote: IContainerRemoteService? = null
        @Volatile var connection: ServiceConnection? = null
        @Volatile var closed = false
    }

    fun query(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): RemoteProviderCursor? = call(
        session,
        targetProcess,
        "query ${uri.authority}"
    ) { remote, process ->
        val result = remote.queryTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri,
            projection?.copyOf(),
            selection,
            selectionArgs?.copyOf(),
            sortOrder
        ) ?: return@call null
        val columns = result.getStringArray("columns") ?: return@call null
        val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelable("window", CursorWindow::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.getParcelable("window")
        } ?: return@call null
        RemoteProviderCursor(columns, window)
    }

    fun insert(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        values: ContentValues?
    ): Uri? = call(session, targetProcess, "insert ${uri.authority}") { remote, process ->
        remote.insertTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri,
            values
        )
    }

    fun bulkInsert(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        values: Array<out ContentValues>
    ): Int = call(session, targetProcess, "bulkInsert ${uri.authority}") { remote, process ->
        remote.bulkInsertTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri,
            values.copyOf()
        )
    } ?: 0

    fun update(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = call(session, targetProcess, "update ${uri.authority}") { remote, process ->
        remote.updateTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri,
            values,
            selection,
            selectionArgs?.copyOf()
        )
    } ?: 0

    fun delete(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = call(session, targetProcess, "delete ${uri.authority}") { remote, process ->
        remote.deleteTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri,
            selection,
            selectionArgs?.copyOf()
        )
    } ?: 0

    fun getType(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri
    ): String? = call(session, targetProcess, "getType ${uri.authority}") { remote, process ->
        remote.getTypeTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            uri
        )
    }

    fun callProvider(
        session: ContainerSession,
        targetProcess: String,
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? = call(session, targetProcess, "call $authority") { remote, process ->
        remote.callTargetProvider(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            authority,
            method,
            arg,
            extras
        )
    }

    fun openFile(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        mode: String
    ): ParcelFileDescriptor? =
        call(session, targetProcess, "openFile ${uri.authority}") { remote, process ->
            remote.openTargetProviderFile(
                session.spec.packageName,
                session.spec.instanceId,
                process,
                uri,
                mode
            )
        }

    fun openAssetFile(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        mode: String
    ): AssetFileDescriptor? =
        call(session, targetProcess, "openAssetFile ${uri.authority}") { remote, process ->
            remote.openTargetProviderAssetFile(
                session.spec.packageName,
                session.spec.instanceId,
                process,
                uri,
                mode
            )
        }

    fun openTypedAssetFile(
        session: ContainerSession,
        targetProcess: String,
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?
    ): AssetFileDescriptor? =
        call(session, targetProcess, "openTypedAssetFile ${uri.authority}") { remote, process ->
            remote.openTargetProviderTypedAssetFile(
                session.spec.packageName,
                session.spec.instanceId,
                process,
                uri,
                mimeTypeFilter,
                opts
            )
        }

    fun notifyContentChange(
        session: ContainerSession,
        targetProcess: String,
        uris: Array<Uri>,
        flags: Int
    ) {
        call(session, targetProcess, "notifyContentChange") { remote, process ->
            remote.notifyTargetContentChange(
                session.spec.packageName,
                session.spec.instanceId,
                process,
                uris.copyOf(),
                flags
            )
        }
    }

    fun deliverActivityIntent(
        session: ContainerSession,
        targetProcess: String,
        activityClass: String,
        intent: Intent
    ): Boolean = call(
        session,
        targetProcess,
        "singleTop $activityClass"
    ) { remote, process ->
        remote.deliverTargetActivityIntent(
            session.spec.packageName,
            session.spec.instanceId,
            process,
            activityClass,
            Intent(intent)
        )
    } ?: false

    private fun <T> call(
        session: ContainerSession,
        targetProcess: String,
        operation: String,
        block: (IContainerRemoteService, String) -> T
    ): T? {
        val process = ContainerProcessRouter.normalize(
            session.spec.packageName,
            targetProcess
        )
        cachedRemote(session, process)?.let { remote ->
            return try {
                block(remote, process)
            } catch (t: Throwable) {
                invalidate(session, process)
                Log.w(TAG, "$operation failed on cached binding", t)
                null
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            Looper.myLooper() == Looper.getMainLooper()
        ) {
            Log.w(TAG, "$operation from main is unavailable on API ${Build.VERSION.SDK_INT}")
            return null
        }
        val future = io.submit<T?> {
            callBlocking(session, process, block)
        }
        return try {
            future.get(WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            Log.w(TAG, "$operation failed", t)
            null
        }
    }

    /**
     * Release all remote Provider bindings owned by a container session.
     * Called when the caller process tears down its virtual target.
     */
    fun release(session: ContainerSession) {
        val app = session.hostContext.applicationContext
        val released = synchronized(bindings) {
            bindings.entries
                .filter { it.key.sessionId == session.spec.sessionId }
                .map { it.value }
                .also { owned ->
                    owned.forEach { binding ->
                        bindings.remove(binding.key)
                    }
                }
        }
        released.forEach { binding ->
            binding.closed = true
            binding.remote = null
            binding.ready.countDown()
            binding.connection?.let { connection ->
                runCatching { app.unbindService(connection) }
            }
        }
    }

    private fun <T> callBlocking(
        session: ContainerSession,
        process: String,
        block: (IContainerRemoteService, String) -> T
    ): T? {
        val binding = bindingFor(session, process)
        if (!binding.ready.await(30, TimeUnit.SECONDS)) {
            Log.w(TAG, "provider host bind timeout process=$process")
            invalidate(binding)
            return null
        }
        val service = binding.remote ?: run {
            if (!binding.closed) {
                Log.w(TAG, "provider host unavailable process=$process")
            }
            invalidate(binding)
            return null
        }
        return try {
            block(service, process)
        } catch (t: Throwable) {
            invalidate(binding)
            throw t
        }
    }

    private fun cachedRemote(
        session: ContainerSession,
        process: String
    ): IContainerRemoteService? {
        val key = key(session, process)
        val binding = synchronized(bindings) { bindings[key] }
        val remote = binding?.remote
        if (binding != null && remote == null && binding.ready.count == 0L) {
            invalidate(binding)
        }
        return remote
    }

    private fun bindingFor(
        session: ContainerSession,
        process: String
    ): RemoteBinding {
        val key = key(session, process)
        val binding: RemoteBinding
        val owner: Boolean
        synchronized(bindings) {
            val existing = bindings[key]
            if (existing != null) {
                binding = existing
                owner = false
            } else {
                binding = RemoteBinding(key, session, process)
                bindings[key] = binding
                owner = true
            }
        }
        if (owner && binding.started.compareAndSet(false, true)) {
            startBinding(binding)
        }
        return binding
    }

    private fun startBinding(binding: RemoteBinding) {
        val session = binding.session
        val process = binding.processName
        val slot = runCatching {
            ContainerProcessBrokerClient.acquire(
                session.hostContext,
                session.spec.packageName,
                session.spec.instanceId,
                process
            )
        }.onFailure {
            Log.w(TAG, "acquire provider process failed process=$process", it)
        }.getOrNull()
        if (slot == null) {
            invalidate(binding)
            return
        }
        val hostIntent = Intent().setClassName(
            HostIdentity.packageName,
            ContainerRemoteServices.classForSlot(slot).name
        )
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binding.closed) return
                binding.remote = IContainerRemoteService.Stub.asInterface(binder)
                binding.ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                invalidate(binding)
            }

            override fun onBindingDied(name: ComponentName?) {
                invalidate(binding)
            }

            override fun onNullBinding(name: ComponentName?) {
                invalidate(binding)
            }
        }
        binding.connection = connection
        val app = session.hostContext.applicationContext
        val bound = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.bindService(
                    hostIntent,
                    Context.BIND_AUTO_CREATE,
                    directExecutor,
                    connection
                )
            } else {
                @Suppress("DEPRECATION")
                app.bindService(hostIntent, connection, Context.BIND_AUTO_CREATE)
            }
        }.onFailure {
            Log.w(TAG, "bind provider host failed slot=$slot", it)
        }.getOrDefault(false)
        if (!bound) {
            invalidate(binding)
        }
    }

    private fun invalidate(session: ContainerSession, process: String) {
        val binding = synchronized(bindings) { bindings[key(session, process)] }
            ?: return
        invalidate(binding)
    }

    private fun invalidate(binding: RemoteBinding) {
        if (binding.closed) return
        binding.closed = true
        binding.remote = null
        binding.ready.countDown()
        synchronized(bindings) {
            if (bindings[binding.key] === binding) {
                bindings.remove(binding.key)
            }
        }
        binding.connection?.let { connection ->
            runCatching { binding.session.hostContext.applicationContext.unbindService(connection) }
        }
    }

    private fun key(session: ContainerSession, process: String): BindingKey =
        BindingKey(
            sessionId = session.spec.sessionId,
            packageName = session.spec.packageName,
            instanceId = session.spec.instanceId,
            processName = process
        )
}
