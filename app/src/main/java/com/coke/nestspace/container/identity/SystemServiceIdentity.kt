package com.coke.nestspace.container.identity

import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.container.notify.ContainerNotificationBridge
import com.coke.nestspace.container.vpm.ContainerPermissions
import com.coke.nestspace.container.vpm.VirtualPackageRegistry


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
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
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

    private fun installServiceProxy(
        ownerName: String,
        interfaceName: String,
        handler: (Any) -> InvocationHandler,
        label: String
    ) {
        val iface = Class.forName(interfaceName)
        val owner = Class.forName(ownerName)
        val getService = owner.getMethod("getService").apply { isAccessible = true }
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
        owner.declaredFields.forEach { field ->
            if (Modifier.isStatic(field.modifiers) && iface.isAssignableFrom(field.type)) {
                runCatching {
                    field.isAccessible = true
                    field.set(null, proxy)
                    replaced = true
                }
            }
        }
        return replaced
    }

    private fun replaceSingleton(owner: Class<*>, iface: Class<*>, proxy: Any): Boolean {
        var replaced = false
        owner.declaredFields.forEach { field ->
            if (!Modifier.isStatic(field.modifiers)) return@forEach
            if (field.type.name != "android.util.Singleton") return@forEach
            runCatching {
                field.isAccessible = true
                val singleton = field.get(null) ?: return@runCatching
                val instance = field.type.getDeclaredField("mInstance").apply { isAccessible = true }
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
        val iface = Class.forName("android.app.IAlarmManager")
        var patched = 0
        val candidates = (contexts + listOfNotNull(systemContext())).mapNotNull { context ->
            runCatching { context.getSystemService(Context.ALARM_SERVICE) }.getOrNull()
        }
        candidates.distinctBy { System.identityHashCode(it) }.forEach { manager ->
            runCatching {
                val field = manager.javaClass.declaredFields.firstOrNull {
                    iface.isAssignableFrom(it.type)
                } ?: return@forEach
                field.isAccessible = true
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

    private fun installPackageManagerProxy(contexts: List<Context>) {
        val threadClass = Class.forName("android.app.ActivityThread")
        val getPackageManager = threadClass
            .getDeclaredMethod("getPackageManager")
            .apply { isAccessible = true }
        val delegate = getPackageManager.invoke(null) ?: return
        val iface = Class.forName("android.content.pm.IPackageManager")
        val proxy = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            PackageQueryHandler(delegate)
        )
        ipmProxy = proxy

        var replaced = false
        threadClass.declaredFields.forEach { field ->
            if (Modifier.isStatic(field.modifiers) && iface.isAssignableFrom(field.type)) {
                runCatching {
                    field.isAccessible = true
                    field.set(null, proxy)
                    replaced = true
                }
            }
        }
        Log.d(TAG, "IPackageManager proxy installed=$replaced")

        contexts.forEach { patchApplicationPackageManagers(it) }
        systemContext()?.let { patchApplicationPackageManagers(it) }
    }

    private fun patchApplicationPackageManagers(context: Context) {
        val proxy = ipmProxy ?: return
        val appPmClass = runCatching {
            Class.forName("android.app.ApplicationPackageManager")
        }.getOrNull() ?: return
        var current: Context? = context
        while (current != null) {
            runCatching {
                val pm = current!!.packageManager
                if (appPmClass.isInstance(pm)) {
                    val field = appPmClass.getDeclaredField("mPM").apply { isAccessible = true }
                    field.set(pm, proxy)
                }
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }

    private fun systemContext(): Context? = runCatching {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        threadClass.getMethod("getSystemContext").invoke(thread) as? Context
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
            return invokeDelegate(delegate, method, callArgs)
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
                Class.forName("android.content.pm.ParceledListSlice")
                    .getConstructor(List::class.java)
                    .newInstance(list)
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
