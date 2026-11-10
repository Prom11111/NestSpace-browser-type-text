package com.coke.nestspace.container.identity

import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.browser.webUrlFromViewIntent
import com.coke.nestspace.container.notify.ContainerNotificationBridge
import com.coke.nestspace.container.vpm.ContainerPermissions
import com.coke.nestspace.container.vpm.VirtualPackageRegistry
import com.coke.nestspace.runtime.RuntimeHostHooks


import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.content.res.Resources
import android.os.Process
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal AMS/PackageManager identity virtualization for a container process.
 *
 * The target APK is not installed for the host UID. Code in the container
 * therefore has two identities:
 *
 * - target code and native SDKs see the target package (ContainerContext);
 * - system_server must see the host package, otherwise every AMS call fails
 *   with SECURITY "package <target> does not belong to uid <host>".
 *
 * We install local proxies over ActivityTaskManager/ActivityManager that
 * rewrite AMS-facing package arguments to the host package, and an
 * IPackageManager proxy that answers target-package queries from the launch
 * descriptor instead of forwarding them to the real package manager.
 */
object SystemServiceIdentity {

    private const val TAG = "SystemServiceIdentity"

    private data class MemberKey(
        val owner: Class<*>,
        val name: String,
        val parameterTypes: List<Class<*>>,
        val declaredOnly: Boolean
    )

    private data class FieldKey(val owner: Class<*>, val name: String)

    private data class AssignableFieldKey(
        val owner: Class<*>,
        val targetType: Class<*>,
        val staticOnly: Boolean,
        val exactTypeName: String?
    )

    private val reflectionMiss = Any()
    private val classCache = ConcurrentHashMap<String, Any>()
    private val methodCache = ConcurrentHashMap<MemberKey, Any>()
    private val constructorCache = ConcurrentHashMap<MemberKey, Any>()
    private val fieldCache = ConcurrentHashMap<FieldKey, Any>()
    private val assignableFieldCache = ConcurrentHashMap<AssignableFieldKey, Any>()
    private val declaredFieldsCache = ConcurrentHashMap<Class<*>, Array<Field>>()

    @Volatile private var targetPackage: String = ""
    @Volatile private var targetInstance: String = ""
    @Volatile private var targetSpace: String = ""
    @Volatile private var hostPackage: String = ""
    @Volatile private var targetPackageInfo: PackageInfo? = null
    @Volatile private var targetAppInfo: ApplicationInfo? = null
    @Volatile private var targetResources: Resources? = null
    @Volatile private var registry: VirtualPackageRegistry? = null
    @Volatile private var virtualPermissions: ContainerPermissions? = null
    @Volatile private var hostContext: Context? = null
    @Volatile private var aiInfoCaptureActive = false
    private data class RootExitHook(
        val interceptFinish: Boolean,
        val interceptTaskExit: Boolean,
        val callback: () -> Unit
    )
    private val rootExitHook = AtomicReference<RootExitHook?>(null)

    @Volatile private var ipmProxy: Any? = null
    @Volatile private var legacyPackageManager: PackageManager? = null
    private val packageManagerDelegation = ThreadLocal.withInitial { false }
    /** Virtual component/app enabled states; never forwarded to the host PMS. */
    private val componentStates = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var amsInstalled = false
    private var ipmInstalled = false
    private var notificationInstalled = false
    private var alarmInstalled = false
    private var mediaSessionInstalled = false
    private var appOpsInstalled = false
    private var connectivityInstalled = false
    private var clipboardInstalled = false
    private var inputMethodInstalled = false

    /**
     * Arms a one-shot hook for the root target page. It is set only while the
     * host is forwarding a back press, so an internal Activity finish cannot be
     * mistaken for leaving the container.
     */
    fun armRootExitHook(
        interceptFinish: Boolean,
        interceptTaskExit: Boolean,
        callback: () -> Unit
    ) {
        rootExitHook.set(
            RootExitHook(
                interceptFinish = interceptFinish,
                interceptTaskExit = interceptTaskExit,
                callback = callback
            )
        )
    }

    fun clearRootFinishInterceptor() {
        rootExitHook.set(null)
    }

    /** AI headless/mirror reads must never be promoted to system PiP. */
    fun setAiInfoCaptureActive(active: Boolean) {
        aiInfoCaptureActive = active
    }

