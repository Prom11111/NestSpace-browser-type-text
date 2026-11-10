package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.container.loader.ApkInspector
import com.coke.nestspace.container.loader.ApkMetadataCache


import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import com.coke.nestspace.container.loader.NativeLibs
import com.coke.nestspace.engine.SpaceStore
import java.io.File

/**
 * Rebuilds a launch descriptor inside the container process.
 *
 * The engine runs in the main process; the target app's Application,
 * ClassLoader and native libraries live in a dedicated container process.
 * In-memory hand-off objects cannot cross that boundary, so the remote process
 * reads the persisted install record and re-inspects the APK instead.
 */
internal object ContainerSpecFactory {

    fun build(
        context: Context,
        packageName: String,
        instanceId: String,
        sessionId: String,
        targetProcessName: String? = null,
        requestedActivityClass: String? = null
    ): ContainerLaunchSpec {
        val root = File(context.filesDir, "vspace")
        val store = SpaceStore(File(root, "state.json"))
        val app = store.load().firstOrNull { it.packageName == packageName }
            ?: error(AppLanguage.tr("应用未安装：$packageName", "App not installed: $packageName"))
        val instance = app.instances.firstOrNull { it.id == instanceId }
            ?: error(AppLanguage.tr("实例不存在：$instanceId", "Instance does not exist: $instanceId"))

        val cachedDescriptor = app.apkIndex?.let { index ->
            ApkMetadataCache.read(
                index = index,
                baseApkPath = app.baseApkPath,
                expectedPackageName = app.packageName,
                expectedVersionCode = app.versionCode,
                expectedSignatureSha256 = app.signatureSha256
            )
        }
        val importedDescriptor = cachedDescriptor ?: ApkInspector.inspect(
            context,
            app.baseApkPath,
            requireLauncher = true
        )
        android.util.Log.d(
            "ContainerSpecFactory",
            if (cachedDescriptor != null) {
                "APK metadata cache hit: $packageName"
            } else {
                "APK metadata cache fallback: $packageName"
            }
        )
        val nativeAbis = app.apkIndex
            ?.takeIf { it.isCurrent() }
            ?.nativeAbis
            ?: NativeLibs.abisOf(listOf(app.baseApkPath) + app.splitApkPaths)
        val installed = installedPackage(context, packageName, importedDescriptor)
        val descriptor = installed?.descriptor ?: importedDescriptor
        val baseApkPath = installed?.baseApkPath ?: app.baseApkPath
        val splitApkPaths = installed?.splitApkPaths ?: app.splitApkPaths
        val packageFirstInstallTime =
            installed?.firstInstallTime?.takeIf { it > 0L }
                ?: app.installedAt.takeIf { it > 0L }
                ?: File(baseApkPath).lastModified().takeIf { it > 0L }
                ?: System.currentTimeMillis()
        val packageLastUpdateTime =
            installed?.lastUpdateTime?.takeIf { it > 0L }
                ?: app.updatedAt.takeIf { it > 0L }
                ?: packageFirstInstallTime
        if (descriptor.packageName != packageName) {
            error(
                AppLanguage.tr(
                    "APK 与安装记录不一致：${descriptor.packageName}",
                    "APK does not match the install record: ${descriptor.packageName}"
                )
            )
        }
        val descriptorActivities = descriptor.activities.toMutableList()
        val installedLauncherInfo = installed?.launcherActivityInfo
        if (installedLauncherInfo != null &&
            descriptorActivities.none { it.name == installedLauncherInfo.name }
        ) {
            descriptorActivities += installedLauncherInfo
        }
        val activity = requestedActivityClass
            ?.takeIf { it.isNotBlank() }
            ?.let { requested ->
                descriptorActivities.firstOrNull {
                    it.name == requested || it.targetActivity == requested
                }
            }
            ?: installedLauncherInfo
            ?: descriptor.activityInfo
            ?: error(AppLanguage.tr("APK 没有入口 Activity", "APK has no launcher activity"))

        android.util.Log.d(
            "ContainerSpecFactory",
            "launcher=${descriptor.launcherActivity} info=${activity.name} " +
                "target=${activity.targetActivity}"
        )

        val dataDir = File(File(root, "data"), sanitize(packageName))
            .resolve(sanitize(instanceId))
        dataDir.mkdirs()
        listOf(
            "files",
            "cache",
            "code_cache",
            "no_backup",
            "databases",
            "shared_prefs",
            "obb",
            com.coke.nestspace.container.runtime.VirtualStorageLayout.DEVICE_PROTECTED_DIRECTORY
        )
            .forEach { File(dataDir, it).mkdirs() }

        return ContainerLaunchSpec(
            sessionId = sessionId,
            spaceId = app.spaceId,
            packageName = packageName,
            instanceId = instanceId,
            targetProcessName = ContainerProcessRouter.normalize(packageName, targetProcessName),
            label = app.label,
            baseApkPath = baseApkPath,
            splitApkPaths = splitApkPaths,
            launcherActivity = activity.name ?: app.launcherActivity,
            activityInfo = activity,
            activities = descriptorActivities,
            services = descriptor.services,
            providers = descriptor.providers,
            receivers = descriptor.receivers,
            activityFilters = descriptor.activityFilters,
            serviceFilters = descriptor.serviceFilters,
            receiverFilters = descriptor.receiverFilters,
            packageInfo = descriptor.packageInfo.apply {
                firstInstallTime = packageFirstInstallTime
                lastUpdateTime = packageLastUpdateTime
            },
            applicationInfo = descriptor.applicationInfo,
            dataDir = dataDir.absolutePath,
            activityTheme = activity.theme,
            applicationTheme = descriptor.applicationInfo.theme,
            installedNativeLibraryDir = installed?.nativeLibraryDir,
            nativeAbis = nativeAbis
        )
    }

