package com.coke.nestspace.container

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IInterface
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Virtualises ContentObserver delivery for container authorities.
 *
 * ContentResolver.registerContentObserver/unregisterContentObserver are final
 * and talk to the system IContentService, which rejects authorities that do
 * not belong to the installed host package. The registry replaces the
 * process-wide `ContentResolver.sContentService` with a proxy:
 *
 * - registrations and notifications for virtual authorities stay in this
 *   process and are delivered locally through the original IContentObserver;
 * - everything else (Settings, MediaStore, external providers) is forwarded
 *   to the real IContentService unchanged.
 *
 * Slot processes host exactly one target app for their lifetime, so a single
 * process-wide registry is sufficient.
 */
object VirtualContentObserverRegistry {

    private const val TAG = "VirtualContentObserver"

    private class Registration(
        val uri: Uri,
        val notifyForDescendants: Boolean,
        val observer: Any,
        val userId: Int
    )

    private val authorities = linkedSetOf<String>()
    private val registrations = mutableListOf<Registration>()
    private val lock = Any()

    @Volatile
    private var installed = false

    private val dispatchThread by lazy {
        HandlerThread("VirtualContentObserver").apply { start() }
    }
    private val dispatchHandler: Handler by lazy { Handler(dispatchThread.looper) }

    private var realService: Any? = null
    private var contentServiceInterface: Class<*>? = null

    private val iContentObserverClass: Class<*>? by lazy {
        runCatching { Class.forName("android.database.IContentObserver") }
            .onFailure { Log.w(TAG, "IContentObserver unavailable", it) }
            .getOrNull()
    }

