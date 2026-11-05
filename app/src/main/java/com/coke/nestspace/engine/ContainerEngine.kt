package com.coke.nestspace.engine

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.LaunchInfo
import com.coke.nestspace.data.VirtualApp
import kotlinx.coroutines.flow.MutableStateFlow
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

    suspend fun listApps(spaceId: String): EngineResult<List<AppInfo>>

    suspend fun listInstalled(spaceId: String): EngineResult<List<VirtualApp>>

    suspend fun launch(
        spaceId: String,
        packageName: String,
        instanceId: String? = null,
        headless: Boolean = false
    ): EngineResult<LaunchInfo>

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

/**
 * Placeholder retained for reference. The app uses MiniContainerEngine.
 */
class PlaceholderContainerEngine : ContainerEngine {

    private val empty = MutableStateFlow<List<VirtualApp>>(emptyList())

    override val virtualApps: StateFlow<List<VirtualApp>> = empty

    private val notReady = EngineResult.Error(
        "ENGINE_NOT_AVAILABLE",
        AppLanguage.tr(
            "自研容器尚未接入；当前基础版先控制系统空间。",
            "The in-house container is not wired up yet; this build controls the " +
                "system space first."
        )
    )

    override suspend fun install(
        spaceId: String,
        apkUris: List<String>
    ): EngineResult<VirtualApp> = notReady

    override suspend fun listApps(spaceId: String): EngineResult<List<AppInfo>> = notReady

    override suspend fun listInstalled(spaceId: String): EngineResult<List<VirtualApp>> = notReady

    override suspend fun launch(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        headless: Boolean
    ): EngineResult<LaunchInfo> = notReady

    override suspend fun multiOpen(
        spaceId: String,
        packageName: String,
        headless: Boolean
    ): EngineResult<LaunchInfo> = notReady

    override suspend fun shortcuts(spaceId: String, packageName: String): List<AppShortcut> =
        emptyList()

    override suspend fun launchShortcut(
        spaceId: String,
        packageName: String,
        instanceId: String?,
        shortcut: AppShortcut,
        headless: Boolean
    ): EngineResult<LaunchInfo> = notReady

    override suspend fun stop(spaceId: String, packageName: String): EngineResult<Unit> = notReady

    override suspend fun uninstall(spaceId: String, packageName: String): EngineResult<Unit> =
        notReady
}
