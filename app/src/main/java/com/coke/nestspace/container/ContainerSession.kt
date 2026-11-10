package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.runtime.RuntimeApplicationSpec
import com.coke.nestspace.runtime.RuntimeHostHooks
import com.coke.nestspace.runtime.webUrlFromViewIntent
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.identity.ContainerIdentity
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.job.ContainerJobScheduler
import com.coke.nestspace.container.loader.ContainerClassLoader
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.loader.PluginClassPathResolver
import com.coke.nestspace.container.loader.SharedLibraries
import com.coke.nestspace.container.loader.withContainerClassLoader
import com.coke.nestspace.container.remote.ContainerProcessBrokerClient
import com.coke.nestspace.container.application.TargetApplicationFactory
import com.coke.nestspace.container.application.toRuntimeApplicationSpec
import com.coke.nestspace.runtime.plugin.RuntimeProfile
import com.coke.nestspace.runtime.plugin.RuntimeProfiles
import com.coke.nestspace.container.account.VirtualAccountManager
import com.coke.nestspace.container.runtime.NativeEnvironmentBridge
import com.coke.nestspace.container.runtime.AndroidStartupCoordinator
import com.coke.nestspace.container.runtime.AndroidStartupPhase
import com.coke.nestspace.container.runtime.AndroidStartupReport
import com.coke.nestspace.container.runtime.RuntimeCapabilityProbe
import com.coke.nestspace.container.runtime.RuntimeDiagnostics
import com.coke.nestspace.container.runtime.VirtualEnvironment
import com.coke.nestspace.container.runtime.VirtualEnvironmentFactory
import com.coke.nestspace.container.runtime.VirtualEnvironmentRegistry
import com.coke.nestspace.container.runtime.VirtualIdentitySnapshot
import com.coke.nestspace.container.runtime.VirtualEnvironmentFacts
import com.coke.nestspace.container.runtime.VirtualEnvironmentValidator
import com.coke.nestspace.container.runtime.EnvironmentValidation
import com.coke.nestspace.container.runtime.VirtualStorageProbe
import com.coke.nestspace.container.runtime.VirtualWebViewDataDirectory
import com.coke.nestspace.container.vpm.VirtualPackageRegistry


import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.webkit.WebView
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ActivityThread is process-global, but a slot process alternates between
 * NestSpace's host Activities and one virtual app. Keep the host fields so
 * they can be restored before the framework creates another host Activity.
 */
private class HostProcessState(
    private val bound: Any?,
    private val boundAppInfo: ApplicationInfo?,
    private val loadedApk: Any?,
    private val loadedApkAppInfo: ApplicationInfo?,
    private val loadedApkPackageName: String?,
    private val loadedApkDataDir: String?,
    private val hostResources: Resources?,
    private val loadedApkResources: Resources?,
    private val loadedApkResDir: String?,
    private val hostApplication: Application?,
    private val initialApplication: Application?
) {
    val description: String =
        "bound=${boundAppInfo?.packageName} loadedApk=$loadedApkPackageName " +
            "dataDir=$loadedApkDataDir initial=${initialApplication?.applicationInfo?.packageName}"

    fun attachHostFrameworkState(activity: Activity, restoreTheme: Boolean) {
        val resources = HostIdentity.resources ?: hostResources ?: loadedApkResources
        if (resources != null) {
            setField(activity, "mResources", resources)
            var context: Context? = activity.baseContext
            while (context != null) {
                setField(context, "mResources", resources)
                context = (context as? ContextWrapper)?.baseContext
            }
        }
        hostApplication?.let { setField(activity, "mApplication", it) }
        if (restoreTheme && resources != null) {
            val activityInfo = runCatching {
                var type: Class<*>? = activity.javaClass
                while (type != null) {
                    val field = runCatching {
                        type!!.getDeclaredField("mActivityInfo")
                    }.getOrNull()
                    if (field != null) {
                        field.isAccessible = true
                        return@runCatching field.get(activity) as? ActivityInfo
                    }
                    type = type.superclass
                }
                null
            }.getOrNull()
            val configuredTheme = activityInfo?.theme ?: 0
            val themeResource = configuredTheme.takeIf {
                it != 0 && runCatching { resources.getResourceName(it) }.isSuccess
            } ?: com.coke.nestspace.R.style.Theme_Virtual_Container
            activity.setTheme(themeResource)
            val activeResources = runCatching { activity.resources }.getOrNull()
            Log.d(
                "ContainerSession",
                "restored host Activity state theme=$themeResource " +
                    "package=${runCatching { resources.getResourcePackageName(themeResource) }
                        .getOrDefault("?")} " +
                    "hostSnapshot=${resources === HostIdentity.resources} " +
                    "activityPackage=${activeResources?.let {
                        runCatching { it.getResourcePackageName(themeResource) }
                            .getOrDefault("?")
                    } ?: "missing"}"
            )
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        runCatching {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val field = runCatching { type!!.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    field.set(target, value)
                    return
                }
                type = type.superclass
            }
        }.onFailure {
            Log.w("ContainerSession", "restore host Activity field $name failed", it)
        }
    }

    fun restore() {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val bindData = bound
        if (bindData != null) {
            boundAppInfo?.let { value ->
                runCatching {
                    bindData.javaClass.getDeclaredField("appInfo")
                        .apply { isAccessible = true }
                        .set(bindData, value)
                }
            }
        }
        val apk = loadedApk
        if (apk != null) {
            loadedApkAppInfo?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mApplicationInfo")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkPackageName?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mPackageName")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkDataDir?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mDataDir")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkResources?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mResources")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
            loadedApkResDir?.let { value ->
                runCatching {
                    apk.javaClass.getDeclaredField("mResDir")
                        .apply { isAccessible = true }
                        .set(apk, value)
                }
            }
        }
        initialApplication?.let { value ->
            runCatching {
                threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                    .set(thread, value)
            }
        }
        seedHostSettingsCache()
    }

    /**
     * PhoneWindow reads Settings.Global in Activity.attach(), after the host
     * base Context has already been created. Prime the process-wide cache with
     * the frozen host Application resolver so those framework reads cannot
     * inherit the target AttributionSource used by the previous target app.
     */
    private fun seedHostSettingsCache() {
        val application = hostApplication ?: return
        runCatching {
            Settings.Global.getString(
                application.contentResolver,
                "render_shadows_in_compositor"
            )
            Settings.Global.getString(
                application.contentResolver,
                "force_resizable_activities"
            )
            Log.d("ContainerSession", "seeded host Settings.Global cache")
        }.onFailure {
            Log.w("ContainerSession", "seed host Settings.Global cache failed", it)
        }
    }

    companion object {
        fun capture(
            hostResources: Resources?,
            hostApplication: Application?,
            hostApplicationInfo: ApplicationInfo?
        ): HostProcessState {
            val hostPackage = HostIdentity.packageName
            val stableHostAppInfo = hostApplication?.applicationInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: hostApplicationInfo?.takeIf { it.packageName == hostPackage }
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
            val bound = runCatching {
                threadClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                    .get(thread)
            }.getOrNull()
            val currentBoundAppInfo = runCatching {
                bound?.javaClass?.getDeclaredField("appInfo")
                    ?.apply { isAccessible = true }
                    ?.get(bound) as? ApplicationInfo
            }.getOrNull()
            val boundAppInfo = currentBoundAppInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: stableHostAppInfo
            val loadedApk = runCatching {
                bound?.javaClass?.getDeclaredField("info")
                    ?.apply { isAccessible = true }
                    ?.get(bound)
            }.getOrNull()
            val currentLoadedApkAppInfo = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mApplicationInfo")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? ApplicationInfo
            }.getOrNull()
            val loadedApkAppInfo = currentLoadedApkAppInfo
                ?.takeIf { it.packageName == hostPackage }
                ?: stableHostAppInfo
            val currentLoadedApkPackageName = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mPackageName")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()
            val loadedApkPackageName = currentLoadedApkPackageName
                ?.takeIf { it == hostPackage }
                ?: hostPackage
            val currentLoadedApkDataDir = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mDataDir")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()
            val loadedApkDataDir = stableHostAppInfo?.dataDir
                ?: currentLoadedApkDataDir
            val loadedApkResources = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mResources")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? Resources
            }.getOrNull() ?: hostResources
            val loadedApkResDir = runCatching {
                loadedApk?.javaClass?.getDeclaredField("mResDir")
                    ?.apply { isAccessible = true }
                    ?.get(loadedApk) as? String
            }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: hostApplication?.applicationInfo?.sourceDir
            val initialApplication = runCatching {
                threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                    .get(thread) as? Application
            }.getOrNull() ?: hostApplication
            Log.d(
                "ContainerSession",
                "captured host process state appInfo=${boundAppInfo?.packageName} " +
                    "loadedApk=$loadedApkPackageName dataDir=$loadedApkDataDir"
            )
            return HostProcessState(
                bound = bound,
                boundAppInfo = boundAppInfo,
                loadedApk = loadedApk,
                loadedApkAppInfo = loadedApkAppInfo,
                loadedApkPackageName = loadedApkPackageName,
                loadedApkDataDir = loadedApkDataDir,
                hostResources = hostResources,
                loadedApkResources = loadedApkResources,
                loadedApkResDir = loadedApkResDir,
                hostApplication = hostApplication,
                initialApplication = initialApplication
            )
        }
    }
}

