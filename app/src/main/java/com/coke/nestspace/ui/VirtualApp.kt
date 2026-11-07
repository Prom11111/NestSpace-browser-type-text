package com.coke.nestspace.ui

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coke.nestspace.R
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppInfo
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.McpStatus
import com.coke.nestspace.data.ModelOnboardingStore
import com.coke.nestspace.data.VirtualApp
import androidx.core.view.WindowCompat
import com.coke.nestspace.vm.VirtualViewModel
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.ui.agent.AgentScreen
import com.coke.nestspace.ui.agent.RunHistoryScreen
import com.coke.nestspace.ui.agent.UserGateHost
import com.coke.nestspace.ui.apps.AppManagerScreen
import com.coke.nestspace.browser.BrowserActivity
import com.coke.nestspace.browser.BrowserProxyDialog
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.ui.desktop.DesktopScreen
import com.coke.nestspace.ui.running.RunningAppsScreen
import com.coke.nestspace.ui.settings.AboutDetailScreen
import com.coke.nestspace.ui.settings.AppearanceDetailScreen
import com.coke.nestspace.ui.settings.ChildRunsScreen
import com.coke.nestspace.ui.settings.FloatingDotDetailScreen
import com.coke.nestspace.ui.settings.ModelDetailScreen
import com.coke.nestspace.ui.settings.ServicesDetailScreen
import com.coke.nestspace.ui.settings.LanguageDetailScreen
import com.coke.nestspace.ui.settings.SlotDetailScreen
import com.coke.nestspace.ui.settings.LlmConfigEditor
import com.coke.nestspace.ui.settings.SystemPromptScreen
import com.coke.nestspace.ui.settings.ContainerSelfCheckScreen
import com.coke.nestspace.ui.settings.McpClientSettingsScreen
import com.coke.nestspace.ui.settings.MemorySkillsScreen
import com.coke.nestspace.ui.settings.ModelOnboardingScreen
import com.coke.nestspace.ui.settings.ScheduledTasksScreen
import com.coke.nestspace.ui.settings.EvaluationReplayScreen
import com.coke.nestspace.ui.settings.TokenUsageScreen
import com.coke.nestspace.ui.settings.ToolPermissionScreen
import com.coke.nestspace.ui.settings.HostControlScreen
import com.coke.nestspace.ui.settings.LocationSimulationScreen
import com.coke.nestspace.ui.settings.SettingsHomeScreen
import com.coke.nestspace.ui.settings.SettingsCategory
import com.coke.nestspace.location.MockLocationSettings