    fun install(
        spec: ContainerLaunchSpec,
        hostPackage: String,
        contexts: List<Context>,
        targetResources: Resources? = null,
        registry: VirtualPackageRegistry? = null,
        permissions: ContainerPermissions? = null
    ) {
        targetPackage = spec.packageName
        targetInstance = spec.instanceId
        targetSpace = spec.spaceId
        this.hostPackage = hostPackage
        targetPackageInfo = spec.packageInfo
        targetAppInfo = spec.applicationInfo
        this.targetResources = targetResources
        this.registry = registry
        virtualPermissions = permissions
        hostContext = contexts.firstOrNull()?.applicationContext

        if (!amsInstalled) {
            amsInstalled = true
            runCatching {
                installServiceProxy(
                    ownerName = "android.app.ActivityTaskManager",
                    interfaceName = "android.app.IActivityTaskManager",
                    handler = ::AmRewriteHandler,
                    label = "ActivityTaskManager"
                )
            }.onFailure { Log.w(TAG, "ActivityTaskManager proxy failed", it) }
            runCatching {
                installServiceProxy(
                    ownerName = "android.app.ActivityManager",
                    interfaceName = "android.app.IActivityManager",
                    handler = ::AmRewriteHandler,
                    label = "ActivityManager"
                )
            }.onFailure { Log.w(TAG, "ActivityManager proxy failed", it) }
        }
        if (!ipmInstalled) {
            ipmInstalled = true
            runCatching { installPackageManagerProxy(contexts) }
                .onFailure { Log.w(TAG, "IPackageManager proxy failed", it) }
        }
        if (!notificationInstalled) {
            notificationInstalled = true
            runCatching {
                installServiceProxy(
                    ownerName = "android.app.NotificationManager",
                    interfaceName = "android.app.INotificationManager",
                    handler = ::NotificationRewriteHandler,
                    label = "NotificationManager"
                )
            }.onFailure { Log.w(TAG, "NotificationManager proxy failed", it) }
        }
        if (!alarmInstalled) {
            alarmInstalled = true
            runCatching { installAlarmManagerProxy(contexts) }
                .onFailure { Log.w(TAG, "AlarmManager proxy failed", it) }
        }
        if (!mediaSessionInstalled) {
            mediaSessionInstalled = true
            runCatching { installMediaSessionProxy(contexts) }
                .onFailure { Log.w(TAG, "MediaSessionManager proxy failed", it) }
        }
        if (!appOpsInstalled) {
            appOpsInstalled = true
            runCatching { installAppOpsProxy(contexts) }
                .onFailure { Log.w(TAG, "AppOpsManager proxy failed", it) }
        }
        if (!connectivityInstalled) {
            connectivityInstalled = true
            runCatching {
                installCachedManagerProxy(
                    contexts,
                    Context.CONNECTIVITY_SERVICE,
                    "android.net.IConnectivityManager",
                    "ConnectivityManager"
                )
            }.onFailure { Log.w(TAG, "ConnectivityManager proxy failed", it) }
        }
        if (!clipboardInstalled) {
            clipboardInstalled = true
            runCatching {
                installCachedManagerProxy(
                    contexts,
                    Context.CLIPBOARD_SERVICE,
                    "android.content.IClipboard",
                    "ClipboardManager"
                )
            }.onFailure { Log.w(TAG, "ClipboardManager proxy failed", it) }
        }
        if (!inputMethodInstalled) {
            inputMethodInstalled = true
            runCatching { installInputMethodProxy(contexts) }
                .onFailure { Log.w(TAG, "InputMethodManager proxy failed", it) }
        }

        // Containers created earlier may already hold an ApplicationPackageManager.
        (contexts + listOfNotNull(systemContext())).forEach {
            patchApplicationPackageManagers(it)
        }
    }

    /** The process-wide IPackageManager proxy, or null before install(). */
    fun packageManagerInterface(): Any? = ipmProxy

    /**
     * The container's PackageManager wrapper. The IPackageManager proxy routes
     * component and intent queries through it so a real framework
     * ApplicationPackageManager (which target JNI may inspect) still answers
     * with virtual components.
     */
    fun attachPackageManager(packageManager: PackageManager) {
        legacyPackageManager = packageManager
    }

    private fun findClass(name: String): Class<*>? {
        classCache[name]?.let { return it as? Class<*> }
        val found = runCatching { Class.forName(name) }.getOrElse { reflectionMiss }
        return (classCache.putIfAbsent(name, found) ?: found) as? Class<*>
    }

    private fun loadClass(name: String): Class<*> =
        findClass(name) ?: throw ClassNotFoundException(name)