/**
 * One live virtual-app session: target Application + ClassLoader + Resources.
 *
 * Activity hosting is one real StageAActivity per target Activity. This keeps
 * the host token real, so the target's `finish()` behaves naturally (it closes
 * only its own host and the previous host resumes). `startActivity` inside the
 * target package is intercepted by [ContainerInstrumentation] and turned into
 * another StageAActivity launch backed by this same session.
 */
class ContainerSession private constructor(
    val spec: ContainerLaunchSpec,
    internal val loader: ClassLoader,
    private val resources: Resources,
    val context: ContainerContext,
    val application: Application,
    val hostContext: Context,
    private val activityThread: Any,
    private val hostProcessState: HostProcessState,
    val virtualEnvironment: VirtualEnvironment,
    internal val runtimeProfile: RuntimeProfile?,
    private val startupCoordinator: AndroidStartupCoordinator,
    private val environmentValidation: EnvironmentValidation,
    val intents: ContainerIntentResolver,
    val packageManager: android.content.pm.PackageManager,
    /** Package-level view of every app installed in this space. */
    val virtualPackages: VirtualPackageRegistry
) {
    val id: String get() = spec.sessionId
    internal val applicationSpec = spec.toRuntimeApplicationSpec()

    /** Run target Activities without keeping the host on screen. */
    @Volatile
    var headless: Boolean = false
    var mirrorDisplayId: Int = -1

    val instrumentation: ContainerInstrumentation = ContainerInstrumentation { this }

    val broadcastManager: ContainerBroadcastManager by lazy {
        ContainerBroadcastManager(this)
    }

    private val serviceManager: ContainerServiceManager by lazy {
        ContainerServiceManager(this)
    }

    fun bindServiceDirect(intent: Intent): IBinder? = serviceManager.bindDirect(intent)

    fun unbindServiceDirect(intent: Intent): Boolean = serviceManager.unbindDirect(intent)

    fun startServiceDirect(intent: Intent, foreground: Boolean): android.content.ComponentName? =
        serviceManager.startDirect(intent, foreground)

    fun stopServiceDirect(intent: Intent): Boolean = serviceManager.stopDirect(intent)

    fun queryProviderDirect(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): ContainerProviderQuery? = providerManager.queryDirect(
        uri,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )

    fun insertProviderDirect(uri: android.net.Uri, values: android.content.ContentValues?):
        android.net.Uri? = providerManager.insertDirect(uri, values)

    fun bulkInsertProviderDirect(
        uri: android.net.Uri,
        values: Array<out android.content.ContentValues>
    ): Int = providerManager.bulkInsertDirect(uri, values)

    fun updateProviderDirect(
        uri: android.net.Uri,
        values: android.content.ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = providerManager.updateDirect(uri, values, selection, selectionArgs)

    fun deleteProviderDirect(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = providerManager.deleteDirect(uri, selection, selectionArgs)

    fun getProviderTypeDirect(uri: android.net.Uri): String? =
        providerManager.getTypeDirect(uri)

    fun callProviderDirect(
        authority: String,
        method: String,
        arg: String?,
        extras: android.os.Bundle?
    ): android.os.Bundle? = providerManager.callDirect(authority, method, arg, extras)

    fun openProviderFileDirect(
        uri: android.net.Uri,
        mode: String
    ): android.os.ParcelFileDescriptor? =
        providerManager.openFileDirect(uri, mode, null)

    fun openProviderAssetFileDirect(
        uri: android.net.Uri,
        mode: String
    ): android.content.res.AssetFileDescriptor? =
        providerManager.openAssetFileDirect(uri, mode, null)

    fun openProviderTypedAssetFileDirect(
        uri: android.net.Uri,
        mimeTypeFilter: String,
        opts: android.os.Bundle?
    ): android.content.res.AssetFileDescriptor? =
        providerManager.openTypedAssetFileDirect(uri, mimeTypeFilter, opts, null)

    fun deliverRemoteContentChange(uris: Array<android.net.Uri>, flags: Int) {
        VirtualContentObserverRegistry.deliverRemoteChange(uris.toList(), flags)
    }

    fun deliverTopActivityIntent(className: String, intent: Intent): Boolean =
        navigator?.deliverTargetIntent(className, Intent(intent)) == true

    private val providerManager: ContainerProviderManager by lazy {
        ContainerProviderManager(this)
    }

    private val jobScheduler: ContainerJobScheduler by lazy {
        ContainerJobScheduler(this)
    }

    private val targetHosts = ConcurrentHashMap<Activity, StageAActivity>()
    private val identityLock = Any()
    private var hostIdentityDepth = 0
    private var hostIdentitySuspended = false
    private var lastNativeHookCount = -1
    private var lastNativeScannedCount = -1
    private var lastNativeTargetModuleCount = -1
    private val nativeRefreshPending = AtomicBoolean(false)
    private val nativeHookExecutor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nestspace-native-hook-pump").apply { isDaemon = true }
        }
    }
    private var fastNativeHookPump: ScheduledFuture<*>? = null
    private var slowNativeHookPump: ScheduledFuture<*>? = null

    @Volatile
    var navigator: StageAActivity? = null

    val startupReport: AndroidStartupReport
        get() = startupCoordinator.report()

    init {
        context.virtualEnvironment = virtualEnvironment
        context.targetPackageManager = packageManager
        context.broadcastManager = broadcastManager
        context.jobScheduler = jobScheduler
        context.virtualRegistry = virtualPackages
        context.permissions.attachRegistry(virtualPackages)
        context.navigator = ContainerNavigator { intent, requestCode ->
            val host = navigator ?: return@ContainerNavigator false
            startTargetActivity(host, intent, requestCode)
        }
        context.targetApplication = application
        context.targetTheme = resources.newTheme()
    }

    /**
     * Mirrors Android's startup order: providers are created before
     * Application.onCreate, so libraries that initialize through a provider
     * see the target resolver. Services are created on demand.
     */
    fun initialize() {
        Log.d(TAG, "initialize session ${spec.packageName}")
        context.serviceBridge = serviceManager
        val compatAppLike = ContainerCompat.takeAppLike(application)
        if (compatAppLike != null) {
            startupCoordinator.runOptional(
                AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH
            ) {
                compatAppLike.javaClass
                    .getMethod("onBaseContextAttached", Context::class.java)
                    .invoke(compatAppLike, context)
            }?.let { Log.w(TAG, "embedded ApplicationLike attach failed", it) }
        } else {
            startupCoordinator.skip(
                AndroidStartupPhase.EMBEDDED_APPLICATION_ATTACH,
                "target does not expose an embedded ApplicationLike"
            )
        }
        TargetCrashGuard.noteSession(spec.packageName, spec.instanceId, hostContext)
        startupCoordinator.run(AndroidStartupPhase.PROCESS_APPLICATION) {
            publishAsProcessApplication()
        }
        // Keep the complete process identity on the target while its
        // Application and providers initialize, matching a normal Android
        // process. StageAActivity temporarily restores the host identity only
        // while the framework creates host components.
        startupCoordinator.run(AndroidStartupPhase.CONTENT_PROVIDERS) {
            providerManager.install()
        }
        startupCoordinator.run(AndroidStartupPhase.APPLICATION_CREATE) {
            application.onCreate()
        }
        refreshNativeHooks()
        startupCoordinator.run(AndroidStartupPhase.APPLICATION_COMPATIBILITY) {
            runtimeProfile?.afterApplicationCreated(
                applicationSpec,
                loader,
                context,
                application
            )
        }
        startupCoordinator.run(AndroidStartupPhase.HOST_BRIDGES) {
            broadcastManager.startHostBridge()
        }
        if (compatAppLike != null) {
            startupCoordinator.runOptional(AndroidStartupPhase.EMBEDDED_APPLICATION_CREATE) {
                compatAppLike.javaClass.getMethod("onCreate").invoke(compatAppLike)
            }?.let { Log.w(TAG, "embedded ApplicationLike onCreate failed", it) }
        } else {
            startupCoordinator.skip(
                AndroidStartupPhase.EMBEDDED_APPLICATION_CREATE,
                "target does not expose an embedded ApplicationLike"
            )
        }
        refreshNativeHooks()
        startupCoordinator.run(AndroidStartupPhase.STARTUP_COMPATIBILITY) {
            runtimeProfile?.afterEmbeddedApplicationCreated(
                applicationSpec,
                loader,
                context,
                application
            )
        }
        // Target Application.onCreate often registers its own uncaught
        // exception handler (crash reporting) that terminates the host process.
        // Re-install the guard so target-thread failures stay contained.
        startupCoordinator.run(AndroidStartupPhase.CRASH_GUARD) {
            TargetCrashGuard.install(force = true)
        }
        startupCoordinator.mark(AndroidStartupPhase.READY, 0L)
        startNativeHookPump()
        Log.d(TAG, "session initialized ${spec.packageName}")
    }

    fun noteFirstActivityScheduled() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_SCHEDULED)
    }

    fun noteFirstActivityOpened() {
        startupCoordinator.markOnce(AndroidStartupPhase.FIRST_ACTIVITY_OPENED)
        refreshNativeHooks()
        writeRuntimeDiagnostics()
    }

    internal fun refreshNativeHooks(scanElfFallback: Boolean = false) {
        val snapshot = NativeEnvironmentBridge.refreshHooks(scanElfFallback) ?: return
        if (snapshot.hookCount == lastNativeHookCount &&
            snapshot.scannedModuleCount == lastNativeScannedCount &&
            snapshot.targetModuleCount == lastNativeTargetModuleCount
        ) {
            return
        }
        lastNativeHookCount = snapshot.hookCount
        lastNativeScannedCount = snapshot.scannedModuleCount
        lastNativeTargetModuleCount = snapshot.targetModuleCount
        Log.d(
            TAG,
            "native hooks patched=${snapshot.hookCount} " +
                "modules=${snapshot.hookedModuleCount} " +
                "scanned=${snapshot.scannedModuleCount} " +
                "targetModules=${snapshot.targetModuleCount} " +
                "errors=${snapshot.errorCount} " +
                "virtualProcReads=${snapshot.virtualProcReadCount} " +
                "propertyReads=${snapshot.propertyReadCount} " +
                "dlopenCalls=${snapshot.dlopenCallCount}"
        )
    }

    /** Coalesces lifecycle-triggered refreshes onto the native worker. */
    internal fun scheduleNativeHookRefresh() {
        if (!nativeRefreshPending.compareAndSet(false, true)) return
        runCatching {
            nativeHookExecutor.execute {
                try {
                    refreshNativeHooksSafely(scanElfFallback = false)
                } finally {
                    nativeRefreshPending.set(false)
                }
            }
        }.onFailure {
            nativeRefreshPending.set(false)
            Log.w(TAG, "native hook refresh scheduling failed", it)
        }
    }

    private fun startNativeHookPump() {
        synchronized(nativeHookExecutor) {
            if (fastNativeHookPump != null || slowNativeHookPump != null) return
            fastNativeHookPump = nativeHookExecutor.scheduleAtFixedRate(
                { refreshNativeHooksSafely(scanElfFallback = false) },
                0L,
                250L,
                TimeUnit.MILLISECONDS
            )
            nativeHookExecutor.schedule(
                {
                    fastNativeHookPump?.cancel(false)
                    fastNativeHookPump = null
                    // The maps/ELF file fallback is intentionally one-shot
                    // after target startup. Running it on every 250 ms hook
                    // refresh reads large native libraries while they are
                    // still being loaded and can stall the whole target.
                    refreshNativeHooksSafely(scanElfFallback = true)
                    synchronized(nativeHookExecutor) {
                        if (slowNativeHookPump == null) {
                            slowNativeHookPump = nativeHookExecutor.scheduleAtFixedRate(
                                { refreshNativeHooksSafely(scanElfFallback = false) },
                                0L,
                                2L,
                                TimeUnit.SECONDS
                            )
                        }
                    }
                },
                NATIVE_ELF_FALLBACK_DELAY_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    private fun refreshNativeHooksSafely(scanElfFallback: Boolean) {
        runCatching { refreshNativeHooks(scanElfFallback) }
            .onFailure { Log.w(TAG, "native hook refresh failed", it) }
    }

    private fun writeRuntimeDiagnostics() {
        runCatching {
            val expectedPathProbe = File(virtualEnvironment.dataRoot, "files").absolutePath
            val pathMapProbe = NativeEnvironmentBridge.rewritePathForDiagnostics(
                "/data/user/0/${virtualEnvironment.packageName}/files"
            )?.takeIf { it == expectedPathProbe }
            val externalPathMapProbe = virtualEnvironment.externalDataRoot
                ?.let { externalRoot ->
                    val expected = File(externalRoot, "files").absolutePath
                    NativeEnvironmentBridge.rewritePathForDiagnostics(
                        "/storage/emulated/0/Android/data/" +
                            "${virtualEnvironment.packageName}/files"
                    )?.takeIf { it == expected }
                }
            val expectedUid = virtualEnvironment.observedUid.toString()
            val procViewProbe = NativeEnvironmentBridge.readVirtualProcForDiagnostics(
                "/proc/self/status"
            )?.takeIf { value ->
                value.lineSequence().any {
                    it.startsWith("Uid:") &&
                        it.removePrefix("Uid:").trim().split(Regex("\\s+"))
                            .all { field -> field == expectedUid }
                } && value.contains("Name:")
            }
            val propertyValueProbe = NativeEnvironmentBridge
                .readPropertyForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val propertyCallbackProbe = NativeEnvironmentBridge
                .readPropertyCallbackForDiagnostics("ro.build.version.sdk")
                ?.takeIf { it == android.os.Build.VERSION.SDK_INT.toString() }
            val snapshot = RuntimeDiagnostics.build(
                environment = virtualEnvironment,
                identity = VirtualEnvironmentRegistry.identitySnapshot(),
                validation = environmentValidation,
                startup = startupCoordinator.report(),
                native = NativeEnvironmentBridge.snapshot(),
                probe = NativeEnvironmentBridge.probe(),
                processUid = ContainerIdentity.hostUid(),
                processPid = android.os.Process.myPid(),
                headless = headless,
                pathMapProbe = pathMapProbe,
                externalPathMapProbe = externalPathMapProbe,
                procViewProbe = procViewProbe,
                propertyValueProbe = propertyValueProbe,
                propertyCallbackProbe = propertyCallbackProbe
            )
            val runtimeDir = File(virtualEnvironment.dataRoot, ".nestspace").apply { mkdirs() }
            val fileName = RuntimeDiagnostics.fileName(
                virtualEnvironment.packageName,
                virtualEnvironment.processName
            )
            val destination = File(runtimeDir, fileName)
            val temporary = File(runtimeDir, "$fileName.tmp")
            temporary.writeText(snapshot.toString(2))
            if (!temporary.renameTo(destination)) {
                destination.delete()
                check(temporary.renameTo(destination)) {
                    "cannot publish runtime diagnostics"
                }
            }
            Log.d(
                TAG,
                "runtime diagnostics written capabilities=" +
                    "${virtualEnvironment.capabilities.size} " +
                    "startupMs=${startupCoordinator.report().totalDurationMillis}"
            )
        }.onFailure {
            Log.w(TAG, "write runtime diagnostics failed", it)
        }
    }

    /**
     * publishVirtualIdentity() rewrites the host LoadedApk's package name so
     * native checks during Application class loading see the target. Keeping
     * it afterwards leaks the target name into every host ContextImpl:
     * getExternalFilesDir() then builds target paths that StorageManager
     * refuses to create for the host UID. ContainerContext carries the virtual
     * package identity for target-facing contexts, so restore the host name
     * as soon as the target Application has been attached.
     */
    private fun restoreHostLoadedApkPackageName() {
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val bound = threadClass.getDeclaredField("mBoundApplication")
                .apply { isAccessible = true }
                .get(activityThread) ?: return
            val loadedApk = bound.javaClass.getDeclaredField("info")
                .apply { isAccessible = true }
                .get(bound) ?: return
            Class.forName("android.app.LoadedApk")
                .getDeclaredField("mPackageName")
                .apply { isAccessible = true }
                .set(loadedApk, HostIdentity.packageName)
            Log.d(TAG, "restored host LoadedApk package name")
        }.onFailure { Log.w(TAG, "restore host LoadedApk package name failed", it) }
    }

    /**
     * A slot process hosts exactly one target app for its whole lifetime, so
     * the process application can be the virtual one. Some target SDKs resolve
     * the process application through `ActivityThread.currentApplication()`
     * and pass its ClassLoader to their split-install machinery. While the
     * field still points at the host Application, those splits are injected
     * into the host PathClassLoader, which has no target base APK, so plugin
     * classes referencing patched base classes fail with NoClassDefFoundError
     * (Taobao's Flexa plugins, Ele.me tab included, do exactly this).
     */
    private fun publishAsProcessApplication() {
        val threadClass = Class.forName("android.app.ActivityThread")
        runCatching {
            val field = threadClass.getDeclaredField("mInitialApplication")
            field.isAccessible = true
            field.set(activityThread, application)
        }.onFailure {
            Log.w(TAG, "publish target application failed", it)
        }
        // Security SDKs fingerprint the running package through
        // ActivityThread.mBoundApplication.appInfo: sourceDir, dataDir, package
        // name and uid. That record still points at the host APK, so a target
        // app that verifies its own signature or paths sees NestSpace instead
        // of itself. Keep it aligned with the virtual ApplicationInfo.
        runCatching {
            val boundField = threadClass.getDeclaredField("mBoundApplication")
            boundField.isAccessible = true
            val bound = boundField.get(activityThread) ?: return@runCatching
            val appInfoField = bound.javaClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField.set(bound, context.applicationInfo)
        }.onFailure {
            Log.w(TAG, "publish bound application info failed", it)
        }
    }

    /**
     * Some target libraries (SoLoader) resolve PackageManager through a host
     * Context captured by framework/cross-process components. Point those
     * contexts at the virtual PM so target package lookups do not fall through
     * to the host package manager.
     */
    fun patchHostContextPackageManager(context: Context) {
        val implClass = Class.forName("android.app.ContextImpl")
        var current: Context? = context
        while (current != null) {
            if (implClass.isInstance(current)) {
                implClass.declaredFields.forEach { field ->
                    runCatching {
                        field.isAccessible = true
                        if (PackageManager::class.java.isAssignableFrom(field.type)) {
                            field.set(current, packageManager)
                        }
                    }
                }
            }
            current = (current as? ContextWrapper)?.baseContext
        }
    }

    fun destroy() {
        nativeRefreshPending.set(false)
        nativeHookExecutor.shutdownNow()
        fastNativeHookPump = null
        slowNativeHookPump = null
        NativeEnvironmentBridge.clear()
        VirtualEnvironmentRegistry.clear(virtualEnvironment)
        suspendProcessIdentity()
        broadcastManager.destroy()
        providerManager.destroy()
        serviceManager.destroyAll()
        jobScheduler.shutdown()
        ProcessNameCompat.restorePersistent()
    }

    /**
     * Host Activities must be created with NestSpace's ActivityInfo. Otherwise
     * the framework reads the virtual app's icon/resource IDs while building
     * the host decor and crashes on the second launch in the same slot.
     */
    fun <T> withHostProcessIdentity(block: () -> T): T {
        synchronized(identityLock) {
            if (hostIdentityDepth++ == 0) {
                suspendProcessIdentityLocked()
            }
        }
        return try {
            block()
        } finally {
            synchronized(identityLock) {
                hostIdentityDepth--
            }
        }
    }

    fun suspendProcessIdentity() {
        synchronized(identityLock) {
            suspendProcessIdentityLocked()
        }
    }

    fun activateProcessIdentity() {
        synchronized(identityLock) {
            activateProcessIdentityLocked()
        }
    }

    fun attachHostFrameworkState(activity: Activity, restoreTheme: Boolean) {
        hostProcessState.attachHostFrameworkState(activity, restoreTheme)
    }

    private fun suspendProcessIdentityLocked() {
        if (hostIdentitySuspended) return
        hostProcessState.restore()
        ProcessNameCompat.restorePersistent()
        hostIdentitySuspended = true
        Log.d(
            TAG,
            "restored host process identity ${hostProcessState.description}"
        )
    }

    private fun activateProcessIdentityLocked() {
        if (!hostIdentitySuspended) return
        ProcessNameCompat.setPersistent(spec.targetProcessName)
        publishVirtualIdentity(activityThread, context)
        publishAsProcessApplication()
        restoreHostLoadedApkPackageName()
        hostIdentitySuspended = false
        Log.d(TAG, "activated target process identity")
    }

    fun createActivity(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle? = null
    ): Activity = ProcessNameCompat.withProcessName(spec.targetProcessName) {
        withContainerClassLoader(loader) {
            createActivityLocked(host, className, sourceIntent, savedState)
        }
    }

    private fun createActivityLocked(
        host: StageAActivity,
        className: String?,
        sourceIntent: Intent?,
        savedState: Bundle?,
        fallbackActivityClass: String? = null
    ): Activity {
        val info = resolveActivity(className)
            ?: throw IllegalArgumentException(
                AppLanguage.tr(
                    "找不到 Activity：${className ?: spec.launcherActivity}",
                    "Activity not found: ${className ?: spec.launcherActivity}"
                )
            )
        Log.d(
            TAG,
            "resolve activity requested=$className info=${info.name} " +
                "target=${info.targetActivity}"
        )
        val activityClass = fallbackActivityClass
            ?: (info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name)
        val targetClass = loader.loadClass(activityClass).asSubclass(Activity::class.java)
        runtimeProfile?.beforeTargetActivity(
            applicationSpec,
            loader,
            context,
            targetClass
        )
        Log.d(TAG, "activity class=${targetClass.name}")
        val target = targetClass.getDeclaredConstructor().newInstance()

        val theme = resources.newTheme().apply {
            applyStyle(resolveTheme(info), true)
        }
        val intent = Intent(sourceIntent ?: Intent())
            .setClassName(spec.packageName, info.name)

        val attach = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "attach" }
            ?: error(
                "Activity.attach not found: " +
                    "classLoader=${Activity::class.java.classLoader} " +
                    "methods=${Activity::class.java.declaredMethods.size}"
            )
        val activityBase = ContainerUiContext(
            context,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                host.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            } else {
                android.view.Display.DEFAULT_DISPLAY
            }
        )
        val args = attach.parameterTypes.mapIndexed { index, _ ->
            when (index) {
                0 -> activityBase
                1 -> activityThread
                2 -> instrumentation
                3 -> hostToken(host)
                4 -> 0
                5 -> application
                6 -> intent
                7 -> info
                8 -> spec.label
                9 -> null
                10 -> info.name
                11 -> null
                12 -> resources.configuration
                13 -> null
                14 -> null
                15 -> null
                16 -> null
                else -> null
            }
        }

        attach.isAccessible = true
        try {
            attach.invoke(target, *args.toTypedArray())
        } catch (t: Throwable) {
            val parameterTypes = attach.parameterTypes.joinToString { it.name }
            val argumentTypes = args.joinToString { it?.javaClass?.name ?: "null" }
            Log.e(
                TAG,
                "Activity.attach failed target=${targetClass.name} " +
                    "manifest=${info.name} parameters=$parameterTypes arguments=$argumentTypes",
                t
            )
            throw t
        }
        applyTargetResources(target, resources, theme)
        installVirtualTaskRoot(target)
        targetHosts[target] = host
        try {
            instrumentation.callActivityOnCreate(target, savedState)
        } catch (t: Throwable) {
            // A throwing target onCreate leaves the Activity attached to the
            // host token; without cleanup targetHosts would retain it for the
            // life of the container process.
            targetHosts.remove(target)
            runCatching { instrumentation.callActivityOnDestroy(target) }
                .onFailure { Log.w(TAG, "cleanup failed target destroy", it) }
            if (fallbackActivityClass == null && info.targetActivity != null) {
                val concreteAliasClass = runCatching {
                    loader.loadClass(info.name)
                        .takeIf { Activity::class.java.isAssignableFrom(it) }
                }.getOrNull()
                if (concreteAliasClass != null &&
                    concreteAliasClass.name != info.targetActivity
                ) {
                    Log.w(
                        TAG,
                        "alias target ${info.targetActivity} failed; " +
                            "retrying concrete alias class ${concreteAliasClass.name}",
                        t
                    )
                    return createActivityLocked(
                        host,
                        info.name,
                        sourceIntent,
                        savedState,
                        concreteAliasClass.name
                    )
                }
            }
            throw t
        }
        Log.d(
            TAG,
            "target created ${target.javaClass.name} " +
                "taskRoot=${runCatching { target.isTaskRoot() }.getOrDefault(false)} " +
                "finishing=${target.isFinishing}"
        )
        return target
    }

    fun hostFor(target: Activity): StageAActivity? = targetHosts[target]

    fun detach(target: Activity) {
        targetHosts.remove(target)
    }

    fun resolveActivity(className: String?): ActivityInfo? =
        intents.findActivity(className?.takeIf { it.isNotBlank() } ?: spec.launcherActivity)

    /**
     * Called by target Activity.startActivity / Context.startActivity.
     * Returns false for intents outside the target package so the host can
     * launch them normally.
     */
    fun startTargetActivity(host: StageAActivity, intent: Intent, requestCode: Int): Boolean {
        if (host.blockActivityLaunchFromBack()) {
            Log.d(TAG, "blocked activity launch triggered by back: ${intent.component}")
            return true
        }
        val component = intent.component
        if (component != null && component.packageName != spec.packageName) return false
        val targetPackage = intent.`package`
        if (targetPackage != null && targetPackage != spec.packageName) return false

        val info = if (component != null) {
            val explicit = resolveActivity(component.className)
            if (explicit == null) {
                Log.w(TAG, "explicit target activity not declared: ${component.className}")
                return true
            }
            explicit
        } else {
            intents.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.info
                ?: if (isSelfLauncher(intent)) resolveActivity(null) else null
        } ?: run {
            Log.w(
                TAG,
                "unresolved target activity: component=${intent.component} " +
                    "action=${intent.action} package=${intent.`package`}"
            )
            // A target-scoped Intent that the APK does not declare must not be
            // handed to the host: the host PM cannot launch the target package.
            return targetPackage == spec.packageName
        }

        // Build the component from the host ActivityRecord instead of
        // Intent(host, ...): the container replaces the process package
        // identity, so host.getPackageName() may resolve to the target app.
        val hostPackage = host.componentName?.packageName
            ?: host.applicationInfo?.packageName
            ?: HostIdentity.packageName
        val targetProcess = ContainerProcessRouter.normalize(spec.packageName, info.processName)
        val reuseExisting = requestCode < 0 &&
            (info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP ||
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK ||
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0 ||
                intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        if (targetProcess == spec.targetProcessName &&
            reuseExisting &&
            host.deliverTargetIntent(info.name, intent)
        ) {
            return true
        }
        // Mirror mode keeps one real host Activity and swaps target
        // Activities in the same process inside its Presentation. This avoids
        // creating a WindowManager task for every in-app navigation.
        if (mirrorDisplayId >= 0 &&
            host.isMirrorMode() &&
            targetProcess == spec.targetProcessName
        ) {
            val targetIntent = Intent(intent).setClassName(
                spec.packageName,
                info.name
            )
            if (host.openMirrorChildTarget(info.name, targetIntent, requestCode)) {
                return true
            }
        }
        if (targetProcess == spec.targetProcessName) {
            val currentSlot = ContainerProcessPool.currentSlot()
            val launchHostClass = when {
                info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE &&
                    currentSlot in 0 until ContainerProcessPool.SLOT_COUNT ->
                    ContainerProcessPool.isolatedActivityClass(currentSlot)
                ContainerProcessPool.isIsolatedActivity(host.javaClass) &&
                    currentSlot in 0 until ContainerProcessPool.SLOT_COUNT ->
                    ContainerProcessPool.activityClass(currentSlot)
                else -> host.javaClass
            }
            return launchOnHost(
                host,
                hostPackage,
                info,
                launchHostClass,
                targetProcess,
                intent,
                requestCode
            )
        }

        // A subprocess activity needs a host slot. Allocation may require the
        // main-process broker, so resolve it off the main thread and launch
        // once the slot is known. The launch itself is asynchronous either way.
        ContainerProcessBrokerClient.acquireAsync(
            host,
            spec.packageName,
            spec.instanceId,
            targetProcess
        ) { slot ->
            if (slot == null) {
                Log.w(TAG, "no slot for activity ${info.name} process=$targetProcess")
                return@acquireAsync
            }
            if (reuseExisting &&
                com.coke.nestspace.container.remote.ContainerRemoteProviderClient
                    .deliverActivityIntent(
                        this@ContainerSession,
                        targetProcess,
                        info.name,
                        intent
                    )
            ) {
                return@acquireAsync
            }
            launchOnHost(
                host,
                hostPackage,
                info,
                if (info.launchMode ==
                    android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
                ) {
                    ContainerProcessPool.isolatedActivityClass(slot)
                } else {
                    ContainerProcessPool.activityClass(slot)
                },
                targetProcess,
                intent,
                requestCode
            )
        }
        return true
    }

    private fun launchOnHost(
        host: StageAActivity,
        hostPackage: String,
        info: android.content.pm.ActivityInfo,
        hostClass: Class<out StageAActivity>,
        targetProcess: String,
        intent: Intent,
        requestCode: Int
    ): Boolean {
        val targetSessionId = ContainerProcessRouter.sessionId(
            spec.packageName,
            spec.instanceId,
            targetProcess
        )
        Log.d(
            TAG,
            "startTargetActivity hostPackage=$hostPackage target=${info.name} " +
                "process=$targetProcess host=${hostClass.name}"
        )
        val containerIntent = Intent()
            .setClassName(hostPackage, hostClass.name)
            .apply {
                putExtra(StageAActivity.EXTRA_SESSION_ID, targetSessionId)
                putExtra(StageAActivity.EXTRA_TARGET_PROCESS, targetProcess)
                putExtra(StageAActivity.EXTRA_APP_LABEL, spec.label)
                if (targetProcess != spec.targetProcessName) {
                    // The target slot process may not have created this
                    // subprocess session yet; let it build one from the
                    // launch descriptor instead of reporting "session lost".
                    putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, spec.packageName)
                    putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, spec.instanceId)
                }
                putExtra(StageAActivity.EXTRA_ACTIVITY_CLASS, info.name)
                putExtra(StageAActivity.EXTRA_CONTAINER_ENTRY, false)
                putExtra(
                    StageAActivity.EXTRA_PARENT_SLOT,
                    ContainerProcessPool.currentSlot()
                )
                putExtra(
                    StageAActivity.EXTRA_TARGET_INTENT,
                    Intent(intent).setClassName(spec.packageName, info.name)
                )
                putExtra(StageAActivity.EXTRA_HEADLESS, headless)
                if (info.launchMode == android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    ContainerProcessPool.isIsolatedActivity(host.javaClass)
                ) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (mirrorDisplayId >= 0) {
                    putExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, mirrorDisplayId)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            }
        return try {
            if (mirrorDisplayId >= 0) host.captureMirrorSnapshotForTransition()
            withHostIdentity(host) {
                val launchIntent = Intent(containerIntent).setClassName(
                    HostIdentity.packageName,
                    hostClass.name
                )
                Log.d(
                    TAG,
                    "host launch component=${launchIntent.component} " +
                        "flags=0x${launchIntent.flags.toString(16)}"
                )
                // Starting an Activity from a background host can move the
                // StageA task forward for a frame. Launch behind for mirror
                // sessions; the host window itself is desktop-colored so any
                // residual task transition is not black.
                val options = if (mirrorDisplayId >= 0) {
                    android.app.ActivityOptions.makeTaskLaunchBehind().toBundle()
                } else {
                    null
                }
                if (requestCode >= 0) {
                    host.startActivityForResult(launchIntent, requestCode, options)
                } else if (options != null) {
                    host.startActivity(launchIntent, options)
                } else {
                    host.startActivity(launchIntent)
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start target activity failed: ${info.name}", t)
            false
        }
    }

    fun startExternalActivity(host: StageAActivity, intent: Intent, requestCode: Int): Boolean {
        val webUrl = webUrlFromViewIntent(intent)
        if (webUrl != null) {
            return runCatching {
                RuntimeHostHooks.openBrowser?.invoke(host, webUrl)
                true
            }.getOrElse {
                Log.w(TAG, "open web intent inside NestSpace failed", it)
                false
            }
        }
        return try {
            withHostIdentity(host) {
                if (requestCode >= 0) {
                    host.startActivityForResult(Intent(intent), requestCode)
                } else {
                    host.startActivity(Intent(intent))
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start external activity failed: ${intent.component}", t)
            false
        }
    }

    /**
     * AMS validates the caller package against the process UID. The container
     * process advertises the target package to the app/native layers, so a
     * real host Activity start must temporarily restore the host package
     * identity or AMS rejects it with "package does not belong to uid".
     */
    private fun <T> withHostIdentity(host: StageAActivity, block: () -> T): T {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        val boundField = threadClass.getDeclaredField("mBoundApplication")
            .apply { isAccessible = true }
        val bound = boundField.get(thread)
        val appInfoField = bound?.javaClass?.getDeclaredField("appInfo")
            ?.apply { isAccessible = true }
        val previousAppInfo = appInfoField?.get(bound)
        val hostPackage = HostIdentity.packageName
        val hostAppInfo = runCatching {
            context.packageManager.getApplicationInfo(hostPackage, 0)
        }.getOrNull() ?: host.applicationInfo
        val base = host.baseContext
        val savedPackages = mutableListOf<Triple<java.lang.reflect.Field, Any, Any?>>()
        runCatching {
            base.javaClass.declaredFields.forEach { field ->
                if (field.type == String::class.java &&
                    field.name.contains("ackage", ignoreCase = true)
                ) {
                    field.isAccessible = true
                    savedPackages += Triple(field, base, field.get(base))
                    field.set(base, hostPackage)
                }
            }
        }
        try {
            appInfoField?.set(bound, hostAppInfo)
            Log.d(
                TAG,
                "withHostIdentity host=$hostPackage " +
                    "appInfo=${hostAppInfo?.packageName} " +
                    "base=${host.baseContext.packageName}"
            )
            return block()
        } finally {
            appInfoField?.set(bound, previousAppInfo)
            savedPackages.forEach { (field, target, value) ->
                runCatching { field.set(target, value) }
            }
        }
    }

    private fun isSelfLauncher(intent: Intent): Boolean =
        intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true &&
            intent.`package` == spec.packageName

    private fun resolveTheme(info: ActivityInfo): Int = when {
        info.theme != 0 -> info.theme
        spec.applicationTheme != 0 -> spec.applicationTheme
        else -> android.R.style.Theme_Material_Light
    }

    private fun applyTargetResources(
        activity: Activity,
        targetResources: Resources,
        theme: Resources.Theme
    ) {
        val resourcesField = ContextThemeWrapper::class.java
            .getDeclaredField("mResources")
            .apply { isAccessible = true }
        resourcesField.set(activity, targetResources)

        val themeField = ContextThemeWrapper::class.java
            .getDeclaredField("mTheme")
            .apply { isAccessible = true }
        themeField.set(activity, theme)
    }

    private fun hostToken(host: Activity): IBinder =
        Activity::class.java
            .getDeclaredField("mToken")
            .apply { isAccessible = true }
            .get(host) as IBinder

    /**
     * A target Activity is attached with the host's real activity token, so
     * framework `Activity.isTaskRoot()` asks about the host record. Launcher
     * entry points such as Bilibili's MainActivityV2 deliberately call
     * finish() when they are not the task root (the "don't open a second copy
     * from a notification" path). Inside the container every virtual Activity
     * is the root of its own virtual task, so present that view to the target.
     *
     * Activity.isTaskRoot() delegates to its private WindowControllerCallback;
     * replace only that callback and forward everything else to the original.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun installVirtualTaskRoot(target: Activity) {
        runCatching {
            val field = Activity::class.java
                .getDeclaredField("mWindowControllerCallback")
                .apply { isAccessible = true }
            val original = field.get(target)
            val callbackClass = Class.forName(
                "android.view.Window\$WindowControllerCallback"
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if (method.name == "isTaskRoot") {
                    true
                } else if (method.declaringClass == Any::class.java) {
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(original)
                        "equals" -> proxyOrSelf(original, args)
                        else -> "VirtualWindowController"
                    }
                } else {
                    runCatching {
                        method.invoke(original, *(args ?: emptyArray()))
                    }.getOrNull()
                }
            }
            field.set(target, proxy)
        }.onFailure { Log.w(TAG, "virtual task-root compat failed", it) }
    }

    private fun proxyOrSelf(original: Any?, args: Array<out Any?>?): Boolean =
        args?.firstOrNull() === original

    companion object {
        private const val NATIVE_ELF_FALLBACK_DELAY_SECONDS = 20L
        private const val TAG = "ContainerSession"


        fun create(host: Context, spec: ContainerLaunchSpec): ContainerSession {
            ContainerLaunchProgress.begin()
            val startupCoordinator = AndroidStartupCoordinator(
                observer = { phase ->
                    Log.d(
                        TAG,
                        "startup phase=${phase.phase} outcome=${phase.outcome} " +
                            "durationMs=${phase.durationMillis} " +
                            "detail=${phase.detail.orEmpty()} " +
                            "failure=${phase.failure.orEmpty()}"
                    )
                    ContainerLaunchProgress.phaseCompleted(phase.phase)
                },
                phaseStartedObserver = ContainerLaunchProgress::phaseStarted
            )
            val classLoaderStartedAt = SystemClock.elapsedRealtime()
            HiddenApiCompat.ensure()
            val hostProcessState = HostProcessState.capture(
                hostResources = HostIdentity.resources
                    ?: runCatching { host.resources }.getOrNull(),
                hostApplication = HostIdentity.application
                    ?: host.applicationContext as? Application,
                hostApplicationInfo = HostIdentity.application?.applicationInfo
            )
            TargetCrashGuard.install()
            ProcessNameCompat.setPersistent(spec.targetProcessName)
            Log.d(TAG, "create session ${spec.packageName}/${spec.instanceId}")
            val dataRoot = File(spec.dataDir).apply { mkdirs() }
            File(dataRoot, "code_cache").apply { mkdirs() }
            val apkPaths = listOf(spec.baseApkPath) + spec.splitApkPaths
            val sharedLibraryPaths = SharedLibraries.classPathEntries(
                host.applicationContext,
                apkPaths
            )
            val baseClassPath = (apkPaths + sharedLibraryPaths)
                .joinToString(File.pathSeparator)
            val pluginMount = if (spec.targetProcessName != spec.packageName) {
                PluginClassPathResolver.resolveForComponent(
                    dataRoot = dataRoot,
                    className = spec.launcherActivity,
                    supportedAbis = android.os.Build.SUPPORTED_ABIS.toList()
                )
            } else {
                PluginClassPathResolver.Mount.EMPTY
            }
            val classPath = (listOf(baseClassPath) + pluginMount.apkPaths)
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator)
            val nativeAbis = spec.nativeAbis
            val preferredNativeAbi = NativeLibs.preferredHostAbi(host)
            val nativeAbi = NativeLibs.supportedAbi(nativeAbis, preferredNativeAbi)
            val nativeCacheRoot = File(
                host.applicationContext.filesDir,
                "vspace/native-cache"
            )
            val legacyNativeLibs = File(dataRoot, "native-libs")
            val installedNativeLibs = spec.installedNativeLibraryDir
                ?.let(::File)
                ?.takeIf { dir ->
                    dir.isDirectory &&
                        dir.listFiles()?.any {
                            it.isFile && it.name.endsWith(".so") && it.canRead()
                        } == true
                }
            val sharedNativeLibs = NativeLibs.extractIfNeeded(
                apkPaths,
                nativeAbis,
                nativeCacheRoot,
                emptySet(),
                preferredNativeAbi
            )
            val nativeLibs = installedNativeLibs
                ?: sharedNativeLibs
                ?: legacyNativeLibs.takeIf { dir ->
                dir.listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true
            }
            if (sharedNativeLibs != null) {
                // The current session has not loaded a library yet, so the old
                // per-instance copy can be reclaimed safely.
                runCatching { legacyNativeLibs.deleteRecursively() }
            }
            Log.d(TAG, "native libs: ${nativeLibs?.absolutePath} abi=$nativeAbi")

            val nativeSearchPath = (listOfNotNull(nativeLibs?.absolutePath) +
                pluginMount.nativeLibraryDirs)
                .distinct()
                .joinToString(File.pathSeparator)
                .ifBlank { null }
            var loader: ClassLoader = ContainerClassLoader(
                classPath,
                nativeSearchPath,
                host.classLoader
            )
            ContainerLaunchProgress.advance(LaunchProgressStage.LOADING_CODE)
            val resources = createResources(host, apkPaths + pluginMount.apkPaths)
            ContainerLaunchProgress.advance(LaunchProgressStage.LOADING_RESOURCES)
            Log.d(TAG, "resources ready")
            val appInfo = spec.applicationInfo
            appInfo.dataDir = dataRoot.absolutePath
            appInfo.sourceDir = spec.baseApkPath
            appInfo.publicSourceDir = spec.baseApkPath
            appInfo.uid = android.os.Process.myUid()
            // Slot activities opt out of predictive back in the host manifest.
            // If the target ApplicationInfo keeps enableOnBackInvokedCallback
            // enabled, ViewRootImpl's compat callback re-injects back key
            // events into itself until the stack overflows.
            runCatching {
                appInfo.javaClass
                    .getMethod(
                        "setEnableOnBackInvokedCallback",
                        Boolean::class.javaPrimitiveType
                    )
                    .invoke(appInfo, false)
            }.onFailure { first ->
                runCatching {
                    val field = appInfo.javaClass.getField("privateFlagsExt")
                    val flag = appInfo.javaClass
                        .getField("PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK")
                        .getInt(null)
                    field.setInt(appInfo, field.getInt(appInfo) and flag.inv())
                }.onFailure {
                    Log.w(TAG, "disable target OnBackInvoked failed", first)
                }
            }
            if (nativeAbi != null) {
                // ApplicationPackageManager normally fills this in from the
                // installed APK. Dynamic native loaders
                // read it through reflection and expect a non-null value.
                runCatching {
                    val field = appInfo.javaClass.getField("primaryCpuAbi")
                    field.isAccessible = true
                    field.set(appInfo, nativeAbi)
                }
            }
            if (nativeLibs != null) {
                appInfo.nativeLibraryDir = nativeLibs.absolutePath
            }
            runCatching {
                appInfo.javaClass
                    .getField("splitSourceDirs")
                    .set(appInfo, spec.splitApkPaths.toTypedArray())
            }

            // Some packers expose their decrypted runtime DEX through the
            // AppComponentFactory hook that Android normally calls while
            // creating LoadedApk. Manually attached virtual applications skip
            // that framework path, so run the hook before attaching the target.
            loader = ContainerCompat.installRuntimeClassLoader(spec, loader, appInfo)
            // Some native packers replace the VM hidden-API policy while
            // installing their runtime loader. Re-apply the exemption before
            // the Activity attachment path uses framework-private fields.
            HiddenApiCompat.ensure(force = true)
            Log.d(TAG, "session class loader: ${loader.javaClass.name}")
            startupCoordinator.mark(
                AndroidStartupPhase.CLASS_LOADER,
                SystemClock.elapsedRealtime() - classLoaderStartedAt,
                detail = "loader=${loader.javaClass.simpleName} nativeLibs=${nativeLibs != null}"
            )

            val context = ContainerContext(
                base = host.applicationContext,
                targetPackage = spec.packageName,
                instanceId = spec.instanceId,
                dataRoot = dataRoot,
                targetClassLoader = loader,
                targetResources = resources,
                targetAppInfo = appInfo,
                targetRequestedPermissions = spec.packageInfo.requestedPermissions,
                baseApkPath = spec.baseApkPath,
                splitApkPaths = spec.splitApkPaths
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val suffix = VirtualWebViewDataDirectory.suffix(
                    spec.packageName,
                    spec.instanceId,
                    spec.targetProcessName
                )
                runCatching { WebView.setDataDirectorySuffix(suffix) }
                    .onSuccess {
                        Log.d(
                            TAG,
                            "WebView data directory suffix configured process=${spec.targetProcessName}"
                        )
                    }
                    .onFailure {
                        Log.w(TAG, "WebView data directory suffix failed", it)
                    }
            }
            // Application.attachBaseContext runs before Application.onCreate and
            // may query package metadata. Publish the virtual PackageManager
            // before attaching so that code sees the target APK's version,
            // permissions and components rather than the host package's.
            ContainerLaunchProgress.advance(LaunchProgressStage.PREPARING_IDENTITY)
            val intents = ContainerIntentResolver(
                packageName = spec.packageName,
                launcherActivity = spec.launcherActivity,
                activities = spec.activities,
                services = spec.services,
                receivers = spec.receivers,
                providers = spec.providers,
                activityFilters = spec.activityFilters,
                serviceFilters = spec.serviceFilters,
                receiverFilters = spec.receiverFilters,
                contentResolver = {
                    context.targetContentResolver ?: host.applicationContext.contentResolver
                }
            )
            val virtualPackages = VirtualPackageRegistry(
                context = host.applicationContext,
                currentPackage = spec.packageName,
                currentInstanceId = spec.instanceId,
                spaceId = spec.spaceId,
                contentResolver = {
                    context.targetContentResolver ?: host.applicationContext.contentResolver
                }
            )
            context.virtualRegistry = virtualPackages
            context.permissions.attachRegistry(virtualPackages)
            // Install the identity proxies before attaching the target
            // Application: the PackageManager handed to it must already be an
            // ApplicationPackageManager with mPM pointing at the container's
            // IPackageManager (some security SDKs read that field from JNI).
            SystemServiceIdentity.install(
                spec = spec,
                hostPackage = HostIdentity.packageName,
                contexts = listOf(host.applicationContext, context),
                targetResources = resources,
                registry = virtualPackages,
                permissions = context.permissions
            )
            val virtualPackageManager = ContainerPackageManager(
                host.applicationContext,
                spec,
                resources,
                intents,
                virtualPackages,
                context.permissions
            )
            // The IPackageManager proxy routes component/intent queries
            // through the virtual PackageManager wrapper.
            SystemServiceIdentity.attachPackageManager(virtualPackageManager)
            val serviceContexts = listOf(host.applicationContext, context)
            val accountBasicVerified = VirtualAccountManager.install(
                contexts = serviceContexts,
                targetPackage = spec.packageName,
                instanceId = spec.instanceId
            )

            val storageProbe = VirtualStorageProbe.probe(context, dataRoot)
            val capabilityReport = RuntimeCapabilityProbe.probe(
                availableSubprocessSlots = ContainerProcessPool.slotLimit(host.applicationContext),
                storage = storageProbe,
                accountBasicVerified = accountBasicVerified,
                pluginClassPathMounted = pluginMount.mounted
            )
            val virtualEnvironment = VirtualEnvironmentFactory.create(
                spec = spec,
                nativeLibraryDir = nativeLibs?.absolutePath,
                runtimeCapabilities = capabilityReport
            )
            context.virtualEnvironment = virtualEnvironment
            VirtualEnvironmentRegistry.install(virtualEnvironment)
            NativeEnvironmentBridge.install(
                virtualEnvironment,
                VirtualEnvironmentRegistry.identitySnapshot()
            )
            NativeEnvironmentBridge.snapshot()?.let { native ->
                Log.d(
                    TAG,
                    "native environment configured hostUid=${native.configuredHostUid} " +
                        "observedUid=${native.configuredObservedUid} " +
                        "processUid=${native.processUid} pageSize=${native.pageSize}"
                )
            }
            Log.d(
                TAG,
                "virtual environment installed package=${virtualEnvironment.packageName} " +
                    "instance=${virtualEnvironment.instanceId} " +
                    "hostUid=${virtualEnvironment.hostUid} " +
                    "observedUid=${virtualEnvironment.observedUid} " +
                    "nativeLibs=${virtualEnvironment.nativeLibraryDir != null} " +
                    "capabilities=${virtualEnvironment.capabilities.joinToString(",")}"
            )
            capabilityReport.issues.forEach { Log.w(TAG, "runtime capability: $it") }

            // Hand target code a real ApplicationPackageManager: some SDKs
            // (Pinduoduo's libpdd_secure) read its mPM field from JNI and abort
            // when the object is any other PackageManager implementation.
            val packageManager = host.applicationContext.packageManager
            context.targetPackageManager = packageManager
            // Application.attachBaseContext may read Settings.Secure before
            // providers are installed. Publish the virtual resolver first so
            // device SDKs see this instance's Android ID on their first read.
            context.targetContentResolver = VirtualContentResolver(
                host.applicationContext,
                host.applicationContext.contentResolver,
                spec.providers
                    .flatMap { provider ->
                        provider.authority
                            ?.split(';')
                            .orEmpty()
                            .filter(String::isNotBlank)
                    }
                    .toSet(),
                spec.packageName,
                spec.instanceId
            )
            VirtualSettingsCache.seedAndroidId(context)
            (context.targetContentResolver as? VirtualContentResolver)
                ?.virtualAndroidId
                ?.takeIf { it.isNotBlank() }
                ?.let { androidId ->
                    VirtualEnvironmentRegistry.publishIdentity(
                        virtualEnvironment,
                        VirtualIdentitySnapshot(
                            revision = 1,
                            androidId = androidId,
                            clientUdid = null,
                            deviceId = null,
                            installId = null,
                            source = "Settings.Secure overlay"
                        )
                    )
                    NativeEnvironmentBridge.publishIdentity(
                        VirtualEnvironmentRegistry.identitySnapshot()
                    )
                    Log.d(TAG, "virtual identity revision=1 androidId=present")
                }

            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)
            // Native packers (SecNeo/Bangcle) run package checks in JNI_OnLoad
            // while the target Application class is being loaded. Publish the
            // virtual identity first, otherwise those checks see the host
            // package and abort with NameNotFoundException.
            publishVirtualIdentity(activityThread, context)
            TargetWebViewIdentityHook.install(context)
            val runtimeSpec = spec.toRuntimeApplicationSpec()
            val runtimeProfile = RuntimeProfiles.resolve(
                runtimeSpec,
                loader
            )
            Log.d(
                TAG,
                "runtime profile resolved=${runtimeProfile?.id ?: "none"} " +
                    "package=${runtimeSpec.getPackageName()} " +
                    "version=${runtimeSpec.getVersionCode()}"
            )
            runtimeProfile?.beforeApplicationAttach(
                runtimeSpec,
                loader,
                context
            )
            ContainerLaunchProgress.advance(LaunchProgressStage.ATTACHING_APPLICATION)
            val applicationAttachStartedAt = SystemClock.elapsedRealtime()
            val application = ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    attachApplication(spec, loader, context)
                }
            }
            startupCoordinator.mark(
                AndroidStartupPhase.APPLICATION_ATTACH,
                SystemClock.elapsedRealtime() - applicationAttachStartedAt,
                detail = "application=${application.javaClass.simpleName}"
            )
            Log.d(TAG, "application attached: ${application.javaClass.name}")
            val deviceProtectedContext = context.createDeviceProtectedStorageContext()
            val environmentValidation = VirtualEnvironmentValidator.validate(
                virtualEnvironment,
                VirtualEnvironmentFacts(
                    contextPackageName = context.packageName,
                    applicationPackageName = application.packageName,
                    applicationUid = application.applicationInfo.uid,
                    processUid = ContainerIdentity.hostUid(),
                    processPid = android.os.Process.myPid(),
                    contextDataDir = context.dataDir.absolutePath,
                    applicationDataDir = application.applicationInfo.dataDir,
                    contextIsDeviceProtected = deviceProtectedContext.isDeviceProtectedStorage,
                    deviceProtectedDataDir = deviceProtectedContext.dataDir.absolutePath,
                    nativeSnapshot = NativeEnvironmentBridge.snapshot()
                )
            )
            if (environmentValidation.isConsistent) {
                Log.d(TAG, "virtual environment validation passed")
            } else {
                environmentValidation.issues.forEach { issue ->
                    Log.w(TAG, "virtual environment ${issue.severity} ${issue.code}: ${issue.message}")
                }
            }

            return ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    ContainerSession(
                        spec = spec,
                        loader = loader,
                        resources = resources,
                        context = context,
                        application = application,
                        hostContext = host.applicationContext,
                        activityThread = activityThread,
                        hostProcessState = hostProcessState,
                        virtualEnvironment = virtualEnvironment,
                        runtimeProfile = runtimeProfile,
                        startupCoordinator = startupCoordinator,
                        environmentValidation = environmentValidation,
                        intents = intents,
                        packageManager = packageManager,
                        virtualPackages = virtualPackages
                    ).also {
                        it.patchHostContextPackageManager(host.applicationContext)
                        it.patchHostContextPackageManager(host)
                        it.initialize()
                    }
                }
            }
        }

        /**
         * Makes native startup checks see the target package before the target
         * Application class is loaded. Static initializers of hardened apps
         * (SecNeo's libDexHelper) call PackageManager from JNI_OnLoad, which
         * runs during class loading and before attachBaseContext.
         */
        private fun publishVirtualIdentity(activityThread: Any, context: Context) {
            val threadClass = Class.forName("android.app.ActivityThread")

            runCatching {
                val boundField = threadClass.getDeclaredField("mBoundApplication")
                    .apply { isAccessible = true }
                val bound = boundField.get(activityThread) ?: return@runCatching
                val appInfoField = bound.javaClass.getDeclaredField("appInfo")
                    .apply { isAccessible = true }
                appInfoField.set(bound, context.applicationInfo)

                // Some security SDKs read the LoadedApk mirror instead of
                // AppBindData.appInfo.
                val loadedApk = runCatching {
                    bound.javaClass.getDeclaredField("info")
                        .apply { isAccessible = true }
                        .get(bound)
                }.getOrNull()
                if (loadedApk != null) {
                    val loadedApkClass = Class.forName("android.app.LoadedApk")
                    runCatching {
                        loadedApkClass.getDeclaredField("mApplicationInfo")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.applicationInfo)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mPackageName")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.packageName)
                    }
                    runCatching {
                        loadedApkClass.getDeclaredField("mDataDir")
                            .apply { isAccessible = true }
                            .set(loadedApk, context.applicationInfo.dataDir)
                    }
                }
            }.onFailure { Log.w(TAG, "prepublish appInfo failed", it) }

            runCatching {
                val temp = Application()
                val attachBase = ContextWrapper::class.java
                    .getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }
                attachBase.invoke(temp, context)
                val field = threadClass.getDeclaredField("mInitialApplication")
                    .apply { isAccessible = true }
                field.set(activityThread, temp)
                Log.d(TAG, "prepublished initial application (temp)")
            }.onFailure { Log.w(TAG, "prepublish initial application failed", it) }

            // Native packers also query ActivityThread.getSystemContext()
            // directly. Point its PackageManager/ApplicationInfo at the
            // container so package checks resolve to the target APK.
            runCatching {
                // getSystemContext is an instance method on current AOSP builds.
                val systemContext = threadClass.getMethod("getSystemContext")
                    .invoke(activityThread)
                val packageManager = context.packageManager
                var pmFields = 0
                var appInfoFields = 0
                systemContext.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    when {
                        PackageManager::class.java.isAssignableFrom(field.type) -> {
                            field.set(systemContext, packageManager)
                            pmFields++
                        }
                        field.type == ApplicationInfo::class.java -> {
                            field.set(systemContext, context.applicationInfo)
                            appInfoFields++
                        }
                    }
                }
                Log.d(
                    TAG,
                    "prepublished system context: pmFields=$pmFields appInfoFields=$appInfoFields"
                )
            }.onFailure { Log.w(TAG, "prepublish system context failed", it) }
        }

        private fun attachApplication(
            spec: ContainerLaunchSpec,
            loader: ClassLoader,
            context: Context
        ): Application {
            val applicationSpec = RuntimeApplicationSpec(
                packageName = spec.packageName,
                applicationInfo = spec.applicationInfo,
                baseApkPath = spec.baseApkPath,
                splitApkPaths = spec.splitApkPaths
            )
            val application = TargetApplicationFactory.create(
                applicationSpec,
                loader,
                context
            )
            if (context is ContainerContext) {
                context.targetApplication = application
            }
            Log.d(
                TAG,
                "attached Application ${application.javaClass.name} through " +
                TargetApplicationFactory.javaClass.name
            )
            return application
        }

        private fun createResources(host: Context, paths: List<String>): Resources {
            val assetManager = AssetManager::class.java
                .getDeclaredConstructor()
                .newInstance()
            val addAssetPath = AssetManager::class.java
                .getMethod("addAssetPath", String::class.java)

            paths.forEachIndexed { index, path ->
                val cookie = addAssetPath.invoke(assetManager, path) as Int
                if (cookie == 0) {
                    error(
                        if (index == 0) {
                            "AssetManager.addAssetPath failed: $path"
                        } else {
                            "split addAssetPath failed: $path"
                        }
                    )
                }
            }

            return Resources(
                assetManager,
                host.resources.displayMetrics,
                host.resources.configuration
            )
        }
    }
}
