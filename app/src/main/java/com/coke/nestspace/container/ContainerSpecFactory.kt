package com.coke.nestspace.container

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.container.loader.ApkInspector


import android.content.Context
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
        targetProcessName: String? = null
    ): ContainerLaunchSpec {
        val root = File(context.filesDir, "vspace")
        val store = SpaceStore(File(root, "state.json"))
        val app = store.load().firstOrNull { it.packageName == packageName }
            ?: error(AppLanguage.tr("应用未安装：$packageName", "App not installed: $packageName"))
        if (app.instances.none { it.id == instanceId }) {
            error(AppLanguage.tr("实例不存在：$instanceId", "Instance does not exist: $instanceId"))
        }

        val descriptor = ApkInspector.inspect(context, app.baseApkPath, requireLauncher = true)
        if (descriptor.packageName != packageName) {
            error(
                AppLanguage.tr(
                    "APK 与安装记录不一致：${descriptor.packageName}",
                    "APK does not match the install record: ${descriptor.packageName}"
                )
            )
        }
        val activity = descriptor.activityInfo
            ?: error(AppLanguage.tr("APK 没有入口 Activity", "APK has no launcher activity"))

        val dataDir = File(File(root, "data"), sanitize(packageName))
            .resolve(sanitize(instanceId))
        dataDir.mkdirs()
        listOf("files", "cache", "code_cache", "no_backup", "databases", "shared_prefs", "obb")
            .forEach { File(dataDir, it).mkdirs() }

        return ContainerLaunchSpec(
            sessionId = sessionId,
            spaceId = app.spaceId,
            packageName = packageName,
            instanceId = instanceId,
            targetProcessName = ContainerProcessRouter.normalize(packageName, targetProcessName),
            label = app.label,
            baseApkPath = app.baseApkPath,
            splitApkPaths = app.splitApkPaths,
            launcherActivity = activity.name ?: app.launcherActivity,
            activityInfo = activity,
            activities = descriptor.activities,
            services = descriptor.services,
            providers = descriptor.providers,
            receivers = descriptor.receivers,
            activityFilters = descriptor.activityFilters,
            serviceFilters = descriptor.serviceFilters,
            receiverFilters = descriptor.receiverFilters,
            packageInfo = descriptor.packageInfo,
            applicationInfo = descriptor.applicationInfo,
            dataDir = dataDir.absolutePath,
            activityTheme = activity.theme,
            applicationTheme = descriptor.applicationInfo.theme
        )
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

}