    private fun method(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = findMember(
        cache = methodCache,
        key = MemberKey(owner, name, parameterTypes.toList(), declaredOnly = false)
    ) {
        owner.getMethod(name, *parameterTypes)
    }

    private fun declaredMethod(
        owner: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? = findMember(
        cache = methodCache,
        key = MemberKey(owner, name, parameterTypes.toList(), declaredOnly = true)
    ) {
        owner.getDeclaredMethod(name, *parameterTypes)
    }

    private fun constructor(
        owner: Class<*>,
        vararg parameterTypes: Class<*>
    ): Constructor<*>? = findMember(
        cache = constructorCache,
        key = MemberKey(owner, "<init>", parameterTypes.toList(), declaredOnly = true)
    ) {
        owner.getDeclaredConstructor(*parameterTypes)
    }

    private inline fun <reified T : Any> findMember(
        cache: ConcurrentHashMap<MemberKey, Any>,
        key: MemberKey,
        lookup: () -> T
    ): T? {
        cache[key]?.let { return it as? T }
        val found = runCatching {
            lookup().also { member ->
                when (member) {
                    is Method -> member.isAccessible = true
                    is Constructor<*> -> member.isAccessible = true
                }
            }
        }.getOrElse { reflectionMiss }
        return (cache.putIfAbsent(key, found) ?: found) as? T
    }

    private fun declaredField(owner: Class<*>, name: String): Field? {
        val key = FieldKey(owner, name)
        fieldCache[key]?.let { return it as? Field }
        val found = runCatching {
            owner.getDeclaredField(name).apply { isAccessible = true }
        }.getOrElse { reflectionMiss }
        return (fieldCache.putIfAbsent(key, found) ?: found) as? Field
    }

    private fun declaredFields(owner: Class<*>): Array<Field> =
        declaredFieldsCache[owner]
            ?: owner.declaredFields.also { declaredFieldsCache.putIfAbsent(owner, it) }

    private fun assignableFields(
        owner: Class<*>,
        targetType: Class<*>,
        staticOnly: Boolean = false,
        exactTypeName: String? = null
    ): List<Field> = declaredFields(owner)
        .asSequence()
        .filter { !staticOnly || Modifier.isStatic(it.modifiers) }
        .filter { exactTypeName == null || it.type.name == exactTypeName }
        .filter { targetType.isAssignableFrom(it.type) }
        .map { field ->
            field.apply { isAccessible = true }
        }
        .toList()

    private fun assignableField(
        owner: Class<*>,
        targetType: Class<*>,
        staticOnly: Boolean = false,
        exactTypeName: String? = null
    ): Field? {
        val key = AssignableFieldKey(owner, targetType, staticOnly, exactTypeName)
        assignableFieldCache[key]?.let { return it as? Field }
        val found = assignableFields(owner, targetType, staticOnly, exactTypeName).firstOrNull()
            ?: reflectionMiss
        return (assignableFieldCache.putIfAbsent(key, found) ?: found) as? Field
    }

    private fun installServiceProxy(
        ownerName: String,
        interfaceName: String,
        handler: (Any) -> InvocationHandler,
        label: String
    ) {
        val iface = loadClass(interfaceName)
        val owner = loadClass(ownerName)
        val getService = method(owner, "getService") ?: return
        val delegate = getService.invoke(null) ?: return
        val proxy = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            handler(delegate)
        )
        val staticReplaced = replaceStaticField(owner, iface, proxy)
        // Newer framework versions cache inside android.util.Singleton even
        // when a static field also exists, so both are replaced.
        val singletonReplaced = replaceSingleton(owner, iface, proxy)
        val replaced = staticReplaced || singletonReplaced
        Log.d(
            TAG,
            "$label proxy installed=$replaced static=$staticReplaced " +
                "singleton=$singletonReplaced delegate=${delegate.javaClass.name}"
        )
    }

    private fun replaceStaticField(owner: Class<*>, iface: Class<*>, proxy: Any): Boolean {
        var replaced = false
        assignableFields(owner, iface, staticOnly = true).forEach { field ->
            runCatching {
                field.set(null, proxy)
                replaced = true
            }
        }
        return replaced
    }

    private fun replaceSingleton(owner: Class<*>, iface: Class<*>, proxy: Any): Boolean {
        var replaced = false
        declaredFields(owner).forEach { field ->
            if (!Modifier.isStatic(field.modifiers)) return@forEach
            if (field.type.name != "android.util.Singleton") return@forEach
            runCatching {
                field.isAccessible = true
                val singleton = field.get(null) ?: return@runCatching
                val instance = declaredField(field.type, "mInstance") ?: return@runCatching
                val current = instance.get(singleton)
                if (current != null && iface.isInstance(current)) {
                    instance.set(singleton, proxy)
                    replaced = true
                }
            }
        }
        return replaced
    }

    /**
     * On current releases AlarmManager holds a final IAlarmManager instance
     * field instead of a static service, so replace mService on the context's
     * cached AlarmManager. Package arguments are rewritten like AMS calls.
     */
    private fun installAlarmManagerProxy(contexts: List<Context>) {
        val iface = loadClass("android.app.IAlarmManager")
        var patched = 0
        val candidates = (contexts + listOfNotNull(systemContext())).mapNotNull { context ->
            runCatching { context.getSystemService(Context.ALARM_SERVICE) }.getOrNull()
        }
        candidates.distinctBy { System.identityHashCode(it) }.forEach { manager ->
            runCatching {
                val field = assignableField(manager.javaClass, iface) ?: return@forEach
                val delegate = field.get(manager) ?: return@forEach
                val proxy = Proxy.newProxyInstance(
                    iface.classLoader,
                    arrayOf(iface),
                    AmRewriteHandler(delegate)
                )
                field.set(manager, proxy)
                patched++
            }
        }
        Log.d(TAG, "AlarmManager mService proxies=$patched")
    }

    /**
     * MediaSessionManager caches ISessionManager in an instance field. Replace
     * it for every cached manager and rewrite target package arguments to the
     * host package accepted by the media session service.
     */
    private fun installMediaSessionProxy(contexts: List<Context>) {
        val iface = loadClass("android.media.session.ISessionManager")
        var patched = 0
        val candidates = (contexts + listOfNotNull(systemContext())).mapNotNull { context ->
            runCatching { context.getSystemService(Context.MEDIA_SESSION_SERVICE) }.getOrNull()
        }
        candidates.distinctBy { System.identityHashCode(it) }.forEach { manager ->
            val field = assignableField(manager.javaClass, iface) ?: return@forEach
            runCatching {
                val delegate = field.get(manager) ?: return@runCatching
                if (isIdentityProxy(delegate)) return@runCatching
                field.set(
                    manager,
                    Proxy.newProxyInstance(
                        iface.classLoader,
                        arrayOf(iface),
                        IdentityRewriteHandler(delegate)
                    )
                )
                patched++
            }.onFailure {
                Log.w(TAG, "patch MediaSessionManager failed", it)
            }
        }
        Log.d(TAG, "MediaSessionManager mService proxies=$patched")
    }

    /**
     * AppOpsManager has both a process-wide sService and a final per-instance
     * mService. Patch both so direct checks and newly-created managers use the
     * virtual attribution.
     */
    private fun installAppOpsProxy(contexts: List<Context>) {
        val owner = loadClass("android.app.AppOpsManager")
        val iface = loadClass("com.android.internal.app.IAppOpsService")
        val serviceField = assignableField(owner, iface, staticOnly = true) ?: return
        val staticDelegate = serviceField.get(null)
        var staticPatched = false
        if (staticDelegate != null && !isIdentityProxy(staticDelegate)) {
            serviceField.set(
                null,
                Proxy.newProxyInstance(
                    iface.classLoader,
                    arrayOf(iface),
                    IdentityRewriteHandler(staticDelegate)
                )
            )
            staticPatched = true
        }

        var instances = 0
        val candidates = (contexts + listOfNotNull(systemContext())).mapNotNull { context ->
            runCatching { context.getSystemService(Context.APP_OPS_SERVICE) }.getOrNull()
        }
        candidates.distinctBy { System.identityHashCode(it) }.forEach { manager ->
            val field = assignableField(manager.javaClass, iface) ?: return@forEach
            runCatching {
                val delegate = field.get(manager) ?: return@runCatching
                if (isIdentityProxy(delegate)) return@runCatching
                field.set(
                    manager,
                    Proxy.newProxyInstance(
                        iface.classLoader,
                        arrayOf(iface),
                        IdentityRewriteHandler(delegate)
                    )
                )
                instances++
            }.onFailure {
                Log.w(TAG, "patch AppOpsManager failed", it)
            }
        }
        Log.d(TAG, "AppOpsManager proxies static=$staticPatched instances=$instances")
    }

    /** Patch a framework manager that caches its AIDL service in a final field. */
    private fun installCachedManagerProxy(
        contexts: List<Context>,
        serviceName: String,
        interfaceName: String,
        label: String
    ) {
        val iface = loadClass(interfaceName)
        var patched = 0
        val candidates = (contexts + listOfNotNull(systemContext())).mapNotNull { context ->
            runCatching { context.getSystemService(serviceName) }.getOrNull()
        }
        candidates.distinctBy { System.identityHashCode(it) }.forEach { manager ->
            val field = assignableField(manager.javaClass, iface) ?: return@forEach
            runCatching {
                val delegate = field.get(manager) ?: return@runCatching
                if (isIdentityProxy(delegate)) return@runCatching
                field.set(
                    manager,
                    Proxy.newProxyInstance(
                        iface.classLoader,
                        arrayOf(iface),
                        IdentityRewriteHandler(delegate)
                    )
                )
                patched++
            }.onFailure {
                Log.w(TAG, "patch $label failed", it)
            }
        }
        Log.d(TAG, "$label service proxies=$patched")
    }

    private fun installInputMethodProxy(contexts: List<Context>) {
        val iface = loadClass("com.android.internal.view.IInputMethodManager")
        val invoker = loadClass(
            "android.view.inputmethod.IInputMethodManagerGlobalInvoker"
        )
        val field = assignableField(invoker, iface, staticOnly = true)
        var cachePatched = false
        if (field != null) {
            runCatching {
                val delegate = field.get(null) ?: return@runCatching
                if (isIdentityProxy(delegate)) return@runCatching
                field.set(
                    null,
                    Proxy.newProxyInstance(
                        iface.classLoader,
                        arrayOf(iface),
                        IdentityRewriteHandler(delegate)
                    )
                )
                cachePatched = true
            }.onFailure {
                Log.w(TAG, "patch InputMethodManager service cache failed", it)
            }
        }
        installCachedManagerProxy(
            contexts,
            Context.INPUT_METHOD_SERVICE,
            iface.name,
            "InputMethodManager"
        )
        Log.d(TAG, "InputMethodManager cache proxy=$cachePatched")
    }

    private fun isIdentityProxy(value: Any): Boolean =
        Proxy.isProxyClass(value.javaClass) &&
            Proxy.getInvocationHandler(value) is IdentityRewriteHandler

    private fun installPackageManagerProxy(contexts: List<Context>) {
        val threadClass = loadClass("android.app.ActivityThread")
        val getPackageManager = declaredMethod(threadClass, "getPackageManager") ?: return
        val delegate = getPackageManager.invoke(null) ?: return
        val iface = loadClass("android.content.pm.IPackageManager")
        val proxy = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            PackageQueryHandler(delegate)
        )
        ipmProxy = proxy

        var replaced = false
        assignableFields(threadClass, iface, staticOnly = true).forEach { field ->
            runCatching {
                field.set(null, proxy)
                replaced = true
            }
        }
        Log.d(TAG, "IPackageManager proxy installed=$replaced")

        contexts.forEach { patchApplicationPackageManagers(it) }
        systemContext()?.let { patchApplicationPackageManagers(it) }
    }

