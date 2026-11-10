package com.coke.nestspace.container

import com.coke.nestspace.container.intent.ContainerIntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.ServiceInfo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory hand-off between the engine (which owns installed-app state) and
 * StageBActivity (which only receives a small token through the Intent).
 */
data class ContainerLaunchSpec(
    val sessionId: String,
    val spaceId: String,
    val packageName: String,
    val instanceId: String,
    /** Fully-qualified target process name ("pkg" for main, "pkg:download" for subprocesses). */
    val targetProcessName: String,
    val label: String,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val launcherActivity: String,
    val activityInfo: ActivityInfo,
    val activities: List<ActivityInfo>,
    val services: List<ServiceInfo>,
    val providers: List<ProviderInfo>,
    val receivers: List<ActivityInfo>,
    val activityFilters: Map<String, List<ContainerIntentFilter>>,
    val serviceFilters: Map<String, List<ContainerIntentFilter>>,
    val receiverFilters: Map<String, List<ContainerIntentFilter>>,
    val packageInfo: PackageInfo,
    val applicationInfo: ApplicationInfo,
    val dataDir: String,
    val activityTheme: Int,
    val applicationTheme: Int,
    val installedNativeLibraryDir: String? = null,
    val nativeAbis: Set<String> = emptySet()
)

object ContainerLaunchRegistry {

    private val specs = ConcurrentHashMap<String, ContainerLaunchSpec>()

    fun register(spec: ContainerLaunchSpec): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        specs[token] = spec
        return token
    }

    fun get(token: String): ContainerLaunchSpec? = specs[token]

    fun remove(token: String) {
        specs.remove(token)
    }
}
