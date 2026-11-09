package com.coke.nestspace.vm

import com.coke.nestspace.i18n.AppLanguage
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coke.nestspace.AppGraph
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppShortcut
import com.coke.nestspace.data.AppRepository
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.DesktopAppsStore
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmConfigState
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.data.SpaceInfo
import com.coke.nestspace.data.SpaceType
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.data.WallpaperStore
import com.coke.nestspace.engine.EngineResult
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.llm.LlmProbe
import com.coke.nestspace.service.McpForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class VirtualViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AppRepository(app)
    private val engine = AppGraph.engine(app)
    private val wallpaperStore = WallpaperStore(app)
    private val llmStore = LlmConfigStore(app)
    private val initialLlmState = llmStore.load()

    val llmConfigs = MutableStateFlow(initialLlmState.configs)
    val activeLlmId = MutableStateFlow(initialLlmState.activeId)
    val llmConfig = MutableStateFlow(activeConfig(initialLlmState))
    private val _llmToast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val llmToast: SharedFlow<String> = _llmToast.asSharedFlow()
    val llmTesting = MutableStateFlow(false)
    val agentBusy: StateFlow<Boolean> = AssistantConversation.busy

    val apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val loading = MutableStateFlow(false)
    val installing = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    val wallpaper = MutableStateFlow<ImageBitmap?>(null)
    val desktopSystemPackages = MutableStateFlow(DesktopAppsStore.load(app))
    val virtualApps: StateFlow<List<VirtualApp>> = engine.virtualApps
    val messages: StateFlow<List<AgentMessage>> = AssistantConversation.messages
    val spaces = MutableStateFlow(
        listOf(
            SpaceInfo(
                SPACE_ID,
                AppLanguage.tr("虚拟空间 1", "Virtual space 1"),
                SpaceType.VIRTUAL,
                AppLanguage.tr(
                    "Stage C-D：进程隔离 / Job / 通知 / VPMS",
                    "Stage C-D: process isolation / jobs / notifications / VPMS"
                ),
                0
            )
        )
    )
    val mcpStatus: StateFlow<McpStatus> = McpForegroundService.status

    init {
        viewModelScope.launch {
            engine.virtualApps.collect { updateSpaces(it) }
        }
        viewModelScope.launch {
            wallpaper.value = withContext(Dispatchers.IO) { wallpaperStore.load() }
        }
        refreshVirtualApps()
        refreshApps()
        sanitizeDesktopSystemApps()
        viewModelScope.launch {
            // MCP settings changes happen outside these StateFlows.
            AppSettingsEvents.revision.drop(1).collect {
                val state = withContext(Dispatchers.IO) { llmStore.load() }
                llmConfigs.value = state.configs
                activeLlmId.value = state.activeId
                llmConfig.value = activeConfig(state)
                desktopSystemPackages.value = DesktopAppsStore.load(getApplication())
                wallpaper.value = withContext(Dispatchers.IO) { wallpaperStore.load() }
            }
        }
    }

    /**
     * NestSpace itself is launchable, but it must never be offered as a desktop
     * shortcut (it only reopens this screen). Older builds allowed selecting it,
     * so drop any persisted entry.
     */
    private fun sanitizeDesktopSystemApps() {
        val self = getApplication<Application>().packageName
        val current = desktopSystemPackages.value
        if (self !in current) return
        val cleaned = current - self
        desktopSystemPackages.value = cleaned
        DesktopAppsStore.save(getApplication(), cleaned)
    }

    fun refreshApps() {
        viewModelScope.launch {
            loading.value = true
            runCatching { repository.listLaunchableApps() }
                .onSuccess { list ->
                    apps.value = list
                }
                .onFailure { addError(
                            AppLanguage.tr(
                                "读取应用列表失败：${it.message}",
                                "Failed to load the app list: ${it.message}"
                            )
                        ) }
            loading.value = false
        }
    }

    fun refreshVirtualApps() {
        viewModelScope.launch {
            when (val result = engine.listInstalled(SPACE_ID)) {
                is EngineResult.Error -> addError(
                    AppLanguage.tr(
                        "读取虚拟应用失败：${result.code}: ${result.message}",
                        "Failed to load virtual apps: ${result.code}: ${result.message}"
                    )
                )
                is EngineResult.Ok -> Unit
            }
        }
    }

    fun launchApp(packageName: String) {
        if (!repository.launch(packageName)) {
            addError(AppLanguage.tr("无法启动 $packageName", "Could not launch $packageName"))
        }
    }

    fun toggleDesktopSystemApp(packageName: String) {
        if (packageName == getApplication<Application>().packageName) return
        val next = desktopSystemPackages.value.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }.toSet()
        desktopSystemPackages.value = next
        DesktopAppsStore.save(getApplication(), next)
    }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { wallpaperStore.save(uri) }
            if (bitmap == null) {
                fail(AppLanguage.tr("无法读取图片作为壁纸", "Could not read the image as wallpaper"))
            } else {
                wallpaper.value = bitmap
                notice.value = AppLanguage.tr("桌面壁纸已更新", "Desktop wallpaper updated")
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { wallpaperStore.clear() }
            wallpaper.value = null
            notice.value = AppLanguage.tr("桌面壁纸已清除", "Desktop wallpaper cleared")
        }
    }

    fun installApk(uri: Uri) {
        installApks(listOf(uri))
    }

    fun installApks(uris: List<Uri>) {
        if (uris.isEmpty() || installing.value) return
        viewModelScope.launch {
            installing.value = true
            val installedBefore = engine.virtualApps.value
                .filter { it.spaceId == SPACE_ID }
                .associateBy { it.packageName }
            notice.value = AppLanguage.tr(
                    "正在导入 ${uris.size} 个 APK…",
                    "Importing ${uris.size} APK(s)…"
                )
            try {
                when (
                    val result = engine.install(SPACE_ID, uris.map { it.toString() })
                ) {
                    is EngineResult.Error -> {
                        fail("${result.code}: ${result.message}")
                    }
                    is EngineResult.Ok -> {
                        val app = result.value
                        val previous = installedBefore[app.packageName]
                        if (previous != null &&
                            previous.installedAt == app.installedAt &&
                            previous.updatedAt == app.updatedAt
                        ) {
                            notice.value = AppLanguage.tr(
                                "${app.label} 已安装，无需重复导入",
                                "${app.label} is already installed; no duplicate import was made"
                            )
                            return@launch
                        }
                        notice.value = AppLanguage.tr(
                            "已安装 ${app.label}（${app.instances.size} 个实例）",
                            "Installed ${app.label} (${app.instances.size} instance(s))"
                        )
                        addAgent(
                            AppLanguage.tr(
                                "Stage B 已安装：${app.label} / ${app.packageName}",
                                "Stage B installed: ${app.label} / ${app.packageName}"
                            )
                        )
                        when (val launched = engine.launch(SPACE_ID, app.packageName)) {
                            is EngineResult.Error -> fail(
                                AppLanguage.tr(
                                "已安装，但启动失败：${launched.code}: ${launched.message}",
                                "Installed, but launch failed: ${launched.code}: ${launched.message}"
                            )
                            )
                            is EngineResult.Ok -> Unit
                        }
                    }
                }
            } finally {
                installing.value = false
            }
        }
    }

    fun importHostApps(packageNames: Set<String>) {
        if (packageNames.isEmpty() || installing.value) return
        val installedPackages = engine.virtualApps.value
            .filter { it.spaceId == SPACE_ID }
            .mapTo(mutableSetOf()) { it.packageName }
        val pendingPackages = packageNames - installedPackages
        if (pendingPackages.isEmpty()) return
        viewModelScope.launch {
            installing.value = true
            val available = apps.value.associateBy { it.packageName }
            val failures = mutableListOf<String>()
            var imported = 0
            try {
                pendingPackages.forEachIndexed { index, packageName ->
                    val app = available[packageName]
                    if (app == null || app.apkPaths.isEmpty()) {
                        failures += packageName
                        return@forEachIndexed
                    }
                    notice.value = AppLanguage.tr(
                        "正在导入 ${app.label}（${index + 1}/${pendingPackages.size}）…",
                        "Importing ${app.label} (${index + 1}/${pendingPackages.size})…"
                    )
                    val apkUris = app.apkPaths.map { Uri.fromFile(File(it)).toString() }
                    when (val result = engine.install(SPACE_ID, apkUris)) {
                        is EngineResult.Ok -> imported++
                        is EngineResult.Error -> failures += "${app.label}: ${result.code}"
                    }
                }
                refreshVirtualApps()
                notice.value = when {
                    failures.isEmpty() -> AppLanguage.tr(
                        "已成功导入 $imported 个应用",
                        "Imported $imported app(s)"
                    )
                    imported == 0 -> AppLanguage.tr(
                        "导入失败：${failures.joinToString("；")}",
                        "Import failed: ${failures.joinToString("; ")}"
                    )
                    else -> AppLanguage.tr(
                        "已导入 $imported 个，失败 ${failures.size} 个：${failures.joinToString("；")}",
                        "Imported $imported; failed ${failures.size}: ${failures.joinToString("; ")}"
                    )
                }
            } finally {
                installing.value = false
            }
        }
    }

    fun launchVirtual(packageName: String, instanceId: String? = null) {
        viewModelScope.launch {
            val running = ContainerProcessPool.findRunningSlot(packageName, instanceId)
            if (running != null) {
                val opened = withContext(Dispatchers.IO) {
                    val mirrored = ContainerUiClient.isMirrorMode(
                        getApplication(),
                        running.slot
                    ) ?: (HeadlessMirrorWindow.activeSlot() == running.slot)
                    if (mirrored) {
                        ContainerProcessPool.closeSlot(getApplication(), running.slot)
                        HeadlessMirrorWindow.hide()
                        false
                    } else {
                        ContainerProcessPool.bringToFront(
                            getApplication(),
                            running.slot,
                            packageName,
                            running.instanceId
                        )
                    }
                }
                if (opened) {
                    notice.value = AppLanguage.tr(
                        "已打开 ${packageName}",
                        "Opened $packageName"
                    )
                    return@launch
                }
            }
            when (val result = engine.launch(SPACE_ID, packageName, instanceId)) {
                is EngineResult.Error -> fail("${result.code}: ${result.message}")
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                    "已启动 ${packageName}（${result.value.instanceId}）",
                    "Launched ${packageName} (${result.value.instanceId})"
                )
                }
            }
        }
    }

    suspend fun loadAppShortcuts(packageName: String): List<AppShortcut> =
        runCatching { engine.shortcuts(SPACE_ID, packageName) }.getOrDefault(emptyList())

    fun launchAppShortcut(packageName: String, shortcut: AppShortcut) {
        viewModelScope.launch {
            when (
                val result = engine.launchShortcut(SPACE_ID, packageName, null, shortcut)
            ) {
                is EngineResult.Error -> fail(
                    AppLanguage.tr(
                        "快捷方式启动失败：${result.code}: ${result.message}",
                        "Failed to launch shortcut: ${result.code}: ${result.message}"
                    )
                )
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                        "已打开 ${shortcut.label}",
                        "Opened ${shortcut.label}"
                    )
                }
            }
        }
    }

    fun multiOpenVirtual(packageName: String) {
        viewModelScope.launch {
            when (val result = engine.createInstance(SPACE_ID, packageName)) {
                is EngineResult.Error -> fail(
                        AppLanguage.tr(
                            "多开失败：${result.code}: ${result.message}",
                            "Failed to create an instance: ${result.code}: ${result.message}"
                        )
                    )
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                        "已创建新实例，可从桌面图标进入：${result.value.id}",
                        "Created an instance. Open it from its desktop icon: ${result.value.id}"
                    )
                }
            }
        }
    }

    fun uninstallVirtual(packageName: String) {
        viewModelScope.launch {
            when (val result = engine.uninstall(SPACE_ID, packageName)) {
                is EngineResult.Error -> fail(
                        AppLanguage.tr(
                            "卸载失败：${result.code}: ${result.message}",
                            "Uninstall failed: ${result.code}: ${result.message}"
                        )
                    )
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                    "已卸载 $packageName，实例数据已清理",
                    "Uninstalled $packageName and cleaned up instance data"
                )
                }
            }
        }
    }

    fun deleteVirtualInstance(packageName: String, instanceId: String) {
        viewModelScope.launch {
            when (val result = engine.deleteInstance(SPACE_ID, packageName, instanceId)) {
                is EngineResult.Error -> fail(
                    AppLanguage.tr(
                        "删除实例失败：${result.code}: ${result.message}",
                        "Failed to delete instance: ${result.code}: ${result.message}"
                    )
                )
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                        "已删除 $packageName 的实例 $instanceId",
                        "Deleted $packageName instance $instanceId"
                    )
                }
            }
        }
    }

    fun clearVirtualInstanceData(packageName: String, instanceId: String) {
        viewModelScope.launch {
            when (val result = engine.clearInstanceData(SPACE_ID, packageName, instanceId)) {
                is EngineResult.Error -> fail(
                    AppLanguage.tr(
                        "清空数据失败：${result.code}: ${result.message}",
                        "Failed to clear data: ${result.code}: ${result.message}"
                    )
                )
                is EngineResult.Ok -> {
                    notice.value = AppLanguage.tr(
                        "已清空 $packageName 实例 $instanceId 的数据",
                        "Cleared data for $packageName instance $instanceId"
                    )
                }
            }
        }
    }

    fun saveLlmConfig(config: LlmConfig) {
        val normalized = config.withNormalizedEndpoint().copy(
            id = config.id.ifBlank { UUID.randomUUID().toString() },
            name = config.name.ifBlank { config.model.ifBlank { config.protocol.label } },
            temperature = config.temperature
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, 2.0)
                ?: 0.7
        )
        val current = llmConfigs.value
        val next = if (current.any { it.id == normalized.id }) {
            current.map { if (it.id == normalized.id) normalized else it }
        } else {
            current + normalized
        }
        llmConfigs.value = next
        activeLlmId.value = normalized.id
        persistLlm(next, normalized.id)
        _llmToast.tryEmit(AppLanguage.tr("配置已保存", "Configuration saved"))
        addAgent(
            AppLanguage.tr(
                "模型配置已保存：${normalized.name}（" +
                    normalized.model.ifBlank { "未填模型" } + "）",
                "Model configuration saved: ${normalized.name} (" +
                    normalized.model.ifBlank { "no model" } + ")"
            )
        )
    }

    fun deleteLlmConfig(id: String) {
        val next = llmConfigs.value.filterNot { it.id == id }
        llmConfigs.value = next
        val activeId = if (activeLlmId.value == id) {
            next.firstOrNull()?.id.orEmpty()
        } else {
            activeLlmId.value
        }
        activeLlmId.value = activeId
        persistLlm(next, activeId)
        _llmToast.tryEmit(
            AppLanguage.tr("已删除模型配置", "Model configuration deleted")
        )
    }

    fun selectLlmConfig(id: String) {
        val config = llmConfigs.value.firstOrNull { it.id == id } ?: return
        activeLlmId.value = id
        persistLlm(llmConfigs.value, id)
        Toast.makeText(
            getApplication(),
            AppLanguage.tr("已切换到 ${config.name}", "Switched to ${config.name}"),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        val active = llmConfig.value
        if (active.id.isBlank()) {
            llmConfig.value = active.copy(reasoningEffort = effort)
            return
        }
        val next = llmConfigs.value.map {
            if (it.id == active.id) it.copy(reasoningEffort = effort) else it
        }
        llmConfigs.value = next
        persistLlm(next, active.id)
    }

    private fun persistLlm(configs: List<LlmConfig>, activeId: String) {
        val state = LlmConfigState(configs, activeId)
        llmStore.save(state)
        llmConfig.value = activeConfig(state)
    }

    private fun activeConfig(state: LlmConfigState): LlmConfig =
        state.configs.firstOrNull { it.id == state.activeId }
            ?: state.configs.firstOrNull()
            ?: LlmConfig()

    fun testLlm(config: LlmConfig) {
        if (llmTesting.value) return
        viewModelScope.launch {
            llmTesting.value = true
            val tested = config.withNormalizedEndpoint()
            runCatching { LlmProbe.test(tested, getApplication()) }
                .onSuccess {
                    _llmToast.tryEmit(
                        AppLanguage.tr(
                            "连接成功：${it.take(160)}",
                            "Connected: ${it.take(160)}"
                        )
                    )
                }
                .onFailure {
                    val detail = it.message ?: it.javaClass.simpleName
                    _llmToast.tryEmit(
                        AppLanguage.tr(
                            "连接失败：${detail.take(160)}",
                            "Connection failed: ${detail.take(160)}"
                        )
                    )
                }
            llmTesting.value = false
        }
    }

    fun runAgent(command: String) {
        AssistantConversation.submit(getApplication(), command)
    }

    fun stopAgent() {
        AssistantConversation.cancel()
    }

    fun clearMessages() {
        AssistantConversation.clear()
    }

    fun startMcp() {
        val prefs = getApplication<Application>()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_TOKEN, null).isNullOrBlank()) {
            prefs.edit()
                .putString(KEY_TOKEN, UUID.randomUUID().toString().replace("-", ""))
                .apply()
        }
        McpForegroundService.start(getApplication())
    }

    fun stopMcp() {
        McpForegroundService.stop(getApplication())
    }

    fun setMcpLan(enabled: Boolean) {
        McpForegroundService.setLanEnabled(getApplication(), enabled)
    }

    fun mcpToken(): String =
        getApplication<Application>()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            .orEmpty()

    fun openOverlaySettings() {
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
    }

    private fun updateSpaces(list: List<VirtualApp>) {
        val count = list
            .filter { it.spaceId == SPACE_ID }
            .sumOf { it.instances.size }
        spaces.value = spaces.value.map {
            if (it.id == SPACE_ID) {
                it.copy(
                    status = AppLanguage.tr(
                    "Stage C-D：进程隔离 / Job / 通知 / VPMS",
                    "Stage C-D: process isolation / jobs / notifications / VPMS"
                ),
                    appCount = count
                )
            } else {
                it
            }
        }
    }

    private fun fail(text: String) {
        notice.value = text
        addError(text)
    }

    private fun addError(text: String) {
        AssistantConversation.append(AgentMessage(AgentMessage.Role.ERROR, text))
    }

    private fun addAgent(text: String) {
        AssistantConversation.append(AgentMessage(AgentMessage.Role.AGENT, text))
    }

    companion object {
        private const val SPACE_ID = "virtual-1"
        private const val PREFS = "virtual"
        private const val KEY_TOKEN = "mcp_token"
    }
}
