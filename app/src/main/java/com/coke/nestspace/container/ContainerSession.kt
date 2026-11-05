package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.container.identity.HostIdentity
import com.coke.nestspace.container.identity.ProcessNameCompat
import com.coke.nestspace.container.identity.SystemServiceIdentity
import com.coke.nestspace.container.intent.ContainerIntentResolver
import com.coke.nestspace.container.job.ContainerJobScheduler
import com.coke.nestspace.container.loader.ContainerClassLoader
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.loader.SharedLibraries
import com.coke.nestspace.container.loader.withContainerClassLoader
import com.coke.nestspace.container.remote.ContainerProcessBrokerClient
import com.coke.nestspace.container.vpm.VirtualPackageRegistry


import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ContextThemeWrapper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    val intents: ContainerIntentResolver,
    val packageManager: android.content.pm.PackageManager,
    /** Package-level view of every app installed in this space. */
    val virtualPackages: VirtualPackageRegistry
) {
    val id: String get() = spec.sessionId

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

    private val providerManager: ContainerProviderManager by lazy {
        ContainerProviderManager(this)
    }

    private val jobScheduler: ContainerJobScheduler by lazy {
        ContainerJobScheduler(this)
    }

    private val targetHosts = ConcurrentHashMap<Activity, StageAActivity>()

    @Volatile
    var navigator: StageAActivity? = null

    init {
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
            runCatching {
                compatAppLike.javaClass
                    .getMethod("onBaseContextAttached", Context::class.java)
                    .invoke(compatAppLike, context)
            }.onFailure { Log.w(TAG, "NetEase ApplicationLike attach failed", it) }
        }
        TargetCrashGuard.noteSession(spec.packageName, spec.instanceId, hostContext)
        publishAsProcessApplication()
        restoreHostLoadedApkPackageName()
        providerManager.install()
        application.onCreate()
        broadcastManager.startHostBridge()
        if (compatAppLike != null) {
            runCatching {
                compatAppLike.javaClass.getMethod("onCreate").invoke(compatAppLike)
            }.onFailure { Log.w(TAG, "NetEase ApplicationLike onCreate failed", it) }
        }
        // Target Application.onCreate often registers its own uncaught
        // exception handler (crash reporting) that terminates the host process.
        // Re-install the guard so target-thread failures stay contained.
        TargetCrashGuard.install(force = true)
        Log.d(TAG, "session initialized ${spec.packageName}")
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
        broadcastManager.destroy()
        providerManager.destroy()
        serviceManager.destroyAll()
        jobScheduler.shutdown()
        ProcessNameCompat.restorePersistent()
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
        savedState: Bundle?
    ): Activity {
        val info = resolveActivity(className)
            ?: throw IllegalArgumentException(
                AppLanguage.tr(
                    "找不到 Activity：${className ?: spec.launcherActivity}",
                    "Activity not found: ${className ?: spec.launcherActivity}"
                )
            )
        val activityClass = (info.targetActivity?.takeIf { it.isNotBlank() } ?: info.name)
        val targetClass = loader.loadClass(activityClass).asSubclass(Activity::class.java)
        val target = targetClass.getDeclaredConstructor().newInstance()

        val theme = resources.newTheme().apply {
            applyStyle(resolveTheme(info), true)
        }
        val intent = Intent(sourceIntent ?: Intent())
            .setClassName(spec.packageName, info.name)

        val attach = Activity::class.java.declaredMethods
            .firstOrNull { it.name == "attach" }
            ?: error("Activity.attach not found")
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
        attach.invoke(target, *args.toTypedArray())
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
        if (targetProcess == spec.targetProcessName) {
            // Mirror mode keeps one real host Activity and swaps target
            // Activities inside its Presentation. This avoids creating a
            // WindowManager task for every in-app navigation, which is what
            // causes the full-screen transition flash.
            if (mirrorDisplayId >= 0 && host.isMirrorMode()) {
                val targetIntent = Intent(intent).setClassName(
                    spec.packageName,
                    info.name
                )
                if (host.openMirrorChildTarget(info.name, targetIntent, requestCode)) {
                    return true
                }
            }
            return launchOnHost(
                host,
                hostPackage,
                info,
                host.javaClass,
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
            launchOnHost(
                host,
                hostPackage,
                info,
                ContainerProcessPool.activityClass(slot),
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
                if (targetProcess != spec.targetProcessName) {
                    // The target slot process may not have created this
                    // subprocess session yet; let it build one from the
                    // launch descriptor instead of reporting "session lost".
                    putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, spec.packageName)
                    putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, spec.instanceId)
                }
                putExtra(StageAActivity.EXTRA_ACTIVITY_CLASS, info.name)
                putExtra(
                    StageAActivity.EXTRA_TARGET_INTENT,
                    Intent(intent).setClassName(spec.packageName, info.name)
                )
                putExtra(StageAActivity.EXTRA_HEADLESS, headless)
                if (mirrorDisplayId >= 0) {
                    putExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, mirrorDisplayId)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            }
        return try {
            if (mirrorDisplayId >= 0) host.captureMirrorSnapshotForTransition()
            withHostIdentity(host) {
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
                    host.startActivityForResult(containerIntent, requestCode, options)
                } else if (options != null) {
                    host.startActivity(containerIntent, options)
                } else {
                    host.startActivity(containerIntent)
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "start target activity failed: ${info.name}", t)
            false
        }
    }

    fun startExternalActivity(host: StageAActivity, intent: Intent, requestCode: Int): Boolean {
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
        private const val TAG = "ContainerSession"


        fun create(host: Context, spec: ContainerLaunchSpec): ContainerSession {
            HiddenApiCompat.ensure()
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
            val classPath = (apkPaths + sharedLibraryPaths)
                .joinToString(File.pathSeparator)
            val nativeAbis = NativeLibs.abisOf(apkPaths)
            val nativeAbi = NativeLibs.supportedAbi(nativeAbis)
            val nativeLibs = NativeLibs.extractIfNeeded(
                apkPaths,
                nativeAbis,
                File(dataRoot, "native-libs"),
                ContainerCompat.excludedNativeLibs(spec.packageName)
            )
            Log.d(TAG, "native libs: ${nativeLibs?.absolutePath} abi=$nativeAbi")

            val loader = ContainerClassLoader(
                classPath,
                nativeLibs?.absolutePath,
                host.classLoader
            )
            val resources = createResources(host, apkPaths)
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
                // installed APK. SO loaders (notably NetEase's dynamicso)
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

            // Application.attachBaseContext runs before Application.onCreate and
            // may query package metadata. Publish the virtual PackageManager
            // before attaching so that code sees the target APK's version,
            // permissions and components rather than the host package's.
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
            // Hand target code a real ApplicationPackageManager: some SDKs
            // (Pinduoduo's libpdd_secure) read its mPM field from JNI and abort
            // when the object is any other PackageManager implementation.
            val packageManager = host.applicationContext.packageManager
            context.targetPackageManager = packageManager

            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread")
                .invoke(null)

            // Native packers (SecNeo/Bangcle) run package checks in JNI_OnLoad
            // while the target Application class is being loaded. Publish the
            // virtual identity first, otherwise those checks see the host
            // package and abort with NameNotFoundException.
            publishVirtualIdentity(activityThread, context)

            val application = ProcessNameCompat.withProcessName(spec.targetProcessName) {
                withContainerClassLoader(loader) {
                    attachApplication(spec, loader, context)
                }
            }
            Log.d(TAG, "application attached: ${application.javaClass.name}")

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
            if (ContainerCompat.isNetEase(spec.packageName)) {
                return runCatching { attachNeteaseApplication(loader, context) }
                    .onFailure { Log.w(TAG, "NetEase anti-tamper bypass failed", it) }
                    .getOrElse {
                        attachRegularApplication(spec, loader, context)
                    }
            }
            return attachRegularApplication(spec, loader, context)
        }

        /**
         * NetEase Music's NIS wrapper loads a native anti-tamper library in
         * attachBaseContext and exits the process with status 28 when the
         * emulator/container checks fail. The APK also contains a standard
         * Tinker "real application" pair. Build that pair directly and skip
         * the wrapper: the UI and business dex files are present in the APK,
         * and Tinker has no patch to apply in a fresh install.
         */
        private fun attachNeteaseApplication(
            loader: ClassLoader,
            context: Context
        ): Application {
            val base = Application()
            if (context is ContainerContext) {
                context.targetApplication = base
            }
            attachBaseContext(base, context)

            val likeClass = loader.loadClass(ContainerCompat.NET_EASE_APPLICATION_LIKE)
            val likeCtor = likeClass.getConstructor(
                Application::class.java,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Intent::class.java
            )
            val appLike = likeCtor.newInstance(
                base,
                0x0f,
                true,
                android.os.SystemClock.elapsedRealtime(),
                System.currentTimeMillis(),
                null
            )
            val patchLike = loader.loadClass(ContainerCompat.NET_EASE_PATCH_APPLICATION_LIKE)
            val realClass = loader.loadClass(ContainerCompat.NET_EASE_REAL_APPLICATION)
            val real = realClass
                .getConstructor(Application::class.java, patchLike)
                .newInstance(base, appLike) as Application
            if (context is ContainerContext) {
                context.targetApplication = real
            }
            attachBaseContext(real, context)
            ContainerCompat.rememberAppLike(real, appLike)
            Log.d(TAG, "NetEase compat application: ${real.javaClass.name}")
            return real
        }

        private fun attachRegularApplication(
            spec: ContainerLaunchSpec,
            loader: ClassLoader,
            context: Context
        ): Application {
            val className = spec.applicationInfo.className
                ?.takeIf { it.isNotBlank() }
                ?: Application::class.java.name
            val appClass = loader.loadClass(className).asSubclass(Application::class.java)
            val application = appClass.getDeclaredConstructor().newInstance()
            // Application.attachBaseContext and the providers attached later
            // call getApplicationContext(); make it return the target
            // Application before any target code runs. Some libraries
            // (AndroidX Startup, JPush) cast that result to Application.
            if (context is ContainerContext) {
                context.targetApplication = application
            }
            attachBaseContext(application, context)
            return application
        }

        private fun attachBaseContext(application: Application, context: Context) {
            var cls: Class<*>? = application.javaClass
            while (cls != null) {
                val attachBase = runCatching {
                    cls.getDeclaredMethod("attachBaseContext", Context::class.java)
                }.getOrNull()
                if (attachBase != null) {
                    attachBase.isAccessible = true
                    attachBase.invoke(application, context)
                    return
                }
                cls = cls.superclass
            }
            error("Application.attachBaseContext not found for ${application.javaClass.name}")
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