    private data class InstalledPackage(
        val descriptor: com.coke.nestspace.container.loader.ApkDescriptor,
        val baseApkPath: String,
        val splitApkPaths: List<String>,
        val nativeLibraryDir: String?,
        val launcherActivityInfo: ActivityInfo?,
        val firstInstallTime: Long,
        val lastUpdateTime: Long
    )

    /**
     * Prefer the platform-installed copy only when it is byte-identical in
     * version and signer with the APK imported into this space. This keeps
     * native SDK path checks on the normal Android package layout without
     * silently switching to a different app build.
     */
    @Suppress("DEPRECATION")
    private fun installedPackage(
        context: Context,
        packageName: String,
        imported: com.coke.nestspace.container.loader.ApkDescriptor
    ): InstalledPackage? = runCatching {
        val signatureFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageInfo(
            packageName,
            signatureFlag
        )
        val installedVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val importedSignature = imported.signatureSha256 ?: return@runCatching null
        if (installedVersionCode != imported.versionCode) return@runCatching null

        val appInfo = info.applicationInfo ?: return@runCatching null
        val baseApkPath = appInfo.sourceDir ?: return@runCatching null
        if (!File(baseApkPath).canRead()) return@runCatching null

        val installedSignature = ApkInspector.signatureDigest(info)
        val descriptor = if (installedSignature == null) {
            // Some vendor PackageManagers omit signingInfo even when asked.
            // Preserve the metadata-cache fast path only when the signer is
            // directly available; otherwise fall back to a full APK inspect.
            ApkInspector.inspect(context, baseApkPath, requireLauncher = true)
        } else {
            if (installedSignature != importedSignature) return@runCatching null
            imported
        }
        if (descriptor.packageName != packageName ||
            descriptor.versionCode != imported.versionCode ||
            descriptor.signatureSha256 != importedSignature
        ) {
            return@runCatching null
        }

        val splitApkPaths = appInfo.splitSourceDirs
            ?.filter { path -> path.isNotBlank() && File(path).isFile }
            .orEmpty()
        val nativeLibraryDir = appInfo.nativeLibraryDir
            ?.takeIf { path -> path.isNotBlank() && File(path).isDirectory }
        val launcherActivityInfo = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?.component
            ?.let { component ->
                context.packageManager.getActivityInfo(component, 0)
            }
        InstalledPackage(
            descriptor = descriptor,
            baseApkPath = baseApkPath,
            splitApkPaths = splitApkPaths,
            nativeLibraryDir = nativeLibraryDir,
            launcherActivityInfo = launcherActivityInfo,
            firstInstallTime = info.firstInstallTime,
            lastUpdateTime = info.lastUpdateTime
        )
            .also {
                android.util.Log.d(
                    "ContainerSpecFactory",
                    "using installed package path=$baseApkPath " +
                        "nativeLibs=${it.nativeLibraryDir}"
                )
            }
    }.getOrNull()

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

}