    private fun patchApplicationPackageManagers(context: Context) {
        val proxy = ipmProxy ?: return
        val appPmClass = runCatching {
            loadClass("android.app.ApplicationPackageManager")
        }.getOrNull() ?: return
        var current: Context? = context
        while (current != null) {
            runCatching {
                val pm = current!!.packageManager
                if (appPmClass.isInstance(pm)) {
                    val field = declaredField(appPmClass, "mPM") ?: return@runCatching
                    field.set(pm, proxy)
                }
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }

    private fun systemContext(): Context? = runCatching {
        val threadClass = loadClass("android.app.ActivityThread")
        val thread = method(threadClass, "currentActivityThread")
            ?.invoke(null)
            ?: return@runCatching null
        method(threadClass, "getSystemContext")?.invoke(thread) as? Context
    }.getOrNull()

    private fun invokeDelegate(delegate: Any, method: Method, args: Array<Any?>?): Any? =
        try {
            method.invoke(delegate, *(args ?: emptyArray()))
        } catch (t: InvocationTargetException) {
            throw t.targetException
        }

    /**
     * NotificationManager calls are virtualized end-to-end: package arguments
     * become the host package (UID validation), tags/channel ids get a virtual
     * namespace, target icons are re-rendered, and target PendingIntents are
     * routed through a host launch trampoline. See ContainerNotificationBridge.
     */
    private class NotificationRewriteHandler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val env = notificationEnv()
                ?: return invokeDelegate(delegate, method, args)
            val callArgs = ContainerNotificationBridge.rewriteCall(method.name, args, env)
            val result = invokeDelegate(delegate, method, callArgs)
            return ContainerNotificationBridge.rewriteResult(method.name, result, env)
        }
    }

