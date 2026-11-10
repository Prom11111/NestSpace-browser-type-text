package com.coke.nestspace.container.application

import com.coke.nestspace.container.ContainerLaunchSpec
import com.coke.nestspace.runtime.plugin.RuntimeLaunchSpec

internal fun ContainerLaunchSpec.toRuntimeApplicationSpec():
    RuntimeLaunchSpec {
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    return RuntimeLaunchSpec.Builder()
        .sessionId(sessionId)
        .spaceId(spaceId)
        .packageName(packageName)
        .instanceId(instanceId)
        .targetProcessName(targetProcessName)
        .label(label)
        .baseApkPath(baseApkPath)
        .splitApkPaths(splitApkPaths)
        .launcherActivity(launcherActivity)
        .versionCode(versionCode)
        .applicationClassName(applicationInfo.className.orEmpty())
        .dataDir(dataDir)
        .nativeAbis(nativeAbis.toList())
        .androidApi(android.os.Build.VERSION.SDK_INT)
        .build()
}
