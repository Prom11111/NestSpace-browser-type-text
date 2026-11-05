package com.coke.nestspace.container

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri

/**
 * ContentResolver for a virtual package.
 *
 * Normal data operations go through the framework resolver: the container's
 * dispatcher is registered in ActivityThread's provider map, so target
 * authorities resolve locally without touching system_server.
 *
 * Change notifications are different: ContentResolver.notifyChange() always
 * calls IContentService, and system_server rejects authorities that do not
 * belong to the installed host package ("Failed to find provider ... for user
 * 0"). Bilibili's XprefProvider writes a preference on the main thread and
 * notifies its authority immediately, which used to crash the target. Swallow
 * notifications for virtual authorities; SharedPreferences listeners used by
 * the app are unaffected.
 */
@Suppress("DEPRECATION")
class VirtualContentResolver(
    context: Context,
    private val delegate: ContentResolver,
    private val virtualAuthorities: Set<String>
) : VirtualContentResolverBase(context, delegate) {

    init {
        // ContentResolver is abstract at runtime (acquireProvider and friends
        // are hidden API). Setting mWrapped makes every data operation delegate
        // to the host resolver, so those abstract methods are never entered;
        // only notifyChange needs a container-specific implementation.
        runCatching {
            val field = ContentResolver::class.java.getDeclaredField("mWrapped")
            field.isAccessible = true
            field.set(this, delegate)
        }.onFailure {
            android.util.Log.w("VirtualContentResolver", "mWrapped unavailable", it)
        }
    }

    private fun isVirtual(uri: Uri): Boolean =
        uri.authority?.let { it in virtualAuthorities } == true

    override fun notifyChange(uri: Uri, observer: ContentObserver?) {
        if (isVirtual(uri)) {
            VirtualContentObserverRegistry.notifyLocal(uri, observer)
            return
        }
        delegate.notifyChange(uri, observer)
    }

    @Suppress("DEPRECATION")
    override fun notifyChange(
        uri: Uri,
        observer: ContentObserver?,
        syncToNetwork: Boolean
    ) {
        if (isVirtual(uri)) {
            VirtualContentObserverRegistry.notifyLocal(uri, observer)
            return
        }
        delegate.notifyChange(uri, observer, syncToNetwork)
    }

    override fun notifyChange(uri: Uri, observer: ContentObserver?, flags: Int) {
        if (isVirtual(uri)) {
            VirtualContentObserverRegistry.notifyLocal(uri, observer, flags)
            return
        }
        delegate.notifyChange(uri, observer, flags)
    }

    override fun notifyChange(
        uris: Collection<Uri>,
        observer: ContentObserver?,
        flags: Int
    ) {
        val virtual = uris.filter(::isVirtual)
        if (virtual.isNotEmpty()) {
            VirtualContentObserverRegistry.notifyLocal(virtual, observer, flags)
        }
        val external = uris.filterNot(::isVirtual)
        if (external.isEmpty()) return
        delegate.notifyChange(external, observer, flags)
    }
}