    /**
     * Generic system-service package attribution rewrite. Target package
     * arguments become the host package accepted by system_server; results
     * are mapped back so target code continues to see its own identity.
     */
    private class IdentityRewriteHandler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "ContainerService(${delegate.javaClass.name})"
                    else -> null
                }
            }
            val rewrittenArgs = args?.map(::rewriteArgument)?.toTypedArray()
            val result = invokeDelegate(delegate, method, rewrittenArgs)
            return rewriteResult(result)
        }

        private fun rewriteArgument(value: Any?): Any? {
            val target = targetPackage
            val host = hostPackage
            if (target.isEmpty() || target == host) return value
            return when (value) {
                is String -> if (value == target) host else value
                is Array<*> -> SystemServiceArrayRewrite.rewrite(
                    value,
                    ::rewriteArgument
                )
                is Collection<*> -> value.map(::rewriteArgument)
                else -> rewriteAttributionSource(value, target, host) ?: value
            }
        }

        private fun rewriteResult(value: Any?): Any? {
            val target = targetPackage
            val host = hostPackage
            if (target.isEmpty() || target == host) return value
            return when (value) {
                is String -> if (value == host) target else value
                is Array<*> -> SystemServiceArrayRewrite.rewrite(
                    value,
                    ::rewriteResult
                )
                is Iterable<*> -> value.map(::rewriteResult)
                else -> rewritePackageOps(value)
                    ?: rewriteAttributionSource(value, host, target)
                    ?: value
            }
        }

        private fun rewriteAttributionSource(
            value: Any?,
            fromPackage: String,
            toPackage: String
        ): Any? {
            val sourceClass = findClass("android.content.AttributionSource")
                ?: return null
            if (!sourceClass.isInstance(value)) return null
            return runCatching {
                val packageName = method(sourceClass, "getPackageName")
                    ?.invoke(value) as? String
                if (packageName != fromPackage) return@runCatching value
                val builderClass = loadClass(
                    "android.content.AttributionSource\$Builder"
                )
                val builder = constructor(builderClass, sourceClass)
                    ?.newInstance(value)
                    ?: return@runCatching null
                val setPackage = method(
                    builderClass,
                    "setPackageName",
                    String::class.java
                )
                    ?: return@runCatching null
                setPackage.invoke(builder, toPackage)
                method(builderClass, "build")?.invoke(builder)
            }.getOrNull()
        }

        private fun rewritePackageOps(value: Any?): Any? {
            val packageOpsClass = findClass("android.app.AppOpsManager\$PackageOps")
                ?: return null
            if (!packageOpsClass.isInstance(value)) return null
            return runCatching {
                val packageName = method(packageOpsClass, "getPackageName")
                    ?.invoke(value) as? String
                    ?: return@runCatching null
                if (packageName != hostPackage) return@runCatching value
                val uid = method(packageOpsClass, "getUid")?.invoke(value) as Int
                @Suppress("UNCHECKED_CAST")
                val ops = method(packageOpsClass, "getOps")?.invoke(value)
                    as List<Any?>
                constructor(
                    packageOpsClass,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                    List::class.java
                )
                    ?.newInstance(targetPackage, uid, ops)
            }.getOrNull()
        }
    }

    private fun notificationEnv(): ContainerNotificationBridge.Env? {
        val context = hostContext ?: systemContext() ?: return null
        val target = targetPackage
        if (target.isEmpty()) return null
        return ContainerNotificationBridge.Env(
            targetPackage = target,
            instanceId = targetInstance,
            hostPackage = hostPackage,
            resources = targetResources,
            context = context,
            launchPendingIntent = {
                launchPendingIntent(context)
            }
        )
    }

    private fun launchPendingIntent(context: Context): PendingIntent? = runCatching {
        val intent = Intent()
            .setClassName(hostPackage, CONTAINER_LAUNCH_PROXY)
            .putExtra("space_id", targetSpace)
            .putExtra("package", targetPackage)
            .putExtra("instance", targetInstance)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            context,
            (targetPackage + '@' + targetInstance).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.onFailure { Log.w(TAG, "notification launch PendingIntent failed", it) }
        .getOrNull()

    /**
     * AMS calls carry the caller package as a String. For startActivity and
     * IntentSender APIs this must be the host package; rewriting keeps the
     * target package visible only to the app/native layer.
     */
    private class AmRewriteHandler(private val delegate: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (aiInfoCaptureActive &&
                method.name.startsWith("enterPictureInPictureMode")
            ) {
                Log.d(TAG, "blocked PiP during AI info capture via ${method.name}")
                return when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            val isFinish = method.name.startsWith("finishActivity")
            val isMinimize = method.name.startsWith("moveTaskToBack")
            val activeHook = rootExitHook.get()
            if (method.name.startsWith("startActivity")) {
                val startIntent =
                    args.orEmpty().filterIsInstance<Intent>().firstOrNull()
                val webUrl = startIntent?.let(::webUrlFromViewIntent)
                val context = hostContext ?: systemContext()
                if (webUrl != null && context != null) {
                    Log.w(TAG, "redirected external web startActivity into NestSpace")
                    runCatching {
                        RuntimeHostHooks.openBrowser?.invoke(context, webUrl)
                    }
                    return when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        else -> null
                    }
                }
            }
            if (activeHook != null) {
                if (method.name.startsWith("startActivity")) {
                    val intent = args.orEmpty().filterIsInstance<Intent>().firstOrNull()
                    val isHomeExit = intent?.action == Intent.ACTION_MAIN &&
                        intent.component == null &&
                        intent.data == null
                    if (isHomeExit &&
                        activeHook.interceptTaskExit &&
                        rootExitHook.compareAndSet(activeHook, null)
                    ) {
                        Log.d(TAG, "intercepted container home jump via startActivity")
                        activeHook.callback()
                        return when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> true
                            Int::class.javaPrimitiveType -> 0
                            else -> null
                        }
                    }
                } else if (listOf("finish", "task", "back", "root", "minimize")
                        .any { method.name.contains(it, ignoreCase = true) }
                ) {
                    Log.d(TAG, "AMS while exit hook armed: ${method.name}")
                }
            }
            if (isFinish || isMinimize) {
                val hook = activeHook
                val shouldIntercept = hook != null &&
                    ((isFinish && hook.interceptFinish) ||
                        (isMinimize && hook.interceptTaskExit))
                if (shouldIntercept && rootExitHook.compareAndSet(hook, null)) {
                    Log.d(TAG, "intercepted root container exit via ${method.name}")
                    hook.callback()
                    return when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> true
                        Int::class.javaPrimitiveType -> 0
                        else -> null
                    }
                }
            }
            val target = targetPackage
            val host = hostPackage
            val rewrite = args != null && target.isNotEmpty() && target != host
            if (method.name.startsWith("startActivity")) {
                Log.d(
                    TAG,
                    "AMS ${method.name} strings=" +
                        args?.filterIsInstance<String>().orEmpty()
                )
            }
            val callArgs = if (rewrite) {
                args?.copyOf()?.also { copy ->
                    for (i in copy.indices) {
                        if (copy[i] == target) copy[i] = host
                    }
                }
            } else {
                args
            }
            val result = invokeDelegate(delegate, method, callArgs)
            rewriteProcessIdentity(method.name, callArgs, result)
            return result
        }
    }

    /**
     * ActivityManager reports the real manifest process
     * (`com.coke.nestspace:cN`) even after argv0 and ActivityThread are
     * virtualized. Target SDKs commonly cache this result at startup to decide
     * whether they are the application's main process. That decision gates
     * AppLog, device registration and request common parameters, so expose the
     * target process identity in framework process snapshots as well.
     */
    private fun rewriteProcessIdentity(
        methodName: String,
        args: Array<Any?>?,
        result: Any?
    ) {
        val target = targetPackage
        val host = hostPackage
        if (target.isEmpty() || target == host) return
        val myPid = Process.myPid()
        val processInfoClass = android.app.ActivityManager.RunningAppProcessInfo::class.java

        fun rewrite(info: Any?) {
            if (!processInfoClass.isInstance(info)) return
            val running = info as android.app.ActivityManager.RunningAppProcessInfo
            val belongsToHost = running.pid == myPid ||
                running.processName == host ||
                running.processName?.startsWith("$host:") == true ||
                running.pkgList?.contains(host) == true
            if (belongsToHost) {
                running.processName = target
                running.pkgList = arrayOf(target)
            }
        }

        when {
            methodName == "getRunningAppProcesses" &&
                result is Iterable<*> -> result.forEach(::rewrite)
            methodName in setOf(
                "getMyMemoryState",
                "getRunningAppProcessInfo"
            ) -> args.orEmpty().forEach(::rewrite)
        }
    }

    /**
     * Answers target package queries locally. Native loaders (SoLoader) call
     * IPackageManager directly; the host implementation does not know the
     * virtual package and throws NameNotFoundException.
     *
     * A real ApplicationPackageManager is the object target code receives from
     * Context.getPackageManager() (some SDKs read its mPM field from JNI and
     * crash on any other PackageManager implementation). Its calls therefore
     * arrive here; component and intent queries are answered by the container's
     * PackageManager wrapper, with a thread-local guard so its internal
     * delegation to the host package manager does not recurse into this proxy.
     */
    private class PackageQueryHandler(private val delegate: Any) : InvocationHandler {

        private class Routed(val value: Any?)

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            val values = args ?: emptyArray()
            if (packageManagerDelegation.get()) {
                return invokeDelegate(delegate, method, values)
            }
            val legacy = legacyPackageManager
            if (legacy != null) {
                val routed = routeThroughPackageManager(legacy, method.name, values)
                if (routed !== NOT_ROUTED) return (routed as Routed).value
            }
            val target = targetPackage
            if (target.isEmpty()) {
                return invokeDelegate(delegate, method, values)
            }
            val name = method.name
            val queried = values.firstNotNullOfOrNull {
                when (it) {
                    is String -> it
                    is VersionedPackage -> it.packageName
                    else -> null
                }
            }
            val virtualRegistry = registry
            // checkPermission(permission, packageName, ...): the first string
            // is the permission, so handle it before the package lookup.
            if (name.startsWith("checkPermission")) {
                val strings = values.filterIsInstance<String>()
                val permission = strings.getOrNull(0)
                val pkg = strings.getOrNull(1)
                val permissions = virtualPermissions
                if (permission != null && pkg != null && permissions != null &&
                    (pkg == target || virtualRegistry?.isVirtualPackage(pkg) == true)
                ) {
                    return permissions.checkPackagePermission(pkg, permission)
                }
            }
            if (queried != null &&
                (queried == target || virtualRegistry?.isVirtualPackage(queried) == true)
            ) {
                val packageInfo = if (queried == target) {
                    targetPackageInfo
                } else {
                    virtualRegistry?.findPackageInfo(queried)
                }
                val appInfo = if (queried == target) {
                    targetAppInfo
                } else {
                    virtualRegistry?.findApplicationInfo(queried)
                }
                when {
                    name.startsWith("getPackageInfo") -> packageInfo?.let { return it }
                    name.startsWith("getApplicationInfo") -> appInfo?.let { return it }
                    name.startsWith("getPackageUid") -> {
                        if (queried == target) return Process.myUid()
                        virtualRegistry?.packageUid(queried)?.let { return it }
                    }
                }
            }
            val uid = values.firstOrNull() as? Int
            if (uid != null) {
                val packageForUid = when {
                    uid == Process.myUid() -> target
                    else -> registry?.packageForUid(uid)
                }
                if (packageForUid != null) {
                    when (name) {
                        "getPackagesForUid" -> return arrayOf(packageForUid)
                        "getNameForUid" -> return packageForUid
                    }
                }
            }
            return invokeDelegate(delegate, method, values)
        }

        private fun routeThroughPackageManager(
            pm: PackageManager,
            name: String,
            args: Array<Any?>
        ): Any? {
            fun intFlags(value: Any?): Int = when (value) {
                is Int -> value
                is Long -> value.toInt()
                else -> 0
            }

            fun viaPm(block: () -> Any?): Any = Routed(viaDelegation { block() })

            // IPackageManager component lookups return null when absent;
            // ApplicationPackageManager turns null into NameNotFoundException.
            // The wrapper throws the checked exception, which an AIDL proxy
            // cannot propagate, so convert it back to null here.
            fun viaPmNullable(block: () -> Any?): Any = Routed(
                viaDelegation {
                    try {
                        block()
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            )

            return when (name) {
                "getActivityInfo", "getReceiverInfo", "getServiceInfo", "getProviderInfo" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return NOT_ROUTED
                    if (!isContainerPackage(component.packageName)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPmNullable {
                        when (name) {
                            "getActivityInfo" -> pm.getActivityInfo(component, flags)
                            "getReceiverInfo" -> pm.getReceiverInfo(component, flags)
                            "getServiceInfo" -> pm.getServiceInfo(component, flags)
                            else -> pm.getProviderInfo(component, flags)
                        }
                    }
                }
                "resolveIntent" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { pm.resolveActivity(intent, flags) }
                }
                "resolveService" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { pm.resolveService(intent, flags) }
                }
                "queryIntentActivities" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentActivities(intent, flags)) }
                }
                "queryIntentServices" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentServices(intent, flags)) }
                }
                "queryIntentReceivers" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryBroadcastReceivers(intent, flags)) }
                }
                "queryIntentContentProviders" -> {
                    val intent = args.getOrNull(0) as? Intent ?: return NOT_ROUTED
                    if (!isContainerIntent(intent)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(2))
                    viaPm { slice(pm.queryIntentContentProviders(intent, flags)) }
                }
                "resolveContentProvider" -> {
                    val authority = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    // Route every authority through the wrapper under the
                    // delegation guard. Probing it first would recurse through
                    // the host ApplicationPackageManager's own mPM call.
                    viaPm { pm.resolveContentProvider(authority, flags) }
                }
                "getInstalledPackages" -> {
                    val flags = intFlags(args.getOrNull(0))
                    viaPm { slice(pm.getInstalledPackages(flags)) }
                }
                "getInstalledApplications" -> {
                    val flags = intFlags(args.getOrNull(0))
                    viaPm { slice(pm.getInstalledApplications(flags)) }
                }
                "getPackagesHoldingPermissions" -> {
                    val permissions = args.getOrNull(0) as? Array<*> ?: return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    val names = permissions.filterIsInstance<String>().toTypedArray()
                    viaPm { slice(pm.getPackagesHoldingPermissions(names, flags)) }
                }
                "checkSignatures" -> {
                    val first = args.getOrNull(0)
                    val second = args.getOrNull(1)
                    if (first !is String || second !is String) return NOT_ROUTED
                    if (!isContainerPackage(first) && !isContainerPackage(second)) {
                        return NOT_ROUTED
                    }
                    viaPm { pm.checkSignatures(first, second) }
                }
                "getPackageGids" -> {
                    val pkg = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    if (!isContainerPackage(pkg)) return NOT_ROUTED
                    val flags = intFlags(args.getOrNull(1))
                    viaPmNullable { pm.getPackageGids(pkg, flags) }
                }
                "getApplicationEnabledSetting" -> {
                    val pkg = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    if (!isContainerPackage(pkg)) return NOT_ROUTED
                    Routed(
                        componentStates["$pkg/*"]
                            ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                }
                "setApplicationEnabledSetting" -> {
                    val pkg = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    if (!isContainerPackage(pkg)) return NOT_ROUTED
                    val state = (args.getOrNull(1) as? Int)
                        ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    componentStates["$pkg/*"] = state
                    Routed(null)
                }
                "getComponentEnabledSetting" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return NOT_ROUTED
                    if (!isContainerPackage(component.packageName)) return NOT_ROUTED
                    Routed(
                        componentStates[component.flattenToString()]
                            ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    )
                }
                "setComponentEnabledSetting" -> {
                    val component = args.getOrNull(0) as? ComponentName ?: return NOT_ROUTED
                    if (!isContainerPackage(component.packageName)) return NOT_ROUTED
                    val state = (args.getOrNull(1) as? Int)
                        ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    componentStates[component.flattenToString()] = state
                    Routed(null)
                }
                "getInstallerPackageName" -> {
                    val pkg = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    if (!isContainerPackage(pkg)) return NOT_ROUTED
                    viaPm { pm.getInstallerPackageName(pkg) }
                }
                "getLaunchIntentSenderForPackage" -> {
                    val pkg = args.getOrNull(0) as? String ?: return NOT_ROUTED
                    if (!isContainerPackage(pkg)) return NOT_ROUTED
                    Routed(null)
                }
                else -> NOT_ROUTED
            }
        }

        private fun isContainerPackage(packageName: String?): Boolean =
            packageName != null &&
                (packageName == targetPackage || registry?.isVirtualPackage(packageName) == true)

        private fun isContainerIntent(intent: Intent): Boolean {
            val component = intent.component
            if (component != null) return isContainerPackage(component.packageName)
            return isContainerPackage(intent.`package`)
        }

        private fun slice(list: List<*>?): Any? {
            if (list == null) return null
            return runCatching {
                val sliceClass = loadClass("android.content.pm.ParceledListSlice")
                constructor(sliceClass, List::class.java)?.newInstance(list)
            }.onFailure {
                Log.w(TAG, "ParceledListSlice unavailable", it)
            }.getOrNull()
        }
    }

    private val NOT_ROUTED = Any()

    private inline fun <T> viaDelegation(block: () -> T): T {
        packageManagerDelegation.set(true)
        try {
            return block()
        } finally {
            packageManagerDelegation.set(false)
        }
    }

    private const val CONTAINER_LAUNCH_PROXY =
        "com.coke.nestspace.container.ContainerLaunchProxyActivity"
}