    private val onChangeEtc: Method? by lazy {
        runCatching {
            iContentObserverClass?.getMethod(
                "onChangeEtc",
                Boolean::class.javaPrimitiveType,
                Array<Uri>::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.onFailure { Log.d(TAG, "IContentObserver.onChangeEtc unavailable") }.getOrNull()
    }

    private val onChangeUri: Method? by lazy {
        runCatching {
            iContentObserverClass?.getMethod(
                "onChange",
                Boolean::class.javaPrimitiveType,
                Uri::class.java,
                Int::class.javaPrimitiveType
            )
        }.onFailure { Log.d(TAG, "IContentObserver.onChange(Uri) unavailable") }.getOrNull()
    }

    private val onChangeLegacy: Method? by lazy {
        runCatching {
            iContentObserverClass?.getMethod(
                "onChange",
                Boolean::class.javaPrimitiveType
            )
        }.onFailure { Log.d(TAG, "IContentObserver.onChange unavailable") }.getOrNull()
    }

    fun install() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            HiddenApiCompat.ensure()
            val field = runCatching {
                ContentResolver::class.java.getDeclaredField("sContentService").apply {
                    isAccessible = true
                }
            }.onFailure {
                Log.w(TAG, "ContentResolver.sContentService unavailable", it)
            }.getOrNull() ?: return

            val iface = runCatching {
                Class.forName("android.content.IContentService")
            }.onFailure {
                Log.w(TAG, "IContentService unavailable", it)
            }.getOrNull() ?: return
            contentServiceInterface = iface

            var real = runCatching { field.get(null) }.getOrNull()
            if (real == null) {
                real = runCatching {
                    ContentResolver::class.java
                        .getDeclaredMethod("getContentService")
                        .apply { isAccessible = true }
                        .invoke(null)
                }.onFailure {
                    Log.w(TAG, "real IContentService unavailable", it)
                }.getOrNull()
            }
            if (real == null) return
            if (Proxy.isProxyClass(real.javaClass) &&
                Proxy.getInvocationHandler(real) is ProxyHandler) {
                installed = true
                return
            }
            realService = real
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                ProxyHandler
            )
            runCatching { field.set(null, proxy) }
                .onSuccess {
                    installed = true
                    Log.d(TAG, "virtual IContentService installed")
                }
                .onFailure { Log.w(TAG, "install virtual IContentService failed", it) }
        }
    }

    /** Local notification from a target resolver that shadows the host one. */
    fun notifyLocal(uri: Uri, observer: ContentObserver?, flags: Int = 0) {
        notifyLocal(listOf(uri), observer, flags)
    }

    fun notifyLocal(uris: Collection<Uri>, observer: ContentObserver?, flags: Int = 0) {
        if (uris.isEmpty()) return
        dispatch(uris.toList(), transportOf(observer), flags)
    }

    private val getContentObserver: Method? by lazy {
        runCatching {
            ContentObserver::class.java.getDeclaredMethod("getContentObserver")
                .apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "ContentObserver.getContentObserver unavailable", it) }
            .getOrNull()
    }

    private fun transportOf(observer: ContentObserver?): Any? {
        if (observer == null) return null
        return runCatching { getContentObserver?.invoke(observer) }.getOrNull()
    }

    fun addAuthorities(values: Collection<String>) {
        if (values.isEmpty()) return
        install()
        synchronized(lock) {
            authorities.addAll(values)
        }
    }

    fun removeAuthorities(values: Collection<String>) {
        synchronized(lock) {
            authorities.removeAll(values.toSet())
            registrations.removeAll { it.uri.authority in values }
        }
    }

    private fun isVirtual(uri: Uri): Boolean =
        uri.authority?.let { synchronized(lock) { it in authorities } } == true

    // ---------------------------------------------------------------- proxy

    private object ProxyHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val values = args ?: emptyArray()
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === values.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "VirtualContentService"
                    else -> null
                }
            }
            return try {
                when (method.name) {
                    "registerContentObserver" -> {
                        handleRegister(method, values)
                        null
                    }
                    "unregisterContentObserver" -> {
                        handleUnregister(method, values)
                        null
                    }
                    "notifyChange" -> {
                        handleNotify(method, values)
                        null
                    }
                    else -> delegate(method, values)
                }
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        }
    }

    private fun handleRegister(method: Method, args: Array<Any?>) {
        val uri = args.filterIsInstance<Uri>().firstOrNull()
        val observer = observerArg(method, args)
        if (uri == null || observer == null || !isVirtual(uri)) {
            delegate(method, args)
            return
        }
        val descendants = args.filterIsInstance<Boolean>().firstOrNull() ?: false
        val userId = args.filterIsInstance<Int>().lastOrNull() ?: 0
        val key = uri.withoutQuery()
        synchronized(lock) {
            // The same ContentObserver can be registered for several URIs;
            // keep each registration but avoid duplicate deliveries.
            val duplicate = registrations.any {
                it.observer === observer && it.uri == key &&
                    it.notifyForDescendants == descendants
            }
            if (!duplicate) {
                registrations.add(Registration(key, descendants, observer, userId))
                Log.d(TAG, "register $uri descendants=$descendants")
            }
        }
    }

    private fun handleUnregister(method: Method, args: Array<Any?>) {
        val observer = args.filterIsInstance<Any>().firstOrNull { isContentObserver(it) }
        if (observer != null) {
            synchronized(lock) { registrations.removeAll { it.observer === observer } }
        }
        // The same observer may also be registered for host authorities; the
        // real service ignores observers it never saw.
        delegate(method, args)
    }

    private fun handleNotify(method: Method, args: Array<Any?>) {
        val uris = changedUris(args)
        if (uris.isEmpty()) {
            delegate(method, args)
            return
        }
        val virtual = uris.filter(::isVirtual)
        if (virtual.isEmpty()) {
            delegate(method, args)
            return
        }
        val origin = args.firstOrNull { isContentObserver(it) }
        val flags = notificationFlags(method, args)
        dispatch(virtual, origin, flags)

        val external = uris.filterNot(::isVirtual)
        if (external.isNotEmpty()) {
            delegateFiltered(method, args, external)
        }
    }

    private fun dispatch(uris: List<Uri>, origin: Any?, flags: Int) {
        val targets = mutableMapOf<Any, MutableList<Uri>>()
        synchronized(lock) {
            uris.forEach { changed ->
                registrations.forEach { reg ->
                    if (origin != null && sameObserver(reg.observer, origin)) return@forEach
                    if (matches(reg, changed)) {
                        targets.getOrPut(reg.observer) { mutableListOf() }.add(changed)
                    }
                }
            }
        }
        if (targets.isEmpty()) return
        dispatchHandler.post {
            targets.forEach { (observer, changed) ->
                deliver(observer, changed, flags)
            }
        }
    }

    private fun deliver(observer: Any, uris: List<Uri>, flags: Int) {
        val userId = synchronized(lock) {
            registrations.firstOrNull { it.observer === observer }?.userId
        } ?: 0
        runCatching {
            val etc = onChangeEtc
            if (etc != null) {
                etc.invoke(observer, false, uris.toTypedArray(), flags, userId)
                return@runCatching
            }
            val single = onChangeUri
            uris.forEach { uri ->
                if (single != null) {
                    single.invoke(observer, false, uri, userId)
                } else {
                    onChangeLegacy?.invoke(observer, false)
                }
            }
        }.onFailure {
            Log.w(TAG, "deliver observer callback failed", it)
        }
    }

    private fun matches(reg: Registration, changed: Uri): Boolean {
        val target = changed.withoutQuery()
        if (target == reg.uri) return true
        return reg.notifyForDescendants && target.isDescendantOfSegments(reg.uri)
    }

    // Uri.isDescendantOf is hidden API; compare path segments directly.
    private fun Uri.isDescendantOfSegments(ancestor: Uri): Boolean {
        if (!scheme.equals(ancestor.scheme, ignoreCase = true)) return false
        if (!authority.equals(ancestor.authority, ignoreCase = true)) return false
        val base = ancestor.pathSegments
        val path = pathSegments
        if (path.size <= base.size) return false
        return path.subList(0, base.size) == base
    }

    private fun Uri.withoutQuery(): Uri =
        if (query == null && fragment == null) this
        else buildUpon().query(null).fragment(null).build()

    private fun sameObserver(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        val binderA = (a as? IInterface)?.asBinder()
        val binderB = (b as? IInterface)?.asBinder()
        return binderA != null && binderA === binderB
    }

    private fun isContentObserver(value: Any?): Boolean =
        value != null && iContentObserverClass?.isInstance(value) == true

    private fun observerArg(method: Method, args: Array<Any?>): Any? {
        val index = method.parameterTypes.indexOfFirst { param ->
            iContentObserverClass?.isAssignableFrom(param) == true
        }
        return args.getOrNull(index)
    }

    private fun changedUris(args: Array<Any?>): List<Uri> {
        val raw = args.firstOrNull { it is Uri || it is Array<*> || it is Collection<*> }
        return when (raw) {
            is Uri -> listOf(raw)
            is Array<*> -> raw.filterIsInstance<Uri>()
            is Collection<*> -> raw.filterIsInstance<Uri>()
            else -> emptyList()
        }
    }

    private fun notificationFlags(method: Method, args: Array<Any?>): Int {
        // notifyChange(uri, observer, boolean syncToNetwork, int userId,
        //               int targetSdkVersion, int flags): flags is last.
        // Older overloads have no flags.
        val ints = method.parameterTypes
            .mapIndexedNotNull { index, type ->
                if (type == Int::class.javaPrimitiveType) index to args.getOrNull(index) else null
            }
        if (ints.size >= 3) return ints.last().second as? Int ?: 0
        return 0
    }

    private fun delegate(method: Method, args: Array<Any?>): Any? {
        val real = realService ?: return null
        return method.invoke(real, *args)
    }

    /** Forward only the non-virtual URIs of a multi-uri notify. */
    private fun delegateFiltered(method: Method, args: Array<Any?>, external: List<Uri>) {
        val real = realService ?: return
        val index = args.indexOfFirst { it is Collection<*> || it is Array<*> }
        if (index < 0) return
        val copy = args.copyOf()
        copy[index] = when (val original = copy[index]) {
            is Collection<*> -> external
            is Array<*> -> external.toTypedArray()
            else -> original
        }
        method.invoke(real, *copy)
    }
}
