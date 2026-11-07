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
import android.util.Log

/**
 * Local ContentProvider host for one ContainerSession.
 *
 * Target providers are instantiated with the target ClassLoader and attached to
 * the ContainerContext. A dispatcher ContentProvider wrapped by
 * ContentResolver.wrap() routes URIs by authority to those local instances;
 * external authorities fall through to the host's real ContentResolver.
 */
class ContainerProviderManager(
    private val session: ContainerSession
) {

    private val providers = linkedMapOf<String, ContentProvider>()
    private val pending = linkedMapOf<String, ProviderInfo>()
    private val instances = mutableMapOf<String, ContentProvider>()
    private val registeredKeys = mutableListOf<Any>()
    private val registeredAuthorities = mutableListOf<String>()
    private val lock = Any()
    private val attaching = ThreadLocal.withInitial { mutableSetOf<String>() }

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
            val dispatcher = Dispatcher(
                providers = lazyProviders,
                hostResolver = session.hostContext.contentResolver
            )
            val dispatcherInfo = ProviderInfo().apply {
                name = Dispatcher::class.java.name
                authority = authorities.joinToString(";")
                packageName = session.spec.packageName
                applicationInfo = session.spec.applicationInfo
                exported = true
            }
            dispatcher.attachInfo(session.context, dispatcherInfo)
            // ActivityThread keys providers by (authority, userId); the host
            // uid is a per-user uid, so its user id is uid / PER_USER_RANGE.
            val userId = session.spec.applicationInfo.uid / 100000
            registeredKeys.addAll(
                ActivityThreadProviderRegistry.register(
                    provider = dispatcher,
                    providerInfo = dispatcherInfo,
                    authorities = authorities,
                    userId = userId
                )
            )
            Log.d(TAG, "dispatcher published for ${authorities.size} authorities")
            session.context.targetContentResolver = VirtualContentResolver(
                session.hostContext,
                session.hostContext.contentResolver,
                authorities.toSet()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "dispatcher provider failed", t)
            return
        }

        session.spec.providers.forEach { info ->
            val authorities = authoritiesOf(info)
            if (authorities.isEmpty()) return@forEach
            // initOrder is the manifest's dependency signal. Providers with no
            // explicit order resolve on first authority access, after the
            // target Application has had a chance to initialize.
            if (info.initOrder <= 0) {
                synchronized(lock) { authorities.forEach { pending[it] = info } }
                return@forEach
            }

            val provider = try {
                instances[info.name] ?: createProvider(info).also {
                    instances[info.name] = it
                }
            } catch (t: Throwable) {
                Log.w(TAG, "create provider failed: ${info.name}", t)
                return@forEach
            }

            synchronized(lock) { authorities.forEach { providers[it] = provider } }
            try {
                provider.attachInfo(session.context, info)
                Log.d(TAG, "provider attached: ${info.name} ${authorities.joinToString(",")}")
            } catch (t: Throwable) {
                Log.w(TAG, "attach provider failed: ${info.name}", t)
                // Keep UnavailableProvider in place; falling back to the host
                // resolver would recurse into this dispatcher.
            }
        }
    }

    private fun authoritiesOf(info: ProviderInfo): List<String> =
        info.authority
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private fun providerFor(authority: String): ContentProvider? = synchronized(lock) {
        val current = providers[authority]
        if (current != null && current !is UnavailableProvider) return current
        val attachingSet = attaching.get()
        if (authority in attachingSet) {
            // A provider is being attached and is already touching its own
            // authority. Return the safe placeholder for the duration instead
            // of re-entering attachInfo or falling through to the host
            // resolver (which is this same dispatcher).
            return current ?: UnavailableProvider()
        }
        val info = pending[authority] ?: return current
        instances[info.name]?.let { existing ->
            providers[authority] = existing
            pending.remove(authority)
            return existing
        }
        val provider = try {
            createProvider(info).also { instances[info.name] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "create provider lazily failed: ${info.name}", t)
            pending.remove(authority)
            return current ?: UnavailableProvider()
        }
        return try {
            attachingSet.add(authority)
            try {
                provider.attachInfo(session.context, info)
            } finally {
                attachingSet.remove(authority)
            }
            Log.d(TAG, "provider attached lazily: ${info.name}")
            authoritiesOf(info).forEach {
                providers[it] = provider
                pending.remove(it)
            }
            providers[authority] = provider
            provider
        } catch (t: Throwable) {
            Log.w(TAG, "attach provider lazily failed: ${info.name}", t)
            val unavailable = UnavailableProvider()
            authoritiesOf(info).forEach {
                providers[it] = unavailable
                pending.remove(it)
            }
            unavailable
        }
    }

    fun destroy() {
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

    private fun createProvider(info: ProviderInfo): ContentProvider {
        val cls = session.loader.loadClass(info.name)
            .asSubclass(ContentProvider::class.java)
        return cls.getDeclaredConstructor().newInstance()
    }

    private class Dispatcher(
        private val providers: Map<String, ContentProvider>,
        private val hostResolver: ContentResolver
    ) : ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.query(uri, projection, selection, selectionArgs, sortOrder)
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
            return if (provider != null) {
                provider.query(uri, projection, queryArgs, cancellationSignal)
            } else {
                hostResolver.query(uri, projection, queryArgs, cancellationSignal)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.insert(uri, values)
            } else {
                hostResolver.insert(uri, values)
            }
        }

        override fun insert(uri: Uri, values: ContentValues?, extras: Bundle?): Uri? {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.insert(uri, values, extras)
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
            return if (provider != null) {
                provider.update(uri, values, selection, selectionArgs)
            } else {
                hostResolver.update(uri, values, selection, selectionArgs)
            }
        }

        override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.update(uri, values, extras)
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
            return if (provider != null) {
                provider.delete(uri, selection, selectionArgs)
            } else {
                hostResolver.delete(uri, selection, selectionArgs)
            }
        }

        override fun delete(uri: Uri, extras: Bundle?): Int {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.delete(uri, extras)
            } else {
                hostResolver.delete(uri, extras)
            }
        }

        override fun bulkInsert(uri: Uri, values: Array<out ContentValues>): Int {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.bulkInsert(uri, values)
            } else {
                hostResolver.bulkInsert(uri, values)
            }
        }

        override fun getType(uri: Uri): String? {
            val provider = providers[uri.authority]
            return if (provider != null) {
                provider.getType(uri)
            } else {
                hostResolver.getType(uri)
            }
        }

        override fun call(
            method: String,
            arg: String?,
            extras: Bundle?
        ): Bundle? = null

        override fun call(
            authority: String,
            method: String,
            arg: String?,
            extras: Bundle?
        ): Bundle? {
            val provider = providers[authority]
            return if (provider != null) {
                provider.call(authority, method, arg, extras)
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
            return if (provider != null) {
                provider.openFile(uri, mode, signal)
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
            return if (provider != null) {
                provider.openAssetFile(uri, mode, signal)
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
            return if (provider != null) {
                provider.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
            } else {
                hostResolver.openTypedAssetFile(uri, mimeTypeFilter, opts, signal)
            }
        }
    }

    private companion object {
        const val TAG = "ContainerProvider"
    }
}
