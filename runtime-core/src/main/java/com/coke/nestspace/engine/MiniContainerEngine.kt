package com.coke.nestspace.engine

import com.coke.nestspace.i18n.AppLanguage
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.coke.nestspace.container.loader.ApkDescriptor
import com.coke.nestspace.container.loader.ApkInspector
import com.coke.nestspace.container.loader.ApkMetadataCache
import com.coke.nestspace.container.loader.AppShortcutLoader
import com.coke.nestspace.container.ContainerContext
import com.coke.nestspace.container.ContainerCrashStore
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.ContainerProcessRouter
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.container.shortcut.DynamicShortcutStore
import com.coke.nestspace.container.StageAActivity
import com.coke.nestspace.container.TargetWebViewIdentityHook
import com.coke.nestspace.container.vpm.ContainerPermissionStore
import com.coke.nestspace.control.ContainerUiKeepAlive
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.LaunchInfo
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.data.VirtualInstance
import com.coke.nestspace.plugin.RuntimeFingerprints
import com.coke.nestspace.plugin.RuntimeLaunchMode
import com.coke.nestspace.plugin.RuntimeProxyExtensions
import com.coke.nestspace.plugin.RuntimeProxyContext
import com.coke.nestspace.plugin.RuntimeProxyPlugins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Persistent Stage B container engine.
 *
 * Layout under filesDir/vspace:
 *   apps/<package>/<install-id>/base.apk + split-N.apk
 *   data/<package>/<instance-id>/{files,cache,code_cache,databases,...}
 *   state.json
 *
 * Install validates package consistency, signature consistency, minSdk and
 * REQUIRE_SECURE_ENV, then stores APKs. Launch creates a token-backed launch
 * spec for StageAActivity/MiniContainer. Multi-open creates another instance
 * with an isolated data directory.
 */
class MiniContainerEngine(private val context: Context) : ContainerEngine {

    private val rootDir = File(context.filesDir, "vspace")
    private val appsDir = File(rootDir, "apps")
    private val dataRootDir = File(rootDir, "data")
    private val tempDir = File(rootDir, "tmp")
    private val store = SpaceStore(File(rootDir, "state.json"))
    private val lock = Mutex()

    private var loaded = false
    private val apps = linkedMapOf<String, StoredApp>()
    private val iconCache = mutableMapOf<String, ImageBitmap?>()

    private val _virtualApps = MutableStateFlow<List<VirtualApp>>(emptyList())
    override val virtualApps: StateFlow<List<VirtualApp>> = _virtualApps.asStateFlow()

