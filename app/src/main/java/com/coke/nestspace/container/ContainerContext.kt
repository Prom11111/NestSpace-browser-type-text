package com.coke.nestspace.container

import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.shortcut.ContainerShortcutManager
import com.coke.nestspace.container.vpm.ContainerPermissions
import com.coke.nestspace.container.vpm.VirtualPackageRegistry


import android.app.Application
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.UserHandle
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executor
import com.coke.nestspace.container.gms.GmsBridge

/**
 * Stage B context virtualization.
 *
 * The target APK sees its own package name, code/resource paths, ApplicationInfo
 * and a per-instance data directory. File/cache/database/getDir access is
 * redirected here; SharedPreferences are namespaced by package+instance because
 * SharedPreferences files themselves are still managed by the host process.
 *
 * This is intentionally not a full ContextImpl replacement: content providers,
 * PackageManager queries, UID and native paths are Stage C/D work.
 */
class ContainerContext(
    base: Context,
    private val targetPackage: String,
    val instanceId: String,
    private val dataRoot: File,
    private val targetClassLoader: ClassLoader,
    private val targetResources: Resources,
    private val targetAppInfo: ApplicationInfo,
    private val targetRequestedPermissions: Array<String>?,
    private val baseApkPath: String,
    private val splitApkPaths: List<String>
) : ContextWrapper(base) {

    var targetApplication: Application? = null
    var targetTheme: Resources.Theme? = null
    var navigator: ContainerNavigator? = null

    /**
     * The host Activity's WindowManager. Target Activities are attached with
     * this container context, so without this override dialogs created by a
     * target (privacy prompts, bottom sheets) would use the application-level
     * WindowManager with no window token and fail with BadTokenException.
     */
    var hostWindowManager: android.view.WindowManager? = null
    private var windowManagerProxy: android.view.WindowManager? = null
    var serviceBridge: ContainerServiceBridge? = null
    var targetContentResolver: ContentResolver? = null
    var targetPackageManager: PackageManager? = null
    var broadcastManager: ContainerBroadcastManager? = null
    var jobScheduler: JobScheduler? = null
    var virtualRegistry: VirtualPackageRegistry? = null

    /** Virtual grant model for this target package (shared by PM/context). */
    val permissions: ContainerPermissions = ContainerPermissions(
        hostContext = base.applicationContext,
        targetPackage = targetPackage,
        hostPackage = HostIdentity.packageName,
        targetRequestedPermissions = targetRequestedPermissions
    )

    private val instanceKey = "${sanitize(targetPackage)}_${sanitize(instanceId)}"

    private val declaredPermissions: Set<String> by lazy {
        targetRequestedPermissions?.toSet() ?: emptySet()
    }

    /**
     * LayoutInflater.from(context) goes through getSystemService(). The base
     * host service resolves layout ids against NestSpace's resource table, so
     * any target code that inflates resources dynamically (ViewStub, adapters,
     * dialogs) would get Resources.NotFoundException. Clone the host inflater
     * onto this context so it uses the target Resources.
     */
    private val targetLayoutInflater: android.view.LayoutInflater by lazy {
        android.view.LayoutInflater.from(base).cloneInContext(this)
    }

    /**
     * AppWidgetManager validates widget ownership against the calling UID and
     * cannot see packages installed only inside the container. Sandbox it so
     * target widget bookkeeping stays local instead of raising SecurityException.
     */
    private val targetAppWidgetManager: Any? by lazy {
        ContainerAppWidgetManager.create(this)
    }

    /**
     * Target windows (notably Toast) carry the target package in
     * WindowManager.LayoutParams. The system accepts only the host package for
     * this UID, so rewrite the package before the real WindowManager sees it.
     */
    private fun targetWindowManager(): android.view.WindowManager? {
        val delegate = hostWindowManager
            ?: (super.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)
            ?: return null
        return windowManagerProxy ?: Proxy.newProxyInstance(
            android.view.WindowManager::class.java.classLoader,
            arrayOf(android.view.WindowManager::class.java)
        ) { _, method, args ->
            args?.forEach { value ->
                if (value is android.view.WindowManager.LayoutParams) {
                    if (value.packageName == null || value.packageName == targetPackage) {
                        value.packageName = HostIdentity.packageName
                    }
                }
            }
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (t: InvocationTargetException) {
                throw t.targetException
            }
        } as android.view.WindowManager
    }

    override fun getSystemService(name: String): Any? = when (name) {
        Context.LAYOUT_INFLATER_SERVICE -> targetLayoutInflater
        Context.WINDOW_SERVICE ->
            // The dynamic WindowManager proxy is only needed for NetEase's
            // Toast package rewrite. Other targets (notably Zhihu's native
            // SDKs) pass WindowManager into JNI; a java.lang.reflect.Proxy
            // there trips CheckJNI ("invalid jobject") and aborts the process.
            if (ContainerCompat.isNetEase(targetPackage)) {
                targetWindowManager() ?: super.getSystemService(name)
            } else {
                hostWindowManager ?: super.getSystemService(name)
            }
        Context.APPWIDGET_SERVICE -> targetAppWidgetManager ?: super.getSystemService(name)
        Context.JOB_SCHEDULER_SERVICE -> jobScheduler ?: super.getSystemService(name)
        // Android only knows packages installed for the real user, so
        // LocaleManager.setApplicationLocales("tv.danmaku.bili", ...) throws
        // "Unknown package" and Bilibili turns that into a startup crash.
        // Give targets a package-local no-op LocaleManager instead.
        "locale" -> virtualLocaleManager() ?: super.getSystemService(name)
        Context.AUTOFILL_SERVICE ->
            virtualAutofillManager() ?: super.getSystemService(name)
        Context.SHORTCUT_SERVICE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                virtualShortcutManager() ?: super.getSystemService(name)
            } else {
                super.getSystemService(name)
            }
        else -> super.getSystemService(name)
    }

    private var autofillManager: Any? = null
    private var shortcutManager: Any? = null

    @Synchronized
    private fun virtualShortcutManager(): Any? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        shortcutManager?.let { return it }
        return runCatching {
            HiddenApiCompat.ensure()
            val storeRoot = File(super.getFilesDir(), "vspace/shortcuts")
            val service = ContainerShortcutManager(
                context = this,
                packageName = targetPackage,
                instanceId = instanceId,
                storeRoot = storeRoot
            ).service()
            val iface = Class.forName("android.content.pm.IShortcutService")
            val managerClass = Class.forName("android.content.pm.ShortcutManager")
            val constructor = managerClass
                .getConstructor(Context::class.java, iface)
                .apply { isAccessible = true }
            constructor.newInstance(this, service).also { shortcutManager = it }
        }.onFailure {
            android.util.Log.w("ContainerContext", "ShortcutManager proxy failed", it)
        }.getOrNull()
    }

    /**
     * Views obtain AutofillManager from their context and Compose requires it
     * to be non-null. The manager created for the host application, however,
     * resolves its autofill client through ActivityThread and ends up
     * reporting the virtual package to the system autofill service, which
     * blocks the main thread for 5s per attempt ("com.deepseek.chat is not a
     * valid package") and ANRs while typing into a login form.
     *
     * Build a manager backed by the host ContextImpl rather than the host
     * Application: ContextImpl.getAutofillClient() is null, so the framework
     * treats autofill as unavailable for this context without a null manager
     * (which Compose cannot handle) and without leaving the main thread.
     */
    private fun virtualAutofillManager(): Any? {
        autofillManager?.let { return it }
        return runCatching {
            val app = baseContext.applicationContext
            val hostManager = app.getSystemService(Context.AUTOFILL_SERVICE)
                as? android.view.autofill.AutofillManager
                ?: error("host AutofillManager missing")
            val serviceField = android.view.autofill.AutofillManager::class.java
                .getDeclaredField("mService")
                .apply { isAccessible = true }
            val service = serviceField.get(hostManager)
                ?: error("host AutofillManager has no service")
            // The application's own ContextImpl has an AutofillClient and the
            // service call then carries the virtual package. A fresh derived
            // ContextImpl starts with no client; clear it explicitly to be
            // safe, so AutofillManager treats autofill as unavailable.
            val hostImpl = app.createDeviceProtectedStorageContext()
            runCatching {
                hostImpl.javaClass
                    .getDeclaredField("mAutofillClient")
                    .apply { isAccessible = true }
                    .set(hostImpl, null)
            }.onFailure {
                android.util.Log.w("ContainerContext", "clear autofill client failed", it)
            }
            val constructor = android.view.autofill.AutofillManager::class.java
                .declaredConstructors
                .firstOrNull {
                    it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Context::class.java
                }
                ?: error("AutofillManager(Context, service) constructor missing")
            constructor.isAccessible = true
            (constructor.newInstance(hostImpl, service) as? android.view.autofill.AutofillManager)
                ?.also { autofillManager = it }
        }.onFailure {
            android.util.Log.w("ContainerContext", "virtual autofill manager failed", it)
        }.getOrNull()
    }

    private var localeManager: Any? = null

    private fun virtualLocaleManager(): Any? {
        if (Build.VERSION.SDK_INT < 33) return null
        localeManager?.let { return it }
        return runCatching {
            val serviceClass = Class.forName("android.app.ILocaleManager")
            val service = Proxy.newProxyInstance(
                serviceClass.classLoader,
                arrayOf(serviceClass)
            ) { _, method, _ ->
                when (method.name) {
                    "getApplicationLocales" -> LocaleList.getEmptyLocaleList()
                    "getOverrideLocaleConfig" -> null
                    "setApplicationLocales" -> null
                    else -> defaultServiceValue(method.returnType)
                }
            }
            val managerClass = Class.forName("android.app.LocaleManager")
            val constructor = managerClass.getDeclaredConstructor(
                Context::class.java,
                serviceClass
            ).apply { isAccessible = true }
            constructor.newInstance(this, service).also { localeManager = it }
        }.getOrNull()
    }

    private fun defaultServiceValue(type: Class<*>): Any? = when (type) {
        Void.TYPE -> null
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    // Context.getSystemService(Class) is final, but it dispatches through the
    // name-based getSystemService(String), so the override above is enough.

    private fun targetDeclares(permission: String): Boolean = permission in declaredPermissions

    override fun checkSelfPermission(permission: String): Int =
        permissions.checkSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        permissions.checkCallingPermission(permission, pid, uid)

    override fun checkCallingOrSelfPermission(permission: String): Int =
        permissions.checkSelfPermission(permission)

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (permissions.checkCallingPermission(permission, pid, uid) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        throw SecurityException(message ?: "Permission denied: $permission")
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        permissions.enforce(permission, message)
    }

    /**
     * Android 17 added Context.getPermissionRequestState(String), which
     * Activity.requestPermissions uses to short-circuit requests. It is not in
     * the compile-time stubs, so declare it without `override`; ART still
     * dispatches the virtual call here on releases that have it. Values match
     * Context.PERMISSION_REQUEST_STATE_GRANTED/REQUESTABLE/UNREQUESTABLE.
     *
     * Without this, a host-granted permission would report GRANTED even after
     * the user revoked it for the virtual app, bypassing the virtual policy.
     */
    @Suppress("unused")
    fun getPermissionRequestState(permission: String): Int = when {
        permissions.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> 0
        targetDeclares(permission) -> 1
        else -> 2
    }

    private fun dir(name: String): File =
        File(dataRoot, name).apply { if (!exists()) mkdirs() }

    override fun getPackageName(): String =
        if (calledFromToast()) HostIdentity.packageName else targetPackage

    /**
     * Android 12+ rejects a Toast whose package does not belong to the caller
     * UID ("Package tv.danmaku.bili not in UID ..."). The target context still
     * has to report its virtual package everywhere else, so only the
     * Toast constructor sees the host package.
     */
    private fun calledFromToast(): Boolean {
        val frames = Throwable().stackTrace
        val limit = minOf(frames.size, 12)
        for (i in 0 until limit) {
            val name = frames[i].className
            if (name == "android.widget.Toast" || name.startsWith("android.widget.Toast$")) {
                return true
            }
        }
        return false
    }

    /**
     * Attribution (AppOps, IME, connectivity, notification) is checked against
     * the real UID. Target packages are not installed for the host UID, so
     * reporting them here makes framework services reject the call (for
     * example InputMethodManagerService refuses the input client). The host
     * package is the attribution the system can actually verify.
     */
    override fun getOpPackageName(): String = super.getOpPackageName()

    override fun getDataDir(): File = dataRoot.apply { if (!exists()) mkdirs() }

    override fun getFilesDir(): File = dir("files")

    override fun getCacheDir(): File = dir("cache")

    override fun getCodeCacheDir(): File = dir("code_cache")

    override fun getNoBackupFilesDir(): File = dir("no_backup")

    override fun getDir(name: String, mode: Int): File = dir("app_$name")

    override fun getDatabasePath(name: String): File =
        File(dir("databases"), name).apply { parentFile?.mkdirs() }

    override fun openFileInput(name: String): FileInputStream =
        FileInputStream(File(getFilesDir(), name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val file = File(getFilesDir(), name)
        file.parentFile?.mkdirs()
        return FileOutputStream(file, mode and Context.MODE_APPEND != 0)
    }

    override fun deleteFile(name: String): Boolean = File(getFilesDir(), name).delete()

    override fun fileList(): Array<String> = getFilesDir().list() ?: emptyArray()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val prefName = name ?: "default"
        return super.getSharedPreferences(prefKey(targetPackage, instanceId, prefName), mode)
    }

    override fun getExternalFilesDir(type: String?): File? {
        val hostRoot = hostExternalFilesRoot() ?: return null
        val root = File(File(hostRoot, "vspace"), instanceKey)
        val result = if (type.isNullOrBlank()) {
            File(root, "files")
        } else {
            File(File(root, "files"), type.substringAfterLast('/'))
        }
        result.mkdirs()
        return result
    }

    override fun getExternalCacheDir(): File? {
        val hostRoot = hostExternalCacheRoot() ?: return null
        return File(File(File(hostRoot, "vspace"), instanceKey), "cache").apply { mkdirs() }
    }

    private fun externalStorageDir(): File =
        android.os.Environment.getExternalStorageDirectory()

    /**
     * Do not use super.getExternalFilesDir(): the base Context can carry the
     * target package identity, and StorageManager rejects that path under the
     * host UID. The host package's own external directory is always writable.
     */
    private fun hostExternalFilesRoot(): File? {
        val root = File(externalStorageDir(), "Android/data/${HostIdentity.packageName}/files")
        if (root.isDirectory || root.mkdirs()) return root
        return runCatching { super.getExternalFilesDir(null) }.getOrNull()
    }

    private fun hostExternalCacheRoot(): File? {
        val root = File(externalStorageDir(), "Android/data/${HostIdentity.packageName}/cache")
        if (root.isDirectory || root.mkdirs()) return root
        return runCatching { super.getExternalCacheDir() }.getOrNull()
    }

    override fun getObbDir(): File = dir("obb")

    override fun getObbDirs(): Array<File?> = arrayOf(getObbDir())

    override fun getExternalFilesDirs(type: String?): Array<File?> =
        arrayOf(getExternalFilesDir(type))

    override fun getExternalCacheDirs(): Array<File?> =
        arrayOf(getExternalCacheDir())

    override fun getExternalMediaDirs(): Array<File?> =
        arrayOf(dir("external-media"))

    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == targetPackage) this else super.createPackageContext(packageName, flags)

    override fun getPackageCodePath(): String = baseApkPath

    override fun getPackageResourcePath(): String = baseApkPath

    override fun getClassLoader(): ClassLoader = targetClassLoader

    override fun getResources(): Resources = targetResources

    override fun getAssets() = targetResources.assets

    override fun getApplicationInfo(): ApplicationInfo = targetAppInfo

    override fun getApplicationContext(): Context = targetApplication ?: this

    override fun getTheme(): Resources.Theme = targetTheme ?: super.getTheme()

    /**
     * AMap's Wing/PageSupportActivity rewrites its base context through
     * createConfigurationContext (locale handling). The stock implementation
     * returns a ContextImpl backed by the HOST resources, which makes the
     * target's drawable IDs disappear. Re-wrap the new configuration around
     * the target APK's AssetManager instead.
     */
    override fun createConfigurationContext(
        overrideConfiguration: Configuration
    ): Context {
        val newBase = super.createConfigurationContext(overrideConfiguration)
        return wrapDerived(newBase)
    }

    /**
     * Contexts derived from the container context (device-protected storage,
     * display, window, attribution, user) must stay container contexts.
     * Otherwise the framework backs them with the host LoadedApk, whose
     * package name was rewritten to the target, and getExternalFilesDir()
     * builds target paths that StorageManager refuses to create for the host
     * UID ("does not contain calling package").
     */
    override fun createDeviceProtectedStorageContext(): Context =
        wrapDerived(super.createDeviceProtectedStorageContext())

    override fun createDisplayContext(display: android.view.Display): Context =
        wrapDerived(super.createDisplayContext(display))

    override fun createWindowContext(
        type: Int,
        options: Bundle?
    ): Context = wrapDerived(super.createWindowContext(type, options))

    override fun createWindowContext(
        display: android.view.Display,
        type: Int,
        options: Bundle?
    ): Context = wrapDerived(super.createWindowContext(display, type, options))

    override fun createAttributionContext(attributionTag: String?): Context =
        wrapDerived(super.createAttributionContext(attributionTag))

    private fun wrapDerived(newBase: Context): ContainerContext {
        val adjusted = Resources(
            targetResources.assets,
            newBase.resources.displayMetrics,
            newBase.resources.configuration
        )
        val wrapped = ContainerContext(
            base = newBase,
            targetPackage = targetPackage,
            instanceId = instanceId,
            dataRoot = dataRoot,
            targetClassLoader = targetClassLoader,
            targetResources = adjusted,
            targetAppInfo = targetAppInfo,
            targetRequestedPermissions = targetRequestedPermissions,
            baseApkPath = baseApkPath,
            splitApkPaths = splitApkPaths
        )
        wrapped.targetApplication = targetApplication
        wrapped.targetTheme = adjusted.newTheme()
        wrapped.navigator = navigator
        wrapped.serviceBridge = serviceBridge
        wrapped.targetContentResolver = targetContentResolver
        wrapped.targetPackageManager = targetPackageManager
        wrapped.broadcastManager = broadcastManager
        wrapped.jobScheduler = jobScheduler
        wrapped.virtualRegistry = virtualRegistry
        return wrapped
    }

    override fun startActivity(intent: Intent) {
        if (navigator?.startTargetActivity(intent, -1) == true) return
        android.util.Log.w(
            "ContainerContext",
            "startActivity falls back to host: component=${intent.component} " +
                "action=${intent.action} package=${intent.`package`}"
        )
        super.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (navigator?.startTargetActivity(intent, -1) == true) return
        super.startActivity(
            Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options
        )
    }

    override fun startService(service: Intent): ComponentName? {
        serviceBridge?.startService(service, false)?.let { return it }
        return super.startService(service)
    }

    override fun startForegroundService(service: Intent): ComponentName? {
        serviceBridge?.startService(service, true)?.let { return it }
        return super.startForegroundService(
            Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** GMS client/broker classes live in the GMS APK, not the target APK. */
    private val gmsClassLoader: ClassLoader? by lazy {
        val sourceDir = runCatching {
            getPackageManager().getApplicationInfo("com.google.android.gms", 0).sourceDir
        }.getOrNull()
        if (sourceDir.isNullOrBlank()) {
            null
        } else {
            runCatching {
                dalvik.system.DexClassLoader(sourceDir, null, null, targetClassLoader)
            }.onFailure {
                android.util.Log.w("ContainerContext", "GMS DexClassLoader failed", it)
            }.getOrNull()
        }
    }

    override fun bindService(
        service: Intent,
        conn: ServiceConnection,
        flags: Int
    ): Boolean {
        android.util.Log.d(
            "ContainerContext",
            "bindService action=${service.action} comp=${service.component} " +
                "pkg=${service.`package`}"
        )
        if (serviceBridge?.bindService(service, conn, flags) == true) return true
        return GmsBridge.bind(
            targetClassLoader,
            gmsClassLoader,
            service,
            conn,
            targetPackage,
            HostIdentity.packageName
        ) { connection ->
            super.bindService(
                Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                connection,
                flags
            )
        }
    }

    override fun bindService(
        service: Intent,
        flags: Int,
        executor: Executor,
        conn: ServiceConnection
    ): Boolean {
        android.util.Log.d(
            "ContainerContext",
            "bindService(executor) action=${service.action} comp=${service.component} " +
                "pkg=${service.`package`}"
        )
        if (serviceBridge?.bindService(service, conn, flags) == true) return true
        return GmsBridge.bind(
            targetClassLoader,
            gmsClassLoader,
            service,
            conn,
            targetPackage,
            HostIdentity.packageName
        ) { connection ->
            super.bindService(
                Intent(service).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags,
                executor,
                connection
            )
        }
    }

    override fun unbindService(conn: ServiceConnection) {
        if (serviceBridge?.unbindService(conn) == true) return
        GmsBridge.unbind(conn) { super.unbindService(it) }
    }

    override fun stopService(service: Intent): Boolean {
        serviceBridge?.stopService(service)?.let { return it }
        return super.stopService(service)
    }

    override fun getContentResolver(): ContentResolver =
        targetContentResolver ?: super.getContentResolver()

    override fun getPackageManager(): PackageManager =
        targetPackageManager ?: super.getPackageManager()

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?
    ): Intent? {
        val manager = broadcastManager
        return if (manager != null) {
            manager.register(receiver, filter, null, null, 0)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        flags: Int
    ): Intent? {
        val manager = broadcastManager
        return if (manager != null) {
            manager.register(receiver, filter, null, null, flags)
        } else {
            super.registerReceiver(receiver, filter, flags)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        val manager = broadcastManager
        return if (manager != null) {
            manager.register(receiver, filter, broadcastPermission, scheduler, 0)
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
        flags: Int
    ): Intent? {
        val manager = broadcastManager
        return if (manager != null) {
            manager.register(receiver, filter, broadcastPermission, scheduler, flags)
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        if (broadcastManager?.unregister(receiver) == true) return
        super.unregisterReceiver(receiver)
    }

    override fun sendBroadcast(intent: Intent) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, false)) return
        super.sendBroadcast(forwardToHost(intent))
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendBroadcast(forwardToHost(intent), receiverPermission)
    }

    override fun sendBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendBroadcast(forwardToHost(intent), receiverPermission, options)
    }

    override fun sendBroadcastAsUser(intent: Intent, user: UserHandle) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, false)) return
        super.sendBroadcastAsUser(forwardToHost(intent), user)
    }

    override fun sendBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        receiverPermission: String?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                false,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendBroadcastAsUser(forwardToHost(intent), user, receiverPermission)
    }

    override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(forwardToHost(intent), receiverPermission)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(
            forwardToHost(intent),
            receiverPermission,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        options: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                null,
                null,
                0,
                null,
                null,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(forwardToHost(intent), receiverPermission, options)
    }

    override fun sendOrderedBroadcast(
        intent: Intent,
        receiverPermission: String?,
        receiverAppOp: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcast(
            forwardToHost(intent),
            receiverPermission,
            receiverAppOp,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        receiverPermission: String?,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                receiverPermission,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                false
            )
        ) {
            return
        }
        super.sendOrderedBroadcastAsUser(
            forwardToHost(intent),
            user,
            receiverPermission,
            resultReceiver,
            scheduler,
            initialCode,
            initialData,
            initialExtras
        )
    }

    override fun sendStickyBroadcast(intent: Intent) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, true)) return
        if (broadcastManager == null) runCatching { super.sendStickyBroadcast(intent) }
        else android.util.Log.d(
            "ContainerContext",
            "sticky broadcast kept in container: ${intent.action}"
        )
    }

    override fun sendStickyBroadcastAsUser(intent: Intent, user: UserHandle) {
        if (sendLocal(intent, null, false, null, null, 0, null, null, true)) return
        if (broadcastManager == null) {
            runCatching { super.sendStickyBroadcastAsUser(intent, user) }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky broadcast kept in container: ${intent.action}"
            )
        }
    }

    override fun sendStickyOrderedBroadcast(
        intent: Intent,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                null,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                true
            )
        ) {
            return
        }
        if (broadcastManager == null) {
            runCatching {
                super.sendStickyOrderedBroadcast(
                    intent,
                    resultReceiver,
                    scheduler,
                    initialCode,
                    initialData,
                    initialExtras
                )
            }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky ordered broadcast kept in container: ${intent.action}"
            )
        }
    }

    override fun sendStickyOrderedBroadcastAsUser(
        intent: Intent,
        user: UserHandle,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?
    ) {
        if (sendLocal(
                intent,
                null,
                true,
                resultReceiver,
                scheduler,
                initialCode,
                initialData,
                initialExtras,
                true
            )
        ) {
            return
        }
        if (broadcastManager == null) {
            runCatching {
                super.sendStickyOrderedBroadcastAsUser(
                    intent,
                    user,
                    resultReceiver,
                    scheduler,
                    initialCode,
                    initialData,
                    initialExtras
                )
            }
        } else {
            android.util.Log.d(
                "ContainerContext",
                "sticky ordered broadcast kept in container: ${intent.action}"
            )
        }
    }

    /**
     * Marks a broadcast that the container already delivered locally before
     * handing it to the host. The host-side bridge drops marked intents so a
     * broadcast cannot be delivered to target Receivers twice.
     */
    private fun forwardToHost(intent: Intent): Intent =
        Intent(intent).putExtra(ContainerBroadcastManager.EXTRA_BRIDGE_ORIGIN, targetPackage)

    private fun sendLocal(
        intent: Intent,
        receiverPermission: String?,
        ordered: Boolean,
        resultReceiver: BroadcastReceiver?,
        scheduler: Handler?,
        initialCode: Int,
        initialData: String?,
        initialExtras: Bundle?,
        sticky: Boolean
    ): Boolean = broadcastManager?.send(
        intent,
        receiverPermission,
        ordered,
        resultReceiver,
        scheduler,
        initialCode,
        initialData,
        initialExtras,
        sticky
    ) == true

    companion object {
        /**
         * Uninstall removes the whole package, so clear preferences of every
         * instance (including stale ones from an earlier installation).
         */
        fun clearPackagePrefs(base: Context, packageName: String) {
            val prefsDir = File(base.dataDir, "shared_prefs")
            val prefix = "ns_${packageName}_"
            prefsDir.listFiles()?.forEach { file ->
                val prefName = file.name.removeSuffix(".xml")
                if (prefName.startsWith(prefix)) {
                    runCatching {
                        base.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    }
                    file.delete()
                }
            }
        }

        private fun prefKey(packageName: String, instanceId: String, name: String): String =
            "ns_${packageName}_${instanceId}_$name"

        fun externalRootFor(base: Context, packageName: String, instanceId: String): File? {
            val hostRoot = File(
                android.os.Environment.getExternalStorageDirectory(),
                "Android/data/${HostIdentity.packageName}/files"
            )
            if (!hostRoot.isDirectory && !hostRoot.mkdirs()) return null
            return File(
                File(hostRoot, "vspace"),
                "${sanitize(packageName)}_${sanitize(instanceId)}"
            )
        }

        private fun sanitize(value: String): String =
            value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

/**
 * Base context for a target Activity.
 *
 * The container's root context is backed by the host application context,
 * whose `Context.isUiContext()` is false. Activities are expected to be UI
 * contexts; AndroidX WindowManager forwards the Activity to
 * `WindowLayoutComponentImpl`, which throws
 * "Context must be a UI Context with display association" before the target
 * can draw (DeepSeek 2.7.4 does this during startup).
 *
 * `getAssociatedDisplayId()` is a hidden framework method, so it cannot be
 * declared with `override`, but the JVM dispatches calls on this wrapper to it
 * all the same. `isUiContext()` is part of the public Context API.
 */
class ContainerUiContext(
    base: Context,
    private val displayId: Int
) : ContextWrapper(base) {

    override fun isUiContext(): Boolean = true

    @Suppress("unused")
    fun getAssociatedDisplayId(): Int = displayId
}
