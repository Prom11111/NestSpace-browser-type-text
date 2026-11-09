package com.coke.nestspace.container

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.IContentProvider
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.MessageDigest

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
@SuppressLint("BlockedPrivateApi")
class VirtualContentResolver(
    context: Context,
    private val delegate: ContentResolver,
    private val virtualAuthorities: Set<String>,
    packageName: String,
    instanceId: String
) : VirtualContentResolverBase(context, delegate) {
    val virtualAndroidId: String = buildAndroidId(context, packageName, instanceId)

    private val overlay = ProviderOverlayResolver(
        context,
        delegate,
        virtualAndroidId
    )

    init {
        // Route framework-final call()/query() methods through a resolver
        // whose provider hook can supply virtual settings values.
        runCatching {
            val field = ContentResolver::class.java.getDeclaredField("mWrapped")
            field.isAccessible = true
            field.set(this, overlay)
        }.onFailure {
            android.util.Log.w("VirtualContentResolver", "mWrapped unavailable", it)
        }
    }

    private fun isVirtual(uri: Uri): Boolean =
        uri.authority?.let { it in virtualAuthorities } == true

    override fun acquireProvider(context: Context, name: String): IContentProvider? =
        overlay.provider(context, name)

    override fun acquireUnstableProvider(context: Context, name: String): IContentProvider? =
        overlay.unstableProvider(context, name)

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

    private class ProviderOverlayResolver(
        context: Context,
        delegate: ContentResolver,
        private val androidId: String
    ) : VirtualContentResolverBase(context, delegate) {
        override fun acquireProvider(context: Context, name: String): IContentProvider? =
            provider(context, name)

        override fun acquireUnstableProvider(context: Context, name: String): IContentProvider? =
            unstableProvider(context, name)

        fun provider(context: Context, name: String): IContentProvider? =
            wrap(super.acquireProvider(context, name), name)

        fun unstableProvider(context: Context, name: String): IContentProvider? =
            wrap(super.acquireUnstableProvider(context, name), name)

        private fun wrap(provider: IContentProvider?, authority: String): IContentProvider? {
            if (provider == null || authority != SETTINGS_AUTHORITY) return provider
            return Proxy.newProxyInstance(
                IContentProvider::class.java.classLoader,
                arrayOf(IContentProvider::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "ContainerSettingsProvider($provider)"
                    "call" -> {
                        val callMethod = args?.getOrNull(2) as? String
                        val callArg = args?.getOrNull(3) as? String
                        when {
                            callMethod == "GET_secure" &&
                                callArg == Settings.Secure.ANDROID_ID ->
                                Bundle().apply { putString("value", androidId) }
                            // PhoneWindow reads these while Activity.attach is
                            // running, before StageA.onCreate can restore host
                            // framework state. Forwarding them through the
                            // target attribution makes system_server reject the
                            // process package. The framework defaults are safe.
                            callMethod == "GET_global" &&
                                callArg == "render_shadows_in_compositor" ->
                                Bundle().apply { putString("value", "1") }
                            callMethod == "GET_global" &&
                                callArg == "force_resizable_activities" ->
                                Bundle().apply { putString("value", "0") }
                            else ->
                                invokeDelegate(provider, method, args)
                        }
                    }
                    else -> invokeDelegate(provider, method, args)
                }
            } as IContentProvider
        }

        private fun invokeDelegate(
            provider: IContentProvider,
            method: java.lang.reflect.Method,
            args: Array<Any?>?
        ): Any? = try {
            method.invoke(provider, *(args ?: emptyArray()))
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

        private companion object {
            const val SETTINGS_AUTHORITY = "settings"
        }
    }

    private companion object {
        fun buildAndroidId(
            context: Context,
            packageName: String,
            instanceId: String
        ): String {
            val hostAndroidId = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                )
            }.getOrNull().orEmpty()
            val seed = listOf(
                hostAndroidId,
                Build.FINGERPRINT,
                packageName,
                instanceId
            ).joinToString("|")
            return MessageDigest.getInstance("SHA-256")
                .digest(seed.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }
    }

}
