package com.coke.nestspace.engine

import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.LaunchInfo
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.data.VirtualInstance
import kotlinx.coroutines.flow.StateFlow

sealed class EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>()
    data class Error(val code: String, val message: String) : EngineResult<Nothing>()
}

interface ContainerEngine {
    /** All installed virtual apps; the UI filters by space. */
    val virtualApps: StateFlow<List<VirtualApp>>

    /**
     * Installs a base APK plus optional split APKs. All URIs must belong to the
     * same package and be signed by the same certificate.
     */
    suspend fun install(spaceId: String, apkUris: List<String>): EngineResult<VirtualApp>

    /**
     * True only when installing [apkUris] would be a no-op because the same
     * package, version and APK payload are already present in [spaceId].
     */
    suspend fun matchesInstalledPayload(spaceId: String, apkUris: List<String>): Boolean

    /**
     * True when [apkUris] belong to an app already installed in [spaceId] and
     * use the same signing certificate. A changed debug build is an update,
     * not a new app install.
     */
    suspend fun matchesInstalledIdentity(spaceId: String, apkUris: List<String>): Boolean

    suspend fun listApps(spaceId: String): EngineResult<List<AppInfo>>

    suspend fun listInstalled(spaceId: String): EngineResult<List<VirtualApp>>

    suspend fun launch(
        spaceId: String,
        packageName: String,
        instanceId: String? = null,
        headless: Boolean = false,
        requestedActivity: String? = null
    ): EngineResult<LaunchInfo>

    /** Creates an independent instance without opening it. */
    suspend fun createInstance(
        spaceId: String,
        packageName: String
    ): EngineResult<VirtualInstance>

    /** Deletes one isolated instance without uninstalling the shared APK. */
    suspend fun deleteInstance(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit>

    /** Clears app data while preserving the instance record and APK. */
    suspend fun clearInstanceData(
        spaceId: String,
        packageName: String,
        instanceId: String
    ): EngineResult<Unit>

    suspend fun multiOpen(
        spaceId: String,
        packageName: String,
        headless: Boolean = false
    ): EngineResult<LaunchInfo>

    /** Static app shortcuts declared by the APK (parsed lazily on first use). */
    suspend fun shortcuts(spaceId: String, packageName: String): List<AppShortcut>

    /** Launch a static app shortcut (its action/extra/activity) in the container. */
    suspend fun launchShortcut(
        spaceId: String,
        packageName: String,
        instanceId: String? = null,
        shortcut: AppShortcut,
        headless: Boolean = false
    ): EngineResult<LaunchInfo>

    suspend fun stop(spaceId: String, packageName: String): EngineResult<Unit>

    suspend fun uninstall(spaceId: String, packageName: String): EngineResult<Unit>
}