    override suspend fun install(
        spaceId: String,
        apkUris: List<String>
    ): EngineResult<VirtualApp> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            installLocked(spaceId, apkUris)
        }
    }

    override suspend fun matchesInstalledPayload(
        spaceId: String,
        apkUris: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            if (apkUris.isEmpty()) return@withLock false
            val workDir = File(tempDir, UUID.randomUUID().toString().replace("-", ""))
                .apply { mkdirs() }
            try {
                val copied = apkUris.mapIndexed { index, uri ->
                    copyUriToTemp(uri, File(workDir, "input-$index.apk"))
                }
                val descriptors = copied.map {
                    ApkInspector.inspect(context, it.absolutePath, requireLauncher = false)
                }
                val packageName = descriptors
                    .map { it.packageName }
                    .distinct()
                    .singleOrNull()
                    ?: return@withLock false
                val base = descriptors.singleOrNull {
                    it.hasLauncher && it.splitName == null
                } ?: return@withLock false
                val existing = apps[key(spaceId, packageName)]
                    ?: return@withLock false
                val pendingDigests = copied.map(ApkPayloadIdentity::sha256)
                existing.versionCode == base.versionCode &&
                    existing.versionName == base.versionName &&
                    (
                        existing.apkIndex?.matchesPayload(pendingDigests)
                            ?: ApkPayloadIdentity.same(
                                pending = copied,
                                installed = listOf(File(existing.baseApkPath)) +
                                    existing.splitApkPaths.map(::File)
                            )
                        )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    override suspend fun matchesInstalledIdentity(
        spaceId: String,
        apkUris: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            if (apkUris.isEmpty()) return@withLock false
            val workDir = File(tempDir, UUID.randomUUID().toString().replace("-", ""))
                .apply { mkdirs() }
            try {
                val copied = apkUris.mapIndexed { index, uri ->
                    copyUriToTemp(uri, File(workDir, "identity-$index.apk"))
                }
                val descriptors = copied.map {
                    ApkInspector.inspect(context, it.absolutePath, requireLauncher = false)
                }
                val packageName = descriptors
                    .map { it.packageName }
                    .distinct()
                    .singleOrNull()
                    ?: return@withLock false
                val base = descriptors.singleOrNull {
                    it.hasLauncher && it.splitName == null
                } ?: return@withLock false
                val existing = apps[key(spaceId, packageName)]
                    ?: return@withLock false
                val signature = base.signatureSha256
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withLock false
                existing.signatureSha256 == signature &&
                    descriptors.all {
                        it.signatureSha256.isNullOrBlank() ||
                            it.signatureSha256 == signature
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                false
            } finally {
                workDir.deleteRecursively()
            }
        }
    }

    override suspend fun listApps(spaceId: String): EngineResult<List<AppInfo>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoadedLocked()
                EngineResult.Ok(
                    apps.values
                        .filter { it.spaceId == spaceId }
                        .sortedBy { it.label.lowercase() }
                        .map {
                            AppInfo(
                                packageName = it.packageName,
                                label = it.label,
                                icon = null,
                                isSystem = false
                            )
                        }
                )
            }
        }

    override suspend fun listInstalled(spaceId: String): EngineResult<List<VirtualApp>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                ensureLoadedLocked()
                EngineResult.Ok(publicApps(spaceId))
            }
        }

    override suspend fun launch(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        headless: Boolean,
        requestedActivity: String?
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val instance = if (instanceId.isNullOrBlank()) {
                defaultInstance(app)
            } else {
                app.instances.firstOrNull { it.id == instanceId }
                    ?: return@withLock EngineResult.Error(
                        "INSTANCE_NOT_FOUND",
                        AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                    )
            }
            val fingerprint = RuntimeFingerprints.sha256(
                app.packageName,
                app.versionCode,
                app.signatureSha256
            )
            RuntimeProxyPlugins.resolve(
                RuntimeProxyContext(
                    fingerprint,
                    Build.VERSION.SDK_INT
                )
            )?.let { policy ->
                when (policy.mode) {
                    RuntimeLaunchMode.HOST_FALLBACK -> launchHostFallback(
                        app = app,
                        instanceId = instance.id,
                        fallbackReason = policy.reason,
                        pluginId = policy.pluginId,
                        policyId = policy.policyId
                    ).let { return@withLock it }
                    RuntimeLaunchMode.BLOCKED -> return@withLock EngineResult.Error(
                        "RUNTIME_BLOCKED_BY_PLUGIN",
                        policy.reason
                    )
                    RuntimeLaunchMode.CONTAINER -> Unit
                }
            }
            RuntimeProxyExtensions.resolve(
                RuntimeProxyContext(
                    fingerprint,
                    Build.VERSION.SDK_INT
                )
            )?.let { extension ->
                when (extension.mode) {
                    RuntimeLaunchMode.HOST_FALLBACK -> launchHostFallback(
                        app = app,
                        instanceId = instance.id,
                        fallbackReason = extension.reason,
                        pluginId = extension.id,
                        policyId = extension.id
                    ).let { return@withLock it }
                    RuntimeLaunchMode.BLOCKED -> return@withLock EngineResult.Error(
                        "RUNTIME_BLOCKED_BY_PLUGIN",
                        extension.reason
                    )
                    RuntimeLaunchMode.CONTAINER -> Unit
                }
            }
            launchLocked(
                app = app,
                instanceId = instance.id,
                headless = headless,
                requestedActivity = requestedActivity
            )
        }
    }

    private fun launchHostFallback(
        app: StoredApp,
        instanceId: String,
        fallbackReason: String,
        pluginId: String,
        policyId: String
    ): EngineResult<LaunchInfo> {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return EngineResult.Error(
                "HOST_APP_REQUIRED",
                AppLanguage.tr(
                    "该版本需要宿主兼容模式，但平板上没有安装同名官方应用。",
                    "This build requires host compatibility mode, but the official " +
                        "app is not installed on the device."
                )
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Log.i(
                TAG,
                "host fallback launch package=${app.packageName} reason=$fallbackReason"
            )
            EngineResult.Ok(
                LaunchInfo(
                    spaceId = app.spaceId,
                    packageName = app.packageName,
                    instanceId = instanceId,
                    activity = intent.component?.className.orEmpty(),
                    dataDir = "",
                    hostFallback = true,
                    fallbackReason = fallbackReason,
                    runtimePluginId = pluginId,
                    runtimePolicyId = policyId
                )
            )
        } catch (t: Throwable) {
            EngineResult.Error(
                "HOST_FALLBACK_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    override suspend fun createInstance(
        spaceId: String,
        packageName: String
    ): EngineResult<VirtualInstance> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val now = System.currentTimeMillis()
            val instance = StoredInstance(nextInstanceId(app), now, 0L)
            val updated = app.copy(instances = app.instances + instance)
            apps[key(updated)] = updated
            ensureInstanceDirs(updated.packageName, instance.id)
            val sourcePrivacyMarker = File(
                dataDirFor(packageName, defaultInstance(app).id),
                "files/privacy_confirmed"
            )
            if (sourcePrivacyMarker.isFile) {
                File(
                    dataDirFor(packageName, instance.id),
                    "files/privacy_confirmed"
                ).createNewFile()
            }
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "save state failed", it) }
            publish()
            EngineResult.Ok(
                VirtualInstance(
                    id = instance.id,
                    createdAt = instance.createdAt,
                    lastLaunchedAt = instance.lastLaunchedAt,
                    dataDir = dataDirFor(packageName, instance.id).absolutePath,
                    environmentVersion = instance.environmentVersion,
                    compatibilityProfile = instance.compatibilityProfile,
                    lastProbeResult = instance.lastProbeResult
                )
            )
        }
    }

    override suspend fun deleteInstance(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val key = key(spaceId, packageName)
            val app = apps[key]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            val instance = app.instances.firstOrNull { it.id == instanceId }
                ?: return@withLock EngineResult.Error(
                    "INSTANCE_NOT_FOUND",
                    AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                )
            if (app.instances.size <= 1) {
                return@withLock EngineResult.Error(
                    "LAST_INSTANCE",
                    AppLanguage.tr(
                        "这是 $packageName 的最后一个实例；请从应用管理卸载应用。",
                        "This is the last $packageName instance; uninstall the app from App manager."
                    )
                )
            }

            val updated = app.copy(
                instances = app.instances.filterNot { it.id == instance.id }
            )
            apps[key] = updated
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps[key] = app
                return@withLock EngineResult.Error(
                    "DELETE_INSTANCE_FAILED",
                    AppLanguage.tr(
                        "保存状态失败：${t.message ?: t.javaClass.simpleName}",
                        "Failed to save state: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
            }

            closeInstanceRuntime(packageName, instance.id)
            dataDirFor(packageName, instance.id).deleteRecursively()
            ContainerContext.externalRootFor(context, packageName, instance.id)
                ?.deleteRecursively()
            ContainerContext.clearInstancePrefs(context, packageName, instance.id)
            ContainerPermissionStore.clear(context, packageName, instance.id)
            DynamicShortcutStore.clear(
                File(context.filesDir, "vspace/shortcuts"),
                packageName,
                instance.id
            )
            ContainerCrashStore.clear(context, packageName, instance.id)
            publish()
            EngineResult.Ok(Unit)
        }
    }

    override suspend fun clearInstanceData(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            val instance = app.instances.firstOrNull { it.id == instanceId }
                ?: return@withLock EngineResult.Error(
                    "INSTANCE_NOT_FOUND",
                    AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                )

            closeInstanceRuntime(packageName, instance.id)
            dataDirFor(packageName, instance.id).deleteRecursively()
            ensureInstanceDirs(packageName, instance.id)
            ContainerContext.externalRootFor(context, packageName, instance.id)
                ?.deleteRecursively()
            ContainerContext.clearInstancePrefs(context, packageName, instance.id)
            ContainerPermissionStore.clear(context, packageName, instance.id)
            DynamicShortcutStore.clear(
                File(context.filesDir, "vspace/shortcuts"),
                packageName,
                instance.id
            )
            ContainerCrashStore.clear(context, packageName, instance.id)
            publish()
            EngineResult.Ok(Unit)
        }
    }

    override suspend fun multiOpen(
        spaceId: String,
        packageName: String,
        headless: Boolean
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val now = System.currentTimeMillis()
            val instance = StoredInstance(nextInstanceId(app), now, 0L)
            val updated = app.copy(instances = app.instances + instance)
            apps[key(updated)] = updated
            ensureInstanceDirs(updated.packageName, instance.id)
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "save state failed", it) }
            publish()
            val result = launchLocked(updated, instance.id, headless)
            if (result is EngineResult.Error) {
                // Do not leave an instance that never launched behind.
                apps[key(updated)] = app
                runCatching { store.save(apps.values) }
                    .onFailure { Log.w(TAG, "rollback instance failed", it) }
                dataDirFor(packageName, instance.id).deleteRecursively()
                ContainerContext.externalRootFor(context, packageName, instance.id)
                    ?.deleteRecursively()
                publish()
            }
            result
        }
    }

    override suspend fun shortcuts(
        spaceId: String,
        packageName: String
    ): List<AppShortcut> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)] ?: return@withLock emptyList()
            val static = AppShortcutLoader.load(
                context = context,
                apkPath = app.baseApkPath,
                packageName = app.packageName,
                launcherActivity = app.launcherActivity,
                cacheKey = "${app.baseApkPath}@${app.updatedAt}"
            )
            val storeRoot = File(context.filesDir, "vspace/shortcuts")
            val dynamic = DynamicShortcutStore.load(
                root = storeRoot,
                packageName = app.packageName,
                instanceId = defaultInstance(app).id
            ).asSequence()
                .filter { it.enabled }
                .mapNotNull { record ->
                    val intent = runCatching {
                        Intent.parseUri(record.intentUri, Intent.URI_INTENT_SCHEME)
                    }.getOrNull() ?: return@mapNotNull null
                    AppShortcut(
                        id = "dynamic:${record.id}",
                        label = record.label,
                        icon = null,
                        targetActivity = record.targetActivity
                            ?: intent.component?.className
                            ?: app.launcherActivity,
                        action = intent.action,
                        data = intent.dataString,
                        mimeType = intent.type,
                        categories = intent.categories?.toList().orEmpty(),
                        extras = intent.extras ?: Bundle(),
                        intentUri = record.intentUri
                    )
                }
                .toList()
            (dynamic + static).distinctBy { it.id }.take(8)
        }
    }

    override suspend fun launchShortcut(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        shortcut: AppShortcut,
        headless: Boolean
    ): EngineResult<LaunchInfo> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val app = apps[key(spaceId, packageName)]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr(
                        "空间里没有 $packageName，请先导入 APK",
                        "$packageName is not in the space; import its APK first"
                    )
                )
            val instance = if (instanceId.isNullOrBlank()) {
                defaultInstance(app)
            } else {
                app.instances.firstOrNull { it.id == instanceId }
                    ?: return@withLock EngineResult.Error(
                        "INSTANCE_NOT_FOUND",
                        AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found")
                    )
            }
            launchLocked(
                app = app,
                instanceId = instance.id,
                headless = headless,
                requestedActivity = shortcut.targetActivity,
                requestedIntent = shortcut.toIntent(packageName)
            )
        }
    }

    override suspend fun stop(spaceId: String, packageName: String): EngineResult<Unit> =
        withContext(Dispatchers.IO) {
            // StageAActivity state is per slot process; this engine runs in the
            // main process. Close every assigned process for the package instead.
            val slots = ContainerProcessPool.runningSlots(context)
                .filter { it.packageName == packageName }
                .map { it.slot }
                .toSet()
            apps[key(spaceId, packageName)]?.instances?.forEach { instance ->
                ContainerProcessPool.slotFor(packageName, instance.id)?.let { slot ->
                    ContainerUiKeepAlive.unbind(context, slot)
                }
            }
            ContainerProcessPool.closePackage(context, packageName)
            // Killing a slot process does not remove its ActivityRecord/Task;
            // an orphaned headless host can later reappear as a second small
            // window. Finish the tasks explicitly as well.
            slots.forEach { finishPreviousHostTasks(it) }
            StageAActivity.finishIfShowing(packageName)
            EngineResult.Ok(Unit)
        }

    override suspend fun uninstall(
        spaceId: String,
        packageName: String
    ): EngineResult<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoadedLocked()
            val key = key(spaceId, packageName)
            val app = apps[key]
                ?: return@withLock EngineResult.Error(
                    "APP_NOT_FOUND",
                    AppLanguage.tr("空间里没有 $packageName", "$packageName is not in the space")
                )
            apps.remove(key)
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps[key] = app
                return@withLock EngineResult.Error(
                    "UNINSTALL_FAILED",
                    AppLanguage.tr(
                    "保存状态失败：${t.message ?: t.javaClass.simpleName}",
                    "Failed to save state: ${t.message ?: t.javaClass.simpleName}"
                )
                )
            }

            // Kill main and subprocess sessions before deleting APK/data so a
            // live target cannot keep running against removed files.
            app.instances.forEach { instance ->
                ContainerProcessPool.slotFor(app.packageName, instance.id)?.let { slot ->
                    ContainerUiKeepAlive.unbind(context, slot)
                }
                ContainerProcessPool.forgetPrefix(
                    context,
                    "${app.packageName}@${instance.id}"
                )
            }
            StageAActivity.finishIfShowing(packageName)
            val apkDir = File(app.baseApkPath).parentFile
            apkDir?.deleteRecursively()
            apkDir?.parentFile?.listFiles()
                ?.takeIf { it.isEmpty() }
                ?.let { apkDir.parentFile?.delete() }
            dataDirFor(packageName).deleteRecursively()
            ContainerContext.clearPackagePrefs(context, packageName)
            DynamicShortcutStore.clearPackage(
                File(context.filesDir, "vspace/shortcuts"),
                packageName
            )
            ContainerPermissionStore.clearPackage(context, packageName)
            app.instances.forEach { instance ->
                ContainerContext.externalRootFor(context, packageName, instance.id)
                    ?.deleteRecursively()
            }
            publish()
            EngineResult.Ok(Unit)
        }
    }

    private fun installLocked(spaceId: String, apkUris: List<String>): EngineResult<VirtualApp> {
        if (apkUris.isEmpty()) {
            return EngineResult.Error(
                "INVALID_ARGUMENT",
                AppLanguage.tr("没有选择 APK", "No APK selected")
            )
        }
        val workDir = File(tempDir, UUID.randomUUID().toString().replace("-", ""))
            .apply { mkdirs() }
        var targetDir: File? = null
        try {
            val copied = apkUris.mapIndexed { index, uri ->
                copyUriToTemp(uri, File(workDir, "input-$index.apk"))
            }
            val descriptors = copied.map {
                ApkInspector.inspect(context, it.absolutePath, requireLauncher = false)
            }

            val packages = descriptors.map { it.packageName }.distinct()
            if (packages.size != 1) {
                throw InstallFailure(
                    "MULTI_PACKAGE",
                    AppLanguage.tr(
                    "一次安装只能包含同一个包：${packages.joinToString()}",
                    "One install can only contain APKs of the same package: " +
                        packages.joinToString()
                )
                )
            }
            val packageName = packages.single()
            val bases = descriptors.filter { it.hasLauncher && it.splitName == null }
            if (bases.isEmpty()) {
                throw InstallFailure(
                    "BASE_APK_REQUIRED",
                    AppLanguage.tr(
                    "需要选择包含入口 Activity 的 base APK",
                    "Select a base APK that contains the launcher activity"
                )
                )
            }
            if (bases.size > 1) {
                throw InstallFailure(
                    "MULTIPLE_BASE_APK",
                    AppLanguage.tr(
                    "一次只能安装一个 base APK（选择 base + split 才合法）",
                    "Only one base APK can be installed at a time (base + splits is allowed)"
                )
                )
            }
            val base = bases.single()
            val splits = descriptors.filter { it !== base }
            if (base.minSdk > Build.VERSION.SDK_INT) {
                throw InstallFailure(
                    "MIN_SDK_TOO_HIGH",
                    AppLanguage.tr(
                    "${base.packageName} 需要 API ${base.minSdk}，当前设备 API ${Build.VERSION.SDK_INT}",
                    "${base.packageName} requires API ${base.minSdk}; this device is API ${Build.VERSION.SDK_INT}"
                )
                )
            }
            if (descriptors.any { it.requiresSecureEnv }) {
                throw InstallFailure(
                    "SECURE_ENV_REQUIRED",
                    AppLanguage.tr(
                    "${base.packageName} 声明了 REQUIRE_SECURE_ENV=1，拒绝在应用级容器中安装",
                    "${base.packageName} declares REQUIRE_SECURE_ENV=1 and cannot be " +
                        "installed in an app-level container"
                )
                )
            }
            val signature = base.signatureSha256?.takeIf { it.isNotBlank() }
                ?: throw InstallFailure("SIGNATURE_INVALID", AppLanguage.tr("无法读取 APK 签名", "Could not read the APK signature"))
            val certificatesMatch = base.signingCertDigests.isNotEmpty() &&
                descriptors.all { descriptor ->
                    descriptor.signingCertDigests.isNotEmpty() &&
                        base.signingCertDigests.containsAll(descriptor.signingCertDigests)
                }
            val legacySignersMatch = base.v1SignatureSha256 != null &&
                descriptors.all { it.v1SignatureSha256 == base.v1SignatureSha256 }
            if (!certificatesMatch && !legacySignersMatch) {
                throw InstallFailure(
                    "SIGNATURE_MISMATCH",
                    AppLanguage.tr(
                    "base 与 split APK 的签名不一致",
                    "The base and split APKs are signed differently"
                )
                )
            }
            val pendingDigestsByPath = copied.associate {
                it.absolutePath to ApkPayloadIdentity.sha256(it)
            }
            val pendingDigests = pendingDigestsByPath.values.toList()

            val existing = apps[key(spaceId, packageName)]
            if (existing != null && existing.signatureSha256 != signature) {
                throw InstallFailure(
                    "SIGNATURE_MISMATCH",
                    AppLanguage.tr(
                    "新 APK 与已安装版本签名不一致（$packageName）",
                    "The new APK is signed differently from the installed version ($packageName)"
                )
                )
            }

            if (existing != null &&
                existing.versionCode == base.versionCode &&
                existing.versionName == base.versionName &&
                (
                    existing.apkIndex?.matchesPayload(pendingDigests)
                        ?: ApkPayloadIdentity.same(
                            pending = copied,
                            installed = listOf(File(existing.baseApkPath)) +
                                existing.splitApkPaths.map(::File)
                        )
                    )
            ) {
                Log.d(TAG, "same APK already installed: $packageName")
                return EngineResult.Ok(existing.toPublic())
            }

            if (existing == null) {
                // State has no record for this package; stale APK/data from an
                // interrupted install must not leak into the new install.
                File(appsDir, sanitize(packageName)).deleteRecursively()
                dataDirFor(packageName).deleteRecursively()
            }

            val installId = "${System.currentTimeMillis()}-${
                UUID.randomUUID().toString().replace("-", "").take(8)
            }"
            val packageDir = File(appsDir, sanitize(packageName))
            val dir = File(packageDir, installId).apply { mkdirs() }
            targetDir = dir
            val finalBase = File(dir, "base.apk")
            moveFile(
                File(base.apkPath),
                finalBase,
                pendingDigestsByPath.getValue(base.apkPath)
            )
            val finalSplits = splits.mapIndexed { index, descriptor ->
                File(dir, "split-$index.apk").also {
                    moveFile(
                        File(descriptor.apkPath),
                        it,
                        pendingDigestsByPath.getValue(descriptor.apkPath)
                    )
                }
            }
            // Android 14+ refuses to open a writable dex file. Installed APKs
            // are immutable payloads, so mark them read-only before launch.
            (listOf(finalBase) + finalSplits).forEach {
                if (!it.setReadOnly()) {
                    throw InstallFailure(
                        "INSTALL_FAILED",
                        AppLanguage.tr(
                        "无法将 APK 设为只读：${it.absolutePath}",
                        "Could not make the APK read-only: ${it.absolutePath}"
                    )
                    )
                }
            }

            base.applicationInfo.sourceDir = finalBase.absolutePath
            base.applicationInfo.publicSourceDir = finalBase.absolutePath
            base.packageInfo.applicationInfo = base.applicationInfo
            val installed = base.copy(apkPath = finalBase.absolutePath)
            if (installed.packageName != packageName) {
                throw InstallFailure("INSTALL_FAILED", AppLanguage.tr("安装后包名不一致", "Package name changed after install"))
            }
            val installedSignature = installed.signatureSha256?.takeIf { it.isNotBlank() }
                ?: throw InstallFailure("SIGNATURE_INVALID", AppLanguage.tr("安装后无法读取签名", "Could not read the signature after install"))
            if (installedSignature != signature) {
                throw InstallFailure("SIGNATURE_MISMATCH", AppLanguage.tr("安装后签名校验失败", "Signature verification failed after install"))
            }
            val finalFiles = listOf(finalBase) + finalSplits
            val sourcePaths = listOf(base.apkPath) + splits.map { it.apkPath }
            val nativeAbis = descriptors.flatMap { it.nativeAbis }.toSet()
            val apkIndex = ApkInstallIndex(
                schemaVersion = APK_INDEX_SCHEMA_VERSION,
                files = finalFiles.zip(sourcePaths).map { (file, source) ->
                    ApkFileFingerprint(
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        sha256 = pendingDigestsByPath.getValue(source)
                    )
                },
                nativeAbis = nativeAbis,
                descriptorCachePath = ApkMetadataCache.descriptorPath(
                    finalBase.absolutePath
                )
            )
            ApkMetadataCache.write(installed, apkIndex)

            val now = System.currentTimeMillis()
            val instances = existing?.instances?.takeIf { it.isNotEmpty() }
                ?: listOf(StoredInstance("instance-1", now, 0L))
            instances.forEach { ensureInstanceDirs(packageName, it.id) }

            val record = StoredApp(
                spaceId = spaceId,
                packageName = packageName,
                label = installed.label,
                versionName = installed.versionName,
                versionCode = installed.versionCode,
                launcherActivity = installed.launcherActivity
                    ?: throw InstallFailure("NO_LAUNCHER_ACTIVITY", AppLanguage.tr("找不到入口 Activity", "Launcher activity not found")),
                baseApkPath = finalBase.absolutePath,
                splitApkPaths = finalSplits.map { it.absolutePath },
                signatureSha256 = installedSignature,
                hasNativeLibs = nativeAbis.isNotEmpty(),
                installedAt = existing?.installedAt ?: now,
                updatedAt = now,
                instances = instances,
                apkIndex = apkIndex
            )
            apps[key(record)] = record
            try {
                store.save(apps.values)
            } catch (t: Throwable) {
                apps.remove(key(record))
                throw InstallFailure(
                    "INSTALL_FAILED",
                    AppLanguage.tr(
                    "保存安装状态失败：${t.message ?: t.javaClass.simpleName}",
                    "Failed to save install state: ${t.message ?: t.javaClass.simpleName}"
                )
                )
            }

            // A replaced APK must not be served by the old container process:
            // its ClassLoader and native libraries still point at the deleted
            // files. Killing the slot also lets the next launch pick up new
            // code and resources.
            instances.forEach { instance ->
                ContainerProcessPool.forgetPrefix(context, "$packageName@${instance.id}")
            }

            if (existing != null) {
                val oldDir = File(existing.baseApkPath).parentFile
                if (oldDir != null && oldDir.absolutePath != dir.absolutePath) {
                    oldDir.deleteRecursively()
                    oldDir.parentFile?.listFiles()
                        ?.takeIf { it.isEmpty() }
                        ?.let { oldDir.parentFile?.delete() }
                }
            }
            publish()
            return EngineResult.Ok(record.toPublic())
        } catch (t: InstallFailure) {
            targetDir?.deleteRecursively()
            return EngineResult.Error(t.code, t.message ?: t.code)
        } catch (t: Throwable) {
            targetDir?.deleteRecursively()
            Log.w(TAG, "install failed", t)
            return EngineResult.Error(
                "INSTALL_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private data class ResolvedMetadata(
        val descriptor: ApkDescriptor,
        val index: ApkInstallIndex
    )

    private fun resolveInstalledMetadata(app: StoredApp): ResolvedMetadata {
        val cached = app.apkIndex?.let { index ->
            ApkMetadataCache.read(
                index = index,
                baseApkPath = app.baseApkPath,
                expectedPackageName = app.packageName,
                expectedVersionCode = app.versionCode,
                expectedSignatureSha256 = app.signatureSha256
            )
        }
        if (cached != null) {
            Log.d(TAG, "APK metadata cache hit: ${app.packageName}")
            return ResolvedMetadata(cached, app.apkIndex!!)
        }

        Log.d(TAG, "APK metadata cache rebuild: ${app.packageName}")
        val descriptor = ApkInspector.inspect(context, app.baseApkPath, true)
        if (descriptor.packageName != app.packageName ||
            descriptor.versionCode != app.versionCode ||
            (app.signatureSha256.isNotBlank() &&
                descriptor.signatureSha256 != app.signatureSha256)
        ) {
            error(
                AppLanguage.tr(
                    "APK 与安装记录不一致",
                    "APK does not match the install record"
                )
            )
        }
        val paths = listOf(app.baseApkPath) + app.splitApkPaths
        val digests = paths.associateWith { ApkPayloadIdentity.sha256(File(it)) }
        val nativeAbis = descriptor.nativeAbis +
            if (app.splitApkPaths.isEmpty()) {
                emptySet()
            } else {
                NativeLibs.abisOf(app.splitApkPaths)
            }
        val index = ApkInstallIndex(
            schemaVersion = APK_INDEX_SCHEMA_VERSION,
            files = paths.map { path ->
                ApkFileFingerprint(
                    path = path,
                    size = File(path).length(),
                    lastModified = File(path).lastModified(),
                    sha256 = digests.getValue(path)
                )
            },
            nativeAbis = nativeAbis,
            descriptorCachePath = ApkMetadataCache.descriptorPath(app.baseApkPath)
        )
        ApkMetadataCache.write(descriptor, index)
        return ResolvedMetadata(descriptor, index)
    }

    private suspend fun launchLocked(
        app: StoredApp,
        instanceId: String,
        headless: Boolean = false,
        requestedActivity: String? = null,
        requestedIntent: Intent? = null
    ): EngineResult<LaunchInfo> {
        Log.d(TAG, "launchLocked ${app.packageName} $instanceId")
        TargetWebViewIdentityHook.prefetch(context)
        if (!headless) {
            // Opening an app full-screen replaces the floating mirror preview.
            HeadlessMirrorWindow.hide()
        }
        val resolved = try {
            resolveInstalledMetadata(app)
        } catch (t: Throwable) {
            return EngineResult.Error(
                "APK_INVALID",
                AppLanguage.tr(
                    "APK 已损坏或丢失：${t.message ?: t.javaClass.simpleName}",
                    "The APK is corrupt or missing: ${t.message ?: t.javaClass.simpleName}"
                )
            )
        }
        val effectiveApp = if (resolved.index != app.apkIndex) {
            app.copy(apkIndex = resolved.index).also {
                apps[key(it)] = it
                runCatching { store.save(apps.values) }
                    .onFailure { error -> Log.w(TAG, "save APK index failed", error) }
            }
        } else {
            app
        }
        val instance = effectiveApp.instances.firstOrNull { it.id == instanceId }
            ?: return EngineResult.Error("INSTANCE_NOT_FOUND", AppLanguage.tr("找不到实例 $instanceId", "Instance $instanceId not found"))

        val descriptor = resolved.descriptor
        Log.d(TAG, "launchLocked metadata ${descriptor.packageName}")
        if (descriptor.packageName != effectiveApp.packageName ||
            descriptor.launcherActivity.isNullOrBlank() ||
            descriptor.activityInfo == null
        ) {
            return EngineResult.Error("APK_INVALID", AppLanguage.tr("APK 与安装记录不一致", "APK does not match the install record"))
        }
        val launchActivity = requestedActivity?.takeIf { it.isNotBlank() }
            ?: descriptor.launcherActivity
        if (requestedActivity != null &&
            descriptor.activities.none {
                it.name == requestedActivity || it.targetActivity == requestedActivity
            }
        ) {
            return EngineResult.Error(
                "ACTIVITY_NOT_FOUND",
                AppLanguage.tr(
                    "快捷方式指向的 Activity 不存在：$requestedActivity",
                    "The shortcut's Activity does not exist: $requestedActivity"
                )
            )
        }

        val nativeAbis = resolved.index.nativeAbis
        val preferredNativeAbi = NativeLibs.preferredHostAbi(context)
        if (nativeAbis.isNotEmpty() &&
            NativeLibs.supportedAbi(nativeAbis, preferredNativeAbi) == null
        ) {
            return EngineResult.Error(
                "ABI_MISMATCH",
                AppLanguage.tr(
                    "该应用只包含 ${nativeAbis.joinToString(", ")} 原生库，" +
                        "当前设备 ABI 为 ${Build.SUPPORTED_ABIS.joinToString(", ")}，" +
                        "无法运行。请换用支持该 ABI 的真机或模拟器。",
                    "This app only contains ${nativeAbis.joinToString(", ")} native " +
                        "libraries, while the device ABI is " +
                        "${Build.SUPPORTED_ABIS.joinToString(", ")}. Use a device or " +
                        "emulator that supports this ABI."
                )
            )
        }

        ensureInstanceDirs(effectiveApp.packageName, instance.id)
        val now = System.currentTimeMillis()
        val updated = effectiveApp.copy(
            instances = effectiveApp.instances.map {
                if (it.id == instance.id) it.copy(lastLaunchedAt = now) else it
            }
        )
        apps[key(updated)] = updated
        runCatching { store.save(apps.values) }
            .onFailure { Log.w(TAG, "save launch time failed", it) }

        val dataDir = dataDirFor(effectiveApp.packageName, instance.id)
        val launchActivityInfo = descriptor.activities.firstOrNull {
            it.name == launchActivity || it.targetActivity == launchActivity
        } ?: descriptor.activityInfo
        val targetProcess = ContainerProcessRouter.normalize(
            effectiveApp.packageName,
            launchActivityInfo.processName
        )
        val sessionId = ContainerProcessRouter.sessionId(
            effectiveApp.packageName,
            instance.id,
            targetProcess
        )
        val slot = ContainerProcessPool.acquire(
            context,
            ContainerProcessRouter.slotKey(
                effectiveApp.packageName,
                instance.id,
                targetProcess
            )
        )
        val hostClass = if (launchActivityInfo.launchMode ==
            android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE
        ) {
            ContainerProcessPool.isolatedActivityClass(slot)
        } else {
            ContainerProcessPool.activityClass(slot)
        }
        val targetIntent = requestedIntent ?: Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(effectiveApp.packageName, launchActivity)
        if (requestedIntent != null && requestedIntent.component == null) {
            targetIntent.setClassName(effectiveApp.packageName, launchActivity)
        }
        val intent = Intent(context, hostClass)
            .putExtra(StageAActivity.EXTRA_LAUNCH_PACKAGE, effectiveApp.packageName)
            .putExtra(StageAActivity.EXTRA_APP_LABEL, effectiveApp.label)
            .putExtra(StageAActivity.EXTRA_LAUNCH_INSTANCE, instance.id)
            .putExtra(StageAActivity.EXTRA_SESSION_ID, sessionId)
            .putExtra(StageAActivity.EXTRA_TARGET_PROCESS, targetProcess)
            .putExtra(StageAActivity.EXTRA_ACTIVITY_CLASS, launchActivity)
            .putExtra(StageAActivity.EXTRA_CONTAINER_ENTRY, true)
            .putExtra(StageAActivity.EXTRA_TARGET_INTENT, targetIntent)
            .putExtra(StageAActivity.EXTRA_HEADLESS, headless)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        var mirrorLaunch = false
        if (headless) {
            // A previous headless host may still hold a task for this slot.
            // MULTIPLE_TASK would stack another one, so remove old host tasks
            // before starting the new run.
            finishPreviousHostTasks(slot)
            val mirrorId = runCatching { HeadlessMirrorWindow.awaitDisplay(context) }
                .onFailure { Log.w(TAG, "mirror display unavailable", it) }
                .getOrNull()
            if (mirrorId != null) {
                Log.i(TAG, "using mirror display $mirrorId")
                val previousMirrorSlot = HeadlessMirrorWindow.activeSlot()
                if (previousMirrorSlot >= 0 && previousMirrorSlot != slot) {
                    finishPreviousHostTasks(previousMirrorSlot)
                }
                HeadlessMirrorWindow.setActiveSlot(slot)
                intent.putExtra(StageAActivity.EXTRA_MIRROR_DISPLAY_ID, mirrorId)
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                mirrorLaunch = true
            }
        }
        val webEnvironmentReady = TargetWebViewIdentityHook.awaitPrefetch()
        Log.d(TAG, "web environment ready=$webEnvironmentReady")
        try {
            if (headless && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // The target host must never become the foreground task, even
                // for one transition frame. This also keeps the assistant page
                // stable when the mirror display is unavailable.
                context.startActivity(
                    intent,
                    ActivityOptions.makeTaskLaunchBehind().toBundle()
                )
            } else {
                context.startActivity(intent)
            }
        } catch (t: Throwable) {
            // The slot was assigned before the OS rejected the launch (for
            // example a BAL restriction). Release it instead of leaving a
            // ghost process assignment in Running apps.
            runCatching { ContainerProcessPool.forget(context, sessionId) }
            return EngineResult.Error(
                "LAUNCH_FAILED",
                t.message ?: t.javaClass.simpleName
            )
        }
        Log.d(TAG, "headless=$headless mirror=$mirrorLaunch slot=$slot")
        ContainerProcessPool.markLaunched(context, sessionId)
        // Headless keep-alive is established by the agent tools once the UI
        // service is reachable, so a failed launch cannot start a service-only
        // slot process.
        // Consume the marker written by the previous process death (native
        // crash / System.exit) or by TargetCrashGuard.
        val crash = ContainerCrashStore.consume(
            context,
            effectiveApp.packageName,
            instance.id
        )
        if (crash != null) {
            Log.w(TAG, "launch after crash ${crash.reason}")
        }

        publish()
        return EngineResult.Ok(
            LaunchInfo(
                spaceId = effectiveApp.spaceId,
                packageName = effectiveApp.packageName,
                instanceId = instance.id,
                activity = launchActivity,
                dataDir = dataDir.absolutePath,
                targetProcess = targetProcess,
                slot = slot,
                restartedAfterCrash = crash != null,
                crashReason = crash?.reason
            )
        )
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        rootDir.mkdirs()
        var changed = false
        store.load().forEach { stored ->
            val baseOk = File(stored.baseApkPath).isFile
            val splitsOk = stored.splitApkPaths.all { File(it).isFile }
            if (!baseOk || !splitsOk) {
                changed = true
                return@forEach
            }
            val app = if (stored.instances.isEmpty()) {
                changed = true
                stored.copy(
                    instances = listOf(
                        StoredInstance("instance-1", System.currentTimeMillis(), 0L)
                    )
                )
            } else {
                stored
            }
            // Migrate records created before the Android 14 read-only rule.
            File(app.baseApkPath).setReadOnly()
            app.splitApkPaths.forEach { File(it).setReadOnly() }
            apps[key(app)] = app
            app.instances.forEach { ensureInstanceDirs(app.packageName, it.id) }
        }
        if (changed) {
            runCatching { store.save(apps.values) }
                .onFailure { Log.w(TAG, "normalize state failed", it) }
        }
        loaded = true
        publish()
    }

    private fun publish() {
        _virtualApps.value = apps.values
            .map { it.toPublic() }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
    }

    private fun publicApps(spaceId: String): List<VirtualApp> =
        apps.values
            .filter { it.spaceId == spaceId }
            .sortedBy { it.label.lowercase() }
            .map { it.toPublic() }

    private fun StoredApp.toPublic(): VirtualApp = VirtualApp(
        spaceId = spaceId,
        packageName = packageName,
        label = label,
        versionName = versionName,
        versionCode = versionCode,
        launcherActivity = launcherActivity,
        signatureSha256 = signatureSha256,
        hasNativeLibs = hasNativeLibs,
        installedAt = installedAt,
        updatedAt = updatedAt,
        instances = instances.map {
            VirtualInstance(
                id = it.id,
                createdAt = it.createdAt,
                lastLaunchedAt = it.lastLaunchedAt,
                dataDir = dataDirFor(packageName, it.id).absolutePath,
                environmentVersion = it.environmentVersion,
                compatibilityProfile = it.compatibilityProfile,
                lastProbeResult = it.lastProbeResult
            )
        },
        icon = loadIcon(this)
    )

    private fun loadIcon(app: StoredApp): ImageBitmap? {
        val cacheKey = "${app.baseApkPath}@${app.updatedAt}"
        synchronized(iconCache) {
            if (iconCache.containsKey(cacheKey)) return iconCache[cacheKey]
        }
        val icon = runCatching {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(app.baseApkPath, 0)?.applicationInfo
                ?: return@runCatching null
            info.sourceDir = app.baseApkPath
            info.publicSourceDir = app.baseApkPath
            info.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
        synchronized(iconCache) { iconCache[cacheKey] = icon }
        return icon
    }

    private fun defaultInstance(app: StoredApp): StoredInstance =
        app.instances.maxByOrNull { it.lastLaunchedAt } ?: app.instances.first()

    private fun nextInstanceId(app: StoredApp): String {
        val max = app.instances.mapNotNull {
            it.id.removePrefix("instance-").toIntOrNull()
        }.maxOrNull() ?: 0
        var next = max + 1
        while (app.instances.any { it.id == "instance-$next" }) next++
        return "instance-$next"
    }

    /**
     * Finish StageA host tasks left over from a previous headless launch of
     * this slot so a stale task cannot stay on screen next to the new host.
     */
    private fun finishPreviousHostTasks(slot: Int) {
        val hostNames = setOf(
            ContainerProcessPool.activityClass(slot).name,
            ContainerProcessPool.isolatedActivityClass(slot).name
        )
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        manager.appTasks.orEmpty().forEach { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@forEach
            val matchesHost = listOfNotNull(
                info.baseActivity?.className,
                info.topActivity?.className
            ).any { it in hostNames }
            if (!matchesHost) {
                return@forEach
            }
            Log.d(TAG, "finish old host task ${info.taskId} for slot $slot")
            runCatching { task.finishAndRemoveTask() }
        }
    }

    private fun closeInstanceRuntime(packageName: String, instanceId: String) {
        val closedSlots = ContainerProcessPool.closeInstance(
            context,
            packageName,
            instanceId
        )
        closedSlots.forEach { slot ->
            ContainerUiKeepAlive.unbind(context, slot)
            finishPreviousHostTasks(slot)
        }
    }

    private fun ensureInstanceDirs(packageName: String, instanceId: String) {
        val root = dataDirFor(packageName, instanceId)
        listOf("files", "cache", "code_cache", "no_backup", "databases", "shared_prefs", "obb")
            .forEach { File(root, it).mkdirs() }
    }

    private fun dataDirFor(packageName: String): File =
        File(dataRootDir, sanitize(packageName))

    private fun dataDirFor(packageName: String, instanceId: String): File =
        File(dataDirFor(packageName), sanitize(instanceId))

    private fun key(app: StoredApp): String = key(app.spaceId, app.packageName)

    private fun key(spaceId: String, packageName: String): String =
        "$spaceId::$packageName"

    private fun copyUriToTemp(uriString: String, target: File): File {
        val source = when {
            uriString.startsWith("content://") -> null
            uriString.startsWith("file://") -> File(
                Uri.parse(uriString).path ?: error(AppLanguage.tr("无效 URI: $uriString", "Invalid URI: $uriString"))
            )
            else -> File(uriString)
        }
        if (source != null) {
            if (!source.isFile) error(
                    AppLanguage.tr(
                        "APK 不存在: ${source.absolutePath}",
                        "APK does not exist: ${source.absolutePath}"
                    )
                )
            source.copyTo(target, overwrite = true)
        } else {
            val input = context.contentResolver.openInputStream(Uri.parse(uriString))
                ?: error(AppLanguage.tr("无法读取 APK: $uriString", "Could not read the APK: $uriString"))
            input.use { from ->
                target.outputStream().use { to -> from.copyTo(to) }
            }
        }
        if (!target.isFile || target.length() == 0L) {
            error(AppLanguage.tr("APK 拷贝失败: $uriString", "Failed to copy the APK: $uriString"))
        }
        return target
    }

    private fun moveFile(source: File, target: File, expectedSha256: String) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        if (ApkPayloadIdentity.sha256(target) != expectedSha256) {
            target.delete()
            throw InstallFailure(
                "INSTALL_FAILED",
                AppLanguage.tr(
                    "APK 移动后内容校验失败",
                    "APK content verification failed after moving the file"
                )
            )
        }
        source.delete()
    }

    private fun AppShortcut.toIntent(packageName: String): Intent {
        val shortcut = this
        val parsed = shortcut.intentUri?.let {
            runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull()
        }
        val intent = parsed ?: Intent().apply {
            shortcut.action?.let { action = it }
            val dataUri = shortcut.data
                ?.takeIf { it.isNotBlank() }
                ?.let { Uri.parse(it) }
            if (dataUri != null && !shortcut.mimeType.isNullOrBlank()) {
                setDataAndType(dataUri, shortcut.mimeType)
            } else if (dataUri != null) {
                setData(dataUri)
            } else if (!shortcut.mimeType.isNullOrBlank()) {
                type = shortcut.mimeType
            }
            shortcut.categories.forEach { addCategory(it) }
            putExtras(shortcut.extras)
        }
        intent.setClassName(packageName, shortcut.targetActivity)
        return intent
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private class InstallFailure(val code: String, message: String) : Exception(message)

    companion object {
        private const val TAG = "MiniContainerEngine"
    }
}