private enum class SpacesPage {
    ROOT,
    SETTINGS_APPS,
    SETTINGS_ASSISTANT,
    SETTINGS_SYSTEM,
    SETTINGS_CONTAINER_ADVANCED,
    SETTINGS_ASSISTANT_ADVANCED,
    APPS,
    SERVICES,
    APPEARANCE,
    FLOATING_DOT,
    MODEL,
    MODEL_EDIT,
    SYSTEM_PROMPT,
    MCP_CLIENTS,
    SLOTS,
    HOST_CONTROL,
    LOCATION,
    SELF_CHECK,
    MEMORY,
    SCHEDULE,
    EVAL,
    RUN_HISTORY,
    CHILD_RUNS,
    TOKEN_USAGE,
    PERMISSIONS,
    LANGUAGE,
    ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualApp(viewModel: VirtualViewModel = viewModel()) {
    val apps by viewModel.apps.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val virtualApps by viewModel.virtualApps.collectAsState()
    val selectedSystemApps by viewModel.desktopSystemPackages.collectAsState()
    val wallpaper by viewModel.wallpaper.collectAsState()
    val installing by viewModel.installing.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val llmConfig by viewModel.llmConfig.collectAsState()
    val llmConfigs by viewModel.llmConfigs.collectAsState()
    val activeLlmId by viewModel.activeLlmId.collectAsState()
    val llmTesting by viewModel.llmTesting.collectAsState()
    val agentBusy by viewModel.agentBusy.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var spacesPage by remember { mutableStateOf(SpacesPage.ROOT) }
    var lastHomeBackAt by remember { mutableStateOf(0L) }
    var mirrorBackEnabled by remember {
        mutableStateOf(HeadlessMirrorWindow.isBackEnabled())
    }
    var childRunsFromAssistant by remember { mutableStateOf(false) }
    var historyFromAssistant by remember { mutableStateOf(false) }
    var modelSettingsFromAssistant by remember { mutableStateOf(false) }
    var assistantSettingsFromAssistant by remember { mutableStateOf(false) }
    var permissionsFromAssistant by remember { mutableStateOf(false) }
    var editingLlm by remember { mutableStateOf<LlmConfig?>(null) }
    var showBrowserProxyDialog by remember { mutableStateOf(false) }
    val apkMimeTypes = arrayOf(
        "application/vnd.android.package-archive",
        "application/octet-stream"
    )
    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.installApks(uris)
    }
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::setWallpaper)
    }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether or not the notification is allowed, the MCP service can
            // still run; the permission only controls its notification.
            viewModel.startMcp()
        }
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.llmToast.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val uiScope = rememberCoroutineScope()
    val modelOnboardingStore = remember(context) { ModelOnboardingStore(context) }
    var modelOnboardingCompleted by remember(context) {
        mutableStateOf(modelOnboardingStore.isCompleted() || llmConfigs.isNotEmpty())
    }
    val showModelOnboarding = !modelOnboardingCompleted && llmConfigs.isEmpty()
    val completeModelOnboarding: () -> Unit = {
        modelOnboardingStore.markCompleted()
        modelOnboardingCompleted = true
    }
    var slotMode by remember {
        mutableIntStateOf(ContainerProcessPool.slotLimitMode(context))
    }
    var slotLimit by remember {
        mutableIntStateOf(ContainerProcessPool.slotLimit(context))
    }
    var hostControlEnabled by remember {
        mutableStateOf(com.coke.nestspace.host.HostControlSettings.isEnabled(context))
    }
    var locationSimulation by remember {
        mutableStateOf(MockLocationSettings.load(context))
    }
    var permissionLevel by remember {
        mutableStateOf(ToolPermissionSettings.load(context))
    }
    var customPermissions by remember {
        mutableStateOf(ToolPermissionSettings.customCategories(context))
    }
    val settingsRevision by AppSettingsEvents.revision.collectAsState()
    LaunchedEffect(settingsRevision) {
        slotMode = ContainerProcessPool.slotLimitMode(context)
        slotLimit = ContainerProcessPool.slotLimit(context)
        hostControlEnabled = com.coke.nestspace.host.HostControlSettings.isEnabled(context)
        locationSimulation = MockLocationSettings.load(context)
        permissionLevel = ToolPermissionSettings.load(context)
        customPermissions = ToolPermissionSettings.customCategories(context)
    }
    val startMcp: () -> Unit = {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startMcp()
        }
    }

    LaunchedEffect(Unit) {
        DesktopNavigation.requests.collect { tab = 0 }
    }
    LaunchedEffect(Unit) {
        val openModelSettings = {
            completeModelOnboarding()
            modelSettingsFromAssistant = true
            spacesPage = SpacesPage.MODEL
            tab = 1
        }
        if (ModelSettingsNavigation.consumeRequest()) {
            openModelSettings()
        }
        ModelSettingsNavigation.requests.collect {
            if (ModelSettingsNavigation.consumeRequest()) {
                openModelSettings()
            }
        }
    }
    LaunchedEffect(AppLanguage.revision) {
        AppLanguage.revision
        AssistantConversation.refreshWelcome()
    }
    LaunchedEffect(messages, agentBusy) {
        AssistantBridge.publish(agentBusy, messages)
    }
    LaunchedEffect(Unit) {
        AssistantBridge.sendRequests.collect { viewModel.runAgent(it) }
    }
    LaunchedEffect(llmConfigs.isNotEmpty()) {
        if (llmConfigs.isNotEmpty() && !modelOnboardingCompleted) {
            completeModelOnboarding()
        }
    }
    LaunchedEffect(Unit) {
        AssistantBridge.externalRequest.collect { text ->
            if (!text.isNullOrBlank()) {
                tab = 2
                AssistantBridge.consumeExternalRequest()
                viewModel.runAgent(text)
            }
        }
    }
    LaunchedEffect(tab) {
        if (tab == 0) lastHomeBackAt = 0L
        if (tab != 1) {
            spacesPage = SpacesPage.ROOT
            editingLlm = null
            childRunsFromAssistant = false
            historyFromAssistant = false
            modelSettingsFromAssistant = false
            assistantSettingsFromAssistant = false
            permissionsFromAssistant = false
        }
    }
    // Space management and running-apps management belong to the container
    // surface, so keep the container color there instead of the host color.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tab) {
        val containerTab = tab == 1 || tab == 3
        fun apply(visible: Boolean) {
            FloatingDotOverlay.setContainerSurfaceVisible(
                context,
                "container-ui",
                visible && containerTab
            )
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply(true)
                Lifecycle.Event.ON_PAUSE -> apply(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        apply(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setContainerSurfaceVisible(context, "container-ui", false)
        }
    }

    val rootView = LocalView.current
    LaunchedEffect(tab, wallpaper) {
        val activity = rootView.context.findActivity() ?: return@LaunchedEffect
        WindowCompat.getInsetsController(activity.window, rootView)
            .isAppearanceLightStatusBars = tab != 0 || wallpaper == null
    }

    val inSpacesDetail = tab == 1 && spacesPage != SpacesPage.ROOT
    // Detail pages contain switches, text fields and bottom actions along the
    // right edge; the floating dot otherwise sits on top of them.
    DisposableEffect(inSpacesDetail) {
        FloatingDotOverlay.setOverlayHidden(context, "settings-detail", inSpacesDetail)
        onDispose {
            FloatingDotOverlay.setOverlayHidden(context, "settings-detail", false)
        }
    }
    DisposableEffect(showModelOnboarding) {
        FloatingDotOverlay.setOverlayHidden(context, "model-onboarding", showModelOnboarding)
        onDispose {
            FloatingDotOverlay.setOverlayHidden(context, "model-onboarding", false)
        }
    }
    val navigateBack: () -> Unit = {
        when {
            tab != 1 -> tab = 0
            spacesPage == SpacesPage.CHILD_RUNS && childRunsFromAssistant -> {
                childRunsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.RUN_HISTORY && historyFromAssistant -> {
                historyFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.MODEL_EDIT -> {
                editingLlm = null
                spacesPage = SpacesPage.MODEL
            }
            spacesPage == SpacesPage.MODEL && modelSettingsFromAssistant -> {
                modelSettingsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.PERMISSIONS && permissionsFromAssistant -> {
                permissionsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage == SpacesPage.SETTINGS_ASSISTANT &&
                assistantSettingsFromAssistant -> {
                assistantSettingsFromAssistant = false
                spacesPage = SpacesPage.ROOT
                tab = 2
            }
            spacesPage in setOf(
                SpacesPage.HOST_CONTROL,
                SpacesPage.SELF_CHECK
            ) -> spacesPage = SpacesPage.SETTINGS_CONTAINER_ADVANCED
            spacesPage in setOf(
                SpacesPage.MCP_CLIENTS,
                SpacesPage.MEMORY,
                SpacesPage.SCHEDULE,
                SpacesPage.EVAL,
                SpacesPage.CHILD_RUNS,
                SpacesPage.TOKEN_USAGE,
                SpacesPage.SERVICES
            ) -> spacesPage = SpacesPage.SETTINGS_ASSISTANT_ADVANCED
            spacesPage in setOf(
                SpacesPage.APPS,
                SpacesPage.SLOTS,
                SpacesPage.LOCATION
            ) -> spacesPage = SpacesPage.SETTINGS_APPS
            spacesPage in setOf(
                SpacesPage.MODEL,
                SpacesPage.SYSTEM_PROMPT,
                SpacesPage.PERMISSIONS,
                SpacesPage.FLOATING_DOT
            ) -> spacesPage = SpacesPage.SETTINGS_ASSISTANT
            spacesPage in setOf(
                SpacesPage.APPEARANCE,
                SpacesPage.LANGUAGE
            ) -> spacesPage = SpacesPage.SETTINGS_SYSTEM
            spacesPage == SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                spacesPage = SpacesPage.SETTINGS_APPS
            spacesPage == SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                spacesPage = SpacesPage.SETTINGS_ASSISTANT
            spacesPage != SpacesPage.ROOT -> spacesPage = SpacesPage.ROOT
            else -> tab = 0
        }
    }
    BackHandler(enabled = tab != 0) { navigateBack() }
    BackHandler(enabled = showModelOnboarding) {
        completeModelOnboarding()
    }
    DisposableEffect(Unit) {
        val listener: (Boolean) -> Unit = { mirrorBackEnabled = it }
        HeadlessMirrorWindow.addBackEnabledListener(listener)
        onDispose { HeadlessMirrorWindow.removeBackEnabledListener(listener) }
    }
    BackHandler(
        enabled = tab == 0 && !showModelOnboarding && !mirrorBackEnabled
    ) {
        val now = SystemClock.elapsedRealtime()
        if (lastHomeBackAt > 0L && now - lastHomeBackAt <= 2_000L) {
            context.findActivity()?.finish()
        } else {
            lastHomeBackAt = now
            Toast.makeText(
                context,
                AppLanguage.tr("再按一次返回退出", "Press back again to exit"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val appBarTitle = when {
        tab == 1 -> when (spacesPage) {
            SpacesPage.ROOT -> AppLanguage.tr("空间设置", "Space settings")
            SpacesPage.SETTINGS_APPS -> AppLanguage.tr("应用与容器", "Apps & containers")
            SpacesPage.SETTINGS_ASSISTANT -> AppLanguage.tr("AI 助手设置", "AI assistant settings")
            SpacesPage.SETTINGS_SYSTEM -> AppLanguage.tr("系统与网络", "System & network")
            SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                AppLanguage.tr("容器高级", "Container advanced")
            SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                AppLanguage.tr("高级助手工具", "Advanced assistant tools")
            SpacesPage.APPS -> AppLanguage.tr("应用管理", "App management")
            SpacesPage.SERVICES -> AppLanguage.tr("MCP 控制服务", "MCP control service")
            SpacesPage.APPEARANCE -> AppLanguage.tr("外观", "Appearance")
            SpacesPage.FLOATING_DOT -> AppLanguage.tr("悬浮球", "Floating dot")
            SpacesPage.MODEL -> AppLanguage.tr("模型设置", "Model settings")
            SpacesPage.MODEL_EDIT ->
                if (editingLlm?.id.isNullOrBlank()) {
                    AppLanguage.tr("添加模型", "Add model")
                } else {
                    AppLanguage.tr("修改模型", "Edit model")
                }
            SpacesPage.SYSTEM_PROMPT -> AppLanguage.tr("系统提示词", "System prompt")
            SpacesPage.MCP_CLIENTS -> AppLanguage.tr("MCP 工具", "MCP tools")
            SpacesPage.SLOTS -> AppLanguage.tr("容器槽位", "Container slots")
            SpacesPage.HOST_CONTROL -> AppLanguage.tr("宿主控制（Root）", "Host control (root)")
            SpacesPage.LOCATION -> AppLanguage.tr("坐标模拟", "Location simulation")
            SpacesPage.SELF_CHECK -> AppLanguage.tr("容器自检", "Container self-check")
            SpacesPage.MEMORY -> AppLanguage.tr("记忆与技能", "Memory & skills")
            SpacesPage.SCHEDULE -> AppLanguage.tr("定时任务", "Scheduled tasks")
            SpacesPage.EVAL -> AppLanguage.tr("评测与回放", "Evaluation & replay")
            SpacesPage.RUN_HISTORY -> AppLanguage.tr("运行历史", "Run history")
            SpacesPage.CHILD_RUNS -> AppLanguage.tr("子任务记录", "Child runs")
            SpacesPage.TOKEN_USAGE -> AppLanguage.tr("Token 统计", "Token usage")
            SpacesPage.PERMISSIONS -> AppLanguage.tr("权限级别", "Permissions")
            SpacesPage.LANGUAGE -> AppLanguage.tr("语言", "Language")
            SpacesPage.ABOUT -> AppLanguage.tr("关于", "About")
        }
        tab == 2 -> AppLanguage.tr("AI 助手", "AI assistant")
        tab == 3 -> AppLanguage.tr("运行管理", "Running apps")
        else -> "NestSpace"
    }

    Scaffold(
            topBar = {
                if (tab != 0) {
                    TopAppBar(
                        title = { Text(appBarTitle) },
                        navigationIcon = {
                            IconButton(onClick = navigateBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = if (inSpacesDetail) {
                                        AppLanguage.tr("返回空间设置", "Back to space settings")
                                    } else {
                                        AppLanguage.tr("返回桌面", "Back to desktop")
                                    }
                                )
                            }
                        }
                    )
                }
            }
        ) { padding ->
        val contentPadding = if (tab == 0) {
            PaddingValues(bottom = padding.calculateBottomPadding())
        } else {
            padding
        }
        Box(Modifier.padding(contentPadding)) {
            when (tab) {
                0 -> DesktopScreen(
                    virtualApps = virtualApps.filter { it.spaceId == "virtual-1" },
                    systemApps = apps.filter { it.packageName in selectedSystemApps },
                    wallpaper = wallpaper,
                    onLaunchVirtual = { viewModel.launchVirtual(it) },
                    onLaunchSystem = viewModel::launchApp,
                    onOpenBrowser = {
                        context.startActivity(Intent(context, BrowserActivity::class.java))
                    },
                    onOpenAgent = { tab = 2 },
                    onOpenSpaces = { spacesPage = SpacesPage.ROOT; tab = 1 },
                    onOpenModel = {
                        modelSettingsFromAssistant = false
                        spacesPage = SpacesPage.MODEL
                        tab = 1
                    },
                    onOpenPermissions = {
                        permissionsFromAssistant = false
                        spacesPage = SpacesPage.PERMISSIONS
                        tab = 1
                    },
                    onOpenAppManager = { spacesPage = SpacesPage.APPS; tab = 1 },
                    onLaunchShortcut = viewModel::launchAppShortcut,
                    loadShortcuts = viewModel::loadAppShortcuts,
                    onOpenRunning = { tab = 3 },
                    onUninstallVirtual = viewModel::uninstallVirtual,
                    onRemoveSystemApp = viewModel::toggleDesktopSystemApp
                )

                1 -> when (spacesPage) {
                    SpacesPage.ROOT,
                    SpacesPage.SETTINGS_APPS,
                    SpacesPage.SETTINGS_ASSISTANT,
                    SpacesPage.SETTINGS_SYSTEM,
                    SpacesPage.SETTINGS_CONTAINER_ADVANCED,
                    SpacesPage.SETTINGS_ASSISTANT_ADVANCED -> {
                        SettingsHomeScreen(
                            category = when (spacesPage) {
                                SpacesPage.SETTINGS_APPS -> SettingsCategory.APPS
                                SpacesPage.SETTINGS_ASSISTANT -> SettingsCategory.ASSISTANT
                                SpacesPage.SETTINGS_SYSTEM -> SettingsCategory.SYSTEM
                                SpacesPage.SETTINGS_CONTAINER_ADVANCED ->
                                    SettingsCategory.CONTAINER_ADVANCED
                                SpacesPage.SETTINGS_ASSISTANT_ADVANCED ->
                                    SettingsCategory.ASSISTANT_ADVANCED
                                else -> null
                            },
                            appCount = virtualApps.count { it.spaceId == "virtual-1" },
                            mcpStatus = mcpStatus,
                            hasWallpaper = wallpaper != null,
                            llmConfig = llmConfig,
                            languageMode = AppLanguage.mode(),
                            slotLimitMode = slotMode,
                            slotLimit = slotLimit,
                            hostControlEnabled = hostControlEnabled,
                            locationSimulation = locationSimulation,
                            onOpenApps = { spacesPage = SpacesPage.APPS },
                            onOpenHostControl = { spacesPage = SpacesPage.HOST_CONTROL },
                            onOpenLocationSimulation = { spacesPage = SpacesPage.LOCATION },
                            onOpenBrowserProxy = { showBrowserProxyDialog = true },
                            onOpenServices = { spacesPage = SpacesPage.SERVICES },
                            onOpenAppearance = { spacesPage = SpacesPage.APPEARANCE },
                            onOpenFloatingDot = { spacesPage = SpacesPage.FLOATING_DOT },
                            onOpenModel = { spacesPage = SpacesPage.MODEL },
                            onOpenSystemPrompt = { spacesPage = SpacesPage.SYSTEM_PROMPT },
                            onOpenMcpClients = { spacesPage = SpacesPage.MCP_CLIENTS },
                            onOpenMemory = { spacesPage = SpacesPage.MEMORY },
                            onOpenSchedule = { spacesPage = SpacesPage.SCHEDULE },
                            onOpenEvaluation = { spacesPage = SpacesPage.EVAL },
                            onOpenChildRuns = {
                                childRunsFromAssistant = false
                                spacesPage = SpacesPage.CHILD_RUNS
                            },
                            onOpenTokenUsage = { spacesPage = SpacesPage.TOKEN_USAGE },
                            permissionLevel = permissionLevel,
                            onOpenPermissions = { spacesPage = SpacesPage.PERMISSIONS },
                            onOpenSlots = { spacesPage = SpacesPage.SLOTS },
                            onOpenSelfCheck = { spacesPage = SpacesPage.SELF_CHECK },
                            onOpenLanguage = { spacesPage = SpacesPage.LANGUAGE },
                            onOpenAbout = { spacesPage = SpacesPage.ABOUT },
                            onOpenCategory = { category ->
                                spacesPage = when (category) {
                                    SettingsCategory.APPS -> SpacesPage.SETTINGS_APPS
                                    SettingsCategory.ASSISTANT -> SpacesPage.SETTINGS_ASSISTANT
                                    SettingsCategory.SYSTEM -> SpacesPage.SETTINGS_SYSTEM
                                    SettingsCategory.CONTAINER_ADVANCED ->
                                        SpacesPage.SETTINGS_CONTAINER_ADVANCED
                                    SettingsCategory.ASSISTANT_ADVANCED ->
                                        SpacesPage.SETTINGS_ASSISTANT_ADVANCED
                                }
                            }
                        )
                        if (showBrowserProxyDialog) {
                            BrowserProxyDialog(
                                onDismiss = { showBrowserProxyDialog = false }
                            )
                        }
                    }

                    SpacesPage.APPS -> AppManagerScreen(
                        virtualApps = virtualApps.filter { it.spaceId == "virtual-1" },
                        systemApps = apps,
                        installing = installing,
                        notice = notice,
                        onInstallApk = { apkPicker.launch(apkMimeTypes) },
                        onLaunchVirtual = { packageName, instanceId ->
                            viewModel.launchVirtual(packageName, instanceId)
                        },
                        onMultiOpen = viewModel::multiOpenVirtual,
                        onUninstall = viewModel::uninstallVirtual,
                        onImportApps = viewModel::importHostApps,
                        onRefreshApps = viewModel::refreshApps
                    )

                    SpacesPage.SERVICES -> ServicesDetailScreen(
                        mcpStatus = mcpStatus,
                        token = mcpStatus.token.ifBlank { viewModel.mcpToken() },
                        onStartMcp = startMcp,
                        onStopMcp = viewModel::stopMcp,
                        onToggleLan = viewModel::setMcpLan,
                        onOpenOverlaySettings = viewModel::openOverlaySettings
                    )

                    SpacesPage.APPEARANCE -> AppearanceDetailScreen(
                        hasWallpaper = wallpaper != null,
                        onPickWallpaper = { wallpaperPicker.launch(arrayOf("image/*")) },
                        onClearWallpaper = viewModel::clearWallpaper
                    )

                    SpacesPage.FLOATING_DOT -> FloatingDotDetailScreen()

                    SpacesPage.MODEL -> ModelDetailScreen(
                        configs = llmConfigs,
                        activeId = activeLlmId,
                        onAddLlm = {
                            editingLlm = LlmConfig.defaultsFor(LlmProtocol.ANTHROPIC)
                            spacesPage = SpacesPage.MODEL_EDIT
                        },
                        onEditLlm = {
                            editingLlm = it
                            spacesPage = SpacesPage.MODEL_EDIT
                        },
                        onDeleteLlm = viewModel::deleteLlmConfig,
                        onSelectLlm = viewModel::selectLlmConfig
                    )

                    SpacesPage.MODEL_EDIT -> {
                        val config = editingLlm
                        if (config != null) {
                            LlmConfigEditor(
                                config = config,
                                testing = llmTesting,
                                onSave = {
                                    viewModel.saveLlmConfig(it)
                                    editingLlm = null
                                    spacesPage = SpacesPage.MODEL
                                },
                                onTest = viewModel::testLlm
                            )
                        }
                    }

                    SpacesPage.SYSTEM_PROMPT -> SystemPromptScreen()

                    SpacesPage.MCP_CLIENTS -> McpClientSettingsScreen()

                    SpacesPage.HOST_CONTROL -> HostControlScreen()
                    SpacesPage.LOCATION -> LocationSimulationScreen()
                    SpacesPage.SLOTS -> SlotDetailScreen(
                        mode = slotMode,
                        limit = slotLimit,
                        onSelect = { value ->
                            // Lowering the limit kills slots synchronously;
                            // keep the wait off the main thread.
                            uiScope.launch {
                                withContext(Dispatchers.IO) {
                                    ContainerProcessPool.applySlotLimit(context, value)
                                }
                                slotMode = ContainerProcessPool.slotLimitMode(context)
                                slotLimit = ContainerProcessPool.slotLimit(context)
                            }
                        }
                    )

                    SpacesPage.SELF_CHECK -> ContainerSelfCheckScreen()

                    SpacesPage.MEMORY -> MemorySkillsScreen()

                    SpacesPage.SCHEDULE -> ScheduledTasksScreen()

                    SpacesPage.EVAL -> EvaluationReplayScreen()

                    SpacesPage.RUN_HISTORY -> RunHistoryScreen(
                        context = context,
                        busy = agentBusy,
                        onRestore = { run, events ->
                            if (AssistantConversation.restoreRun(run, events)) {
                                historyFromAssistant = false
                                spacesPage = SpacesPage.ROOT
                                tab = 2
                            }
                        }
                    )

                    SpacesPage.CHILD_RUNS -> ChildRunsScreen()

                    SpacesPage.TOKEN_USAGE -> TokenUsageScreen()

                    SpacesPage.PERMISSIONS -> ToolPermissionScreen(
                        level = permissionLevel,
                        customCategories = customPermissions,
                        onSelectLevel = { level ->
                            permissionLevel = level
                            ToolPermissionSettings.save(context, level)
                            AppSettingsEvents.notifyChanged()
                        },
                        onToggleCategory = { category, enabled ->
                            val next = if (enabled) {
                                customPermissions + category
                            } else {
                                customPermissions - category
                            }
                            customPermissions = next
                            permissionLevel = ToolPermissionLevel.CUSTOM
                            ToolPermissionSettings.save(context, ToolPermissionLevel.CUSTOM)
                            ToolPermissionSettings.saveCustomCategories(context, next)
                            AppSettingsEvents.notifyChanged()
                        }
                    )

                    SpacesPage.LANGUAGE -> LanguageDetailScreen(
                        mode = AppLanguage.mode(),
                        onSelect = { AppLanguage.setMode(context, it) }
                    )

                    SpacesPage.ABOUT -> AboutDetailScreen()
                }

                3 -> RunningAppsScreen(
                    apps = virtualApps
                )

                2 -> AgentScreen(
                    messages = messages,
                    llmConfig = llmConfig,
                    llmConfigs = llmConfigs,
                    activeLlmId = activeLlmId,
                    busy = agentBusy,
                    onSend = viewModel::runAgent,
                    onStop = viewModel::stopAgent,
                    onClear = viewModel::clearMessages,
                    onOpenHistory = {
                        historyFromAssistant = true
                        spacesPage = SpacesPage.RUN_HISTORY
                        tab = 1
                    },
                    onOpenSettings = {
                        assistantSettingsFromAssistant = true
                        spacesPage = SpacesPage.SETTINGS_ASSISTANT
                        tab = 1
                    },
                    onOpenModelSettings = {
                        modelSettingsFromAssistant = true
                        spacesPage = SpacesPage.MODEL
                        tab = 1
                    },
                    permissionLevel = permissionLevel,
                    onPermissionLevelChange = { level ->
                        permissionLevel = level
                        ToolPermissionSettings.save(context, level)
                        AppSettingsEvents.notifyChanged()
                    },
                    onOpenPermissionSettings = {
                        permissionsFromAssistant = true
                        spacesPage = SpacesPage.PERMISSIONS
                        tab = 1
                    },
                    onOpenChildRuns = {
                        childRunsFromAssistant = true
                        spacesPage = SpacesPage.CHILD_RUNS
                        tab = 1
                    },
                    onSelectModel = viewModel::selectLlmConfig,
                    onReasoningEffortChange = viewModel::setReasoningEffort
                )
            }
        }
    }

    if (showModelOnboarding) {
        ModelOnboardingScreen(
            onConfigure = {
                completeModelOnboarding()
                modelSettingsFromAssistant = false
                editingLlm = LlmConfig.defaultsFor(LlmProtocol.ANTHROPIC)
                spacesPage = SpacesPage.MODEL_EDIT
                tab = 1
            },
            onSkip = completeModelOnboarding
        )
    }

    UserGateHost()
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

