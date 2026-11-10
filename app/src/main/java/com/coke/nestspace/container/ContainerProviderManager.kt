package com.coke.nestspace.container

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.content.res.AssetFileDescriptor
import android.database.AbstractCursor
import android.database.CursorWindow
import android.util.Log
import com.coke.nestspace.container.remote.ContainerRemoteProviderClient
import java.util.concurrent.CompletableFuture

/**
 * Local ContentProvider host for one ContainerSession.
 *
 * Target providers are instantiated with the target ClassLoader and attached to
 * the ContainerContext. A dispatcher ContentProvider wrapped by
 * ContentResolver.wrap() routes URIs by authority to those local instances;
 * external authorities fall through to the host's real ContentResolver.
 */
data class ContainerProviderQuery(
    val columns: Array<String>,
    val window: CursorWindow
)

class ContainerProviderManager(
    private val session: ContainerSession
) {

    private val providers = linkedMapOf<String, ContentProvider>()
    private val pending = linkedMapOf<String, ProviderInfo>()
    private val instances = mutableMapOf<String, ContentProvider>()
    private val providerProcesses = linkedMapOf<String, String>()
    private val registeredKeys = mutableListOf<Any>()
    private val registeredAuthorities = mutableListOf<String>()
    private val loading = mutableMapOf<String, CompletableFuture<ContentProvider>>()
    private val lock = Any()
    private val attaching = ThreadLocal.withInitial { mutableSetOf<String>() }
    private val currentAttaching: MutableSet<String>
        get() = attaching.get() ?: mutableSetOf<String>().also(attaching::set)

    private sealed interface ProviderLookup {
        data class Ready(val provider: ContentProvider?) : ProviderLookup
        data class Wait(val future: CompletableFuture<ContentProvider>) : ProviderLookup
        data class Load(
            val info: ProviderInfo,
            val future: CompletableFuture<ContentProvider>
        ) : ProviderLookup
    }

    fun install() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(
                TAG,
                "ContentResolver.wrap requires API 26; target providers are disabled on " +
                    "API ${Build.VERSION.SDK_INT}"
            )
            return
        }
        val authorities = session.spec.providers
            .flatMap { info ->
                info.authority?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            }
            .distinct()
        if (authorities.isEmpty()) return
        VirtualContentObserverRegistry.bindSession(session)
        session.spec.providers.forEach { info ->
            val process = ContainerProcessRouter.normalize(
                session.spec.packageName,
                info.processName
            )
            authoritiesOf(info).forEach { authority ->
                providerProcesses[authority] = process
            }
        }
        // ContentResolver observers for these authorities never reach
        // system_server; keep them in-process.
        VirtualContentObserverRegistry.addAuthorities(authorities)
        synchronized(lock) { registeredAuthorities.addAll(authorities) }

        // Publish a non-recursive placeholder for every target authority before
        // attaching the real providers. If one provider fails to initialize,
        // its authority must not fall through to the host resolver: the host
        // resolver is the same dispatcher we are inside, which causes infinite
        // recursion.
        authorities.forEach { providers.putIfAbsent(it, UnavailableProvider()) }

        try {
            val lazyProviders = object : AbstractMap<String, ContentProvider>() {
                override val entries: Set<Map.Entry<String, ContentProvider>>
                    get() = synchronized(lock) { providers.entries.toSet() }

                override fun get(key: String): ContentProvider? = providerFor(key)
            }
            // ActivityThread keys providers by (authority, userId); the host
            // uid is a per-user uid, so its user id is uid / PER_USER_RANGE.
            val userId = session.spec.applicationInfo.uid / 100000
            authorities.forEach { authority ->
                // ContentProvider.call(String method) does not include a URI.
                // Publish one dispatcher per authority so that overload can
                // still route to the provider selected by ContentResolver.
                val dispatcher = Dispatcher(
                    providers = lazyProviders,
                    hostResolver = session.hostContext.contentResolver,
                    session = session,
                    defaultAuthority = authority,
                    remoteProcessFor = { candidate ->
                        synchronized(lock) { providerProcesses[candidate] }
                    }
                )
                val dispatcherInfo = ProviderInfo().apply {
                    name = Dispatcher::class.java.name
                    this.authority = authority
                    packageName = session.spec.packageName
                    applicationInfo = session.spec.applicationInfo
                    exported = true
                }
                dispatcher.attachInfo(session.context, dispatcherInfo)
                registeredKeys.addAll(
                    ActivityThreadProviderRegistry.register(
                        provider = dispatcher,
                        providerInfo = dispatcherInfo,
                        authorities = listOf(authority),
                        userId = userId
                    )
                )
            }
            Log.d(TAG, "dispatcher published for ${authorities.size} authorities")
            session.context.targetContentResolver = VirtualContentResolver(
                session.hostContext,
                session.hostContext.contentResolver,
                authorities.toSet(),
                session.spec.packageName,
                session.spec.instanceId
            )
        } catch (t: Throwable) {
            Log.w(TAG, "dispatcher provider failed", t)
            return
        }

        session.spec.providers
            .asSequence()
            .filter { it.enabled }
            .filter { info ->
                ContainerProcessRouter.normalize(
                    session.spec.packageName,
                    info.processName
                ) == session.spec.targetProcessName
            }
            .sortedByDescending { it.initOrder }
            .forEach { info ->
                val authorities = authoritiesOf(info)
                if (authorities.isEmpty()) return@forEach
                // Android installs every provider in the process before
                // Application.onCreate. initOrder controls only their relative
                // order; it does not make zero-order providers lazy.
                synchronized(lock) { authorities.forEach { pending[it] = info } }
                runCatching { providerFor(authorities.first()) }
                    .onFailure {
                        Log.w(TAG, "provider initialization failed: ${info.name}", it)
                    }
            }
    }

    private fun authoritiesOf(info: ProviderInfo): List<String> =
        info.authority
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun providerFor(authority: String): ContentProvider? {
        val lookup = synchronized(lock) {
            val attachingSet = currentAttaching
            if (authority in attachingSet) {
                // A provider is being created/attached and is already touching
                // its own authority. Return the safe placeholder instead of
                // re-entering attachInfo or falling through to the host
                // resolver (which is this same dispatcher).
                return@synchronized ProviderLookup.Ready(
                    providers[authority] ?: UnavailableProvider()
                )
            }
            val current = providers[authority]
            if (current != null && current !is UnavailableProvider) {
                return@synchronized ProviderLookup.Ready(current)
            }
            val info = pending[authority]
                ?: return@synchronized ProviderLookup.Ready(current)
            instances[info.name]?.let { existing ->
                providers[authority] = existing
                pending.remove(authority)
                return@synchronized ProviderLookup.Ready(existing)
            }
            val inFlight = loading[authority]
            if (inFlight != null) {
                ProviderLookup.Wait(inFlight)
            } else {
                val future = CompletableFuture<ContentProvider>()
                authoritiesOf(info).forEach { loading[it] = future }
                ProviderLookup.Load(info, future)
            }
        }
        return when (lookup) {
            is ProviderLookup.Ready -> lookup.provider
            is ProviderLookup.Wait -> lookup.future.join()
            is ProviderLookup.Load -> loadProvider(lookup.info, lookup.future)
        }
    }

    private fun loadProvider(
        info: ProviderInfo,
        future: CompletableFuture<ContentProvider>
    ): ContentProvider {
        val authorities = authoritiesOf(info)
        val attachingSet = currentAttaching
        var resolved: ContentProvider = UnavailableProvider()
        attachingSet.addAll(authorities)
        try {
            val provider = createProvider(info)
            provider.attachInfo(session.context, info)
            resolved = provider
            Log.d(TAG, "provider attached lazily: ${info.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "attach provider lazily failed: ${info.name}", t)
        } finally {
            attachingSet.removeAll(authorities.toSet())
            synchronized(lock) {
                if (resolved !is UnavailableProvider) {
                    instances[info.name] = resolved
                }
                authorities.forEach {
                    providers[it] = resolved
                    pending.remove(it)
                    loading.remove(it)
                }
            }
            future.complete(resolved)
        }
        return resolved
    }

    fun queryDirect(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): ContainerProviderQuery? {
        val provider = localProviderFor(uri.authority) ?: return null
        val cursor = provider.query(
            uri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        ) ?: return null
        return cursor.use {
            val window = CursorWindow("nestspace-provider-${session.spec.instanceId}")
            val columns = it.columnNames
            window.setNumColumns(columns.size)
            it.moveToPosition(-1)
            var filled = true
            while (it.moveToNext()) {
                if (!window.allocRow()) {
                    filled = false
                    break
                }
                val row = it.position
                columns.indices.forEach { column ->
                    val stored = when (it.getType(column)) {
                        Cursor.FIELD_TYPE_NULL ->
                            window.putNull(row, column)
                        Cursor.FIELD_TYPE_INTEGER ->
                            window.putLong(it.getLong(column), row, column)
                        Cursor.FIELD_TYPE_FLOAT ->
                            window.putDouble(it.getDouble(column), row, column)
                        Cursor.FIELD_TYPE_BLOB ->
                            window.putBlob(it.getBlob(column), row, column)
                        else ->
                            window.putString(it.getString(column), row, column)
                    }
                    if (!stored) filled = false
                }
                if (!filled) break
            }
            if (!filled) {
                window.close()
                null
            } else {
                window.setStartPosition(0)
                ContainerProviderQuery(columns, window)
            }
        }
    }

    fun insertDirect(uri: Uri, values: ContentValues?): Uri? =
        localProviderFor(uri.authority)?.insert(uri, values)

    fun bulkInsertDirect(uri: Uri, values: Array<out ContentValues>): Int =
        localProviderFor(uri.authority)?.bulkInsert(uri, values) ?: 0

    fun updateDirect(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = localProviderFor(uri.authority)
        ?.update(uri, values, selection, selectionArgs) ?: 0

    fun deleteDirect(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = localProviderFor(uri.authority)
        ?.delete(uri, selection, selectionArgs) ?: 0

    fun getTypeDirect(uri: Uri): String? =
        localProviderFor(uri.authority)?.getType(uri)

    fun callDirect(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? = localProviderFor(authority)?.call(authority, method, arg, extras)

    fun openFileDirect(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? =
        localProviderFor(uri.authority)?.openFile(uri, mode, signal)

    fun openAssetFileDirect(
        uri: Uri,
        mode: String,
        signal: CancellationSignal?
    ): AssetFileDescriptor? =
        localProviderFor(uri.authority)?.openAssetFile(uri, mode, signal)

    fun openTypedAssetFileDirect(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? =
        localProviderFor(uri.authority)?.openTypedAssetFile(
            uri,
            mimeTypeFilter,
            opts,
            signal
        )

    private fun localProviderFor(authority: String?): ContentProvider? {
        if (authority == null) return null
        val process = synchronized(lock) { providerProcesses[authority] }
        if (process != session.spec.targetProcessName) return null
        return providerFor(authority)?.takeUnless { it is UnavailableProvider }
    }

    fun destroy() {
        VirtualContentObserverRegistry.unbindSession(session)
        ContainerRemoteProviderClient.release(session)
        ActivityThreadProviderRegistry.unregister(registeredKeys)
        registeredKeys.clear()
        synchronized(lock) {
            VirtualContentObserverRegistry.removeAuthorities(registeredAuthorities)
            registeredAuthorities.clear()
        }
        val distinct = instances.values.toSet()
        distinct.forEach { provider -> runCatching { provider.shutdown() } }
        synchronized(lock) {
            instances.clear()
            pending.clear()
            providers.clear()
        }
        session.context.targetContentResolver = null
    }

    private class UnavailableProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun getType(uri: Uri): String? = null
        override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int = 0
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null
        override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? = null
    }

    /**
     * Read-only Cursor view over a CursorWindow returned by another process.
     * CursorWindow already owns the cross-process shared memory, so rows are
     * read lazily instead of copying every value into the caller heap.
     */
    private class CursorWindowCursor(
        private val window: CursorWindow,
        private val columns: Array<String>
    ) : AbstractCursor() {
        override fun getCount(): Int = window.numRows

        override fun getColumnNames(): Array<String> = columns

        override fun getString(column: Int): String? = window.getString(position, column)

        override fun getShort(column: Int): Short = window.getLong(position, column).toShort()

        override fun getInt(column: Int): Int = window.getLong(position, column).toInt()

        override fun getLong(column: Int): Long = window.getLong(position, column)

        override fun getFloat(column: Int): Float = window.getDouble(position, column).toFloat()

        override fun getDouble(column: Int): Double = window.getDouble(position, column)

        override fun isNull(column: Int): Boolean =
            window.getType(position, column) == Cursor.FIELD_TYPE_NULL

        override fun getBlob(column: Int): ByteArray? = window.getBlob(position, column)

        override fun getType(column: Int): Int = window.getType(position, column)

        override fun getWindow(): CursorWindow = window

        override fun close() {
            super.close()
            window.close()
        }
    }

    private fun createProvider(info: ProviderInfo): ContentProvider {
        val cls = session.loader.loadClass(info.name)
            .asSubclass(ContentProvider::class.java)
        return cls.getDeclaredConstructor().newInstance()
    }

    private class Dispatcher(
        private val providers: Map<String, ContentProvider>,
        private val hostResolver: ContentResolver,
        private val session: ContainerSession,
        private val defaultAuthority: String,
        private val remoteProcessFor: (String?) -> String?
    ) : ContentProvider() {

        override fun onCreate(): Boolean = true

        private fun remoteProcess(authority: String?): String? =
            remoteProcessFor(authority)
                ?.takeIf { it != session.spec.targetProcessName }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.query(uri, projection, selection, selectionArgs, sortOrder)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.query(
                        session,
                        process,
                        uri,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.let { CursorWindowCursor(it.window, it.columns) }
                }
            } else {
                hostResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            queryArgs: Bundle?,
            cancellationSignal: CancellationSignal?
        ): Cursor? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.query(uri, projection, queryArgs, cancellationSignal)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.query(
                        session,
                        process,
                        uri,
                        projection,
                        queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                        queryArgs?.getStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS
                        ),
                        queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
                    )?.let { CursorWindowCursor(it.window, it.columns) }
                }
            } else {
                hostResolver.query(uri, projection, queryArgs, cancellationSignal)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.insert(uri, values)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.insert(session, process, uri, values)
                }
            } else {
                hostResolver.insert(uri, values)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.insert(uri, values, extras)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.insert(session, process, uri, values)
                }
            } else {
                hostResolver.insert(uri, values, extras)
            }
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.update(uri, values, selection, selectionArgs)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.update(
                        session,
                        process,
                        uri,
                        values,
                        selection,
                        selectionArgs
                    )
                } ?: 0
            } else {
                hostResolver.update(uri, values, selection, selectionArgs)
            }
        }

        override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.update(uri, values, extras)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.update(
                        session,
                        process,
                        uri,
                        values,
                        null,
                        null
                    )
                } ?: 0
            } else {
                hostResolver.update(uri, values, extras)
            }
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.delete(uri, selection, selectionArgs)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.delete(
                        session,
                        process,
                        uri,
                        selection,
                        selectionArgs
                    )
                } ?: 0
            } else {
                hostResolver.delete(uri, selection, selectionArgs)
            }
        }

        override fun delete(uri: Uri, extras: Bundle?): Int {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.delete(uri, extras)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.delete(
                        session,
                        process,
                        uri,
                        null,
                        null
                    )
                } ?: 0
            } else {
                hostResolver.delete(uri, extras)
            }
        }

        override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.bulkInsert(uri, values)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.bulkInsert(
                        session,
                        process,
                        uri,
                        values
                    )
                } ?: 0
            } else {
                hostResolver.bulkInsert(uri, values)
            }
        }

        override fun getType(uri: Uri): String? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.getType(uri)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.getType(session, process, uri)
                }
            } else {
                hostResolver.getType(uri)
            }
        }

        override fun call(
            method: String,
            arg: String?,
            extras: Bundle?
        ): Bundle? {
            val provider = providers[defaultAuthority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.call(method, arg, extras)
            } else if (provider is UnavailableProvider) {
                remoteProcess(defaultAuthority)?.let { process ->
                    ContainerRemoteProviderClient.callProvider(
                        session,
                        process,
                        defaultAuthority,
                        method,
                        arg,
                        extras
                    )
                }
            } else {
                hostResolver.call(
                    Uri.parse("content://$defaultAuthority"),
                    method,
                    arg,
                    extras
                )
            }
        }

        override fun call(
            authority: String,
            method: String,
            arg: String?,
            extras: Bundle?
        ): Bundle? {
            val provider = providers[authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.call(authority, method, arg, extras)
            } else if (provider is UnavailableProvider) {
                remoteProcess(authority)?.let { process ->
                    ContainerRemoteProviderClient.callProvider(
                        session,
                        process,
                        authority,
                        method,
                        arg,
                        extras
                    )
                }
            } else {
                hostResolver.call(authority, method, arg, extras)
            }
        }

        override fun openFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?
        ): ParcelFileDescriptor? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.openFile(uri, mode, signal)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.openFile(
                        session,
                        process,
                        uri,
                        mode
                    )
                }
            } else {
                hostResolver.openFile(uri, mode, signal)
            }
        }

        override fun openAssetFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?
        ): AssetFileDescriptor? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.openAssetFile(uri, mode, signal)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.openAssetFile(
                        session,
                        process,
                        uri,
                        mode
                    )
                }
            } else {
                hostResolver.openAssetFile(uri, mode, signal)
            }
        }

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
            signal: CancellationSignal?
        ): AssetFileDescriptor? {
            val provider = providers[uri.authority]
            return if (provider != null && provider !is UnavailableProvider) {
                provider.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
            } else if (provider is UnavailableProvider) {
                remoteProcess(uri.authority)?.let { process ->
                    ContainerRemoteProviderClient.openTypedAssetFile(
                        session,
                        process,
                        uri,
                        mimeTypeFilter,
                        opts
                    )
                }
            } else {
                hostResolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
            }
        }
    }

    private companion object {
        const val TAG = "ContainerProvider"
    }
}
