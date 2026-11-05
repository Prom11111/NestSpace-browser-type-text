package com.coke.nestspace.ui.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.widget.FrameLayout
import com.coke.nestspace.R
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.agent.RunEvent
import com.coke.nestspace.assistant.FloatingDotSettings
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.assistant.LocalSpeechModelStore
import com.coke.nestspace.agent.RunRecordStore
import com.coke.nestspace.agent.RunSummary
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Conversation surface for the on-device agent.
 *
 * The visual language follows current desktop agent clients: a quiet
 * assistant identity header, an editorial transcript, collapsible tool
 * activity, and a composer that owns model + reasoning controls.
 */
@Composable
internal fun AgentScreen(
    messages: List<AgentMessage>,
    llmConfig: LlmConfig,
    llmConfigs: List<LlmConfig>,
    activeLlmId: String,
    busy: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelSettings: () -> Unit,
    permissionLevel: ToolPermissionLevel,
    onPermissionLevelChange: (ToolPermissionLevel) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenChildRuns: () -> Unit,
    onSelectModel: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit
) {
    var input by remember { mutableStateOf(AssistantBridge.currentInput.value) }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val injectedText by AssistantBridge.pendingText.collectAsState()
    val clearVersion by AssistantBridge.clearInput.collectAsState()
    val activeChildCount by ChildRunRegistry.activeCount.collectAsState()
    val queuedSteering by AssistantConversation.queuedSteering.collectAsState()
    val liveFrame by LivePreview.frame.collectAsState()
    val lastRun by AssistantConversation.lastRun.collectAsState()
    val streamingText by AssistantConversation.streamingText.collectAsState()
    var showLivePreview by remember { mutableStateOf(false) }

    LaunchedEffect(injectedText) {
        val text = injectedText ?: return@LaunchedEffect
        input = text
        AssistantBridge.updateInput(text)
        AssistantBridge.consumePending()
    }
    LaunchedEffect(clearVersion) {
        if (clearVersion > 0) {
            input = ""
            AssistantBridge.updateInput("")
        }
    }
    DisposableEffect(Unit) {
        AssistantBridge.setScreenAttached(true)
        onDispose { AssistantBridge.setScreenAttached(false) }
    }

    val context = LocalContext.current
    val memoryScope = rememberCoroutineScope()
    fun resolveMemory(id: String, approve: Boolean) {
        memoryScope.launch {
            val store = MemoryStore(context)
            val ok = withContext(Dispatchers.IO) {
                if (approve) store.approve(id) else store.forget(id)
            }
            // Ignoring an already-deleted entry is still a resolved card.
            if (ok || !approve) {
                AssistantConversation.resolveMemoryCard(id, approve)
                AppSettingsEvents.notifyChanged()
            }
        }
    }

    fun resolveSkill(id: String, enabled: Boolean) {
        memoryScope.launch {
            val store = SkillStore(context)
            val ok = withContext(Dispatchers.IO) {
                if (enabled) store.setEnabled(id, true) else store.delete(id)
            }
            if (ok || !enabled) {
                AssistantConversation.resolveSkillCard(id, enabled)
                AppSettingsEvents.notifyChanged()
            }
        }
    }

    LaunchedEffect(liveFrame?.slot, liveFrame?.kind, busy) {
        val frame = liveFrame ?: return@LaunchedEffect
        if (!busy || frame.kind != LivePreview.KIND_CONTAINER) return@LaunchedEffect
        val slot = frame.slot
        while (isActive) {
            delay(1_500)
            if (!ContainerProcessPool.isProcessAlive(context, slot)) break
            val base64 = withContext(Dispatchers.IO) {
                ContainerUiClient.screenshotJpegBase64(context, slot)
            } ?: break
            LivePreview.publish(
                base64 = base64,
                mimeType = "image/jpeg",
                slot = slot,
                label = frame.label
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME ->
                    FloatingDotOverlay.setAssistantPageVisible(context, true)
                Lifecycle.Event.ON_PAUSE ->
                    FloatingDotOverlay.setAssistantPageVisible(context, false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            FloatingDotOverlay.setAssistantPageVisible(context, true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setAssistantPageVisible(context, false)
        }
    }

    val displayMessages = remember(messages) {
        messages.filterNot { it.welcome }
    }
    val showWelcome = displayMessages.isEmpty() && !busy

    LaunchedEffect(displayMessages.size, busy, streamingText.length) {
        if (!showWelcome) {
            val count = displayMessages.size + if (busy) 1 else 0
            if (count > 0) listState.animateScrollToItem(count - 1)
        }
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty()) return
        onSend(text)
        input = ""
        AssistantBridge.updateInput("")
    }

    val clearConversation = {
        input = ""
        AssistantBridge.updateInput("")
        onClear()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.045f),
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        AgentHeader(
            llmConfig = llmConfig,
            busy = busy,
            onClear = clearConversation,
            onOpenHistory = { showHistory = true },
            onOpenSettings = onOpenSettings
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (showWelcome) {
                WelcomeState(
                    configured = llmConfig.isConfigured,
                    onConfigure = onOpenModelSettings,
                    modifier = Modifier.fillMaxSize(),
                    onSuggestion = {
                        input = it
                        AssistantBridge.updateInput(it)
                        focusRequester.requestFocus()
                    }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(displayMessages) { message ->
                        val pendingSkillId = message.pendingSkillId
                        val pendingId = message.pendingMemoryId
                        if (pendingSkillId != null) {
                            PendingSkillCard(
                                text = message.text,
                                decision = message.skillDecision,
                                onEnable = { resolveSkill(pendingSkillId, enabled = true) },
                                onIgnore = { resolveSkill(pendingSkillId, enabled = false) }
                            )
                        } else if (pendingId != null) {
                            PendingMemoryCard(
                                text = message.text,
                                decision = message.memoryDecision,
                                onConfirm = { resolveMemory(pendingId, approve = true) },
                                onIgnore = { resolveMemory(pendingId, approve = false) }
                            )
                        } else {
                            when (message.role) {
                                AgentMessage.Role.USER -> UserMessage(message.text)
                                AgentMessage.Role.AGENT -> AssistantMessage(message.text)
                                AgentMessage.Role.TOOL -> ToolActivityCard(message.text)
                                AgentMessage.Role.ERROR -> ErrorMessage(message.text)
                            }
                        }
                    }
                    if (streamingText.isNotBlank()) {
                        item {
                            AssistantMessage(streamingText.trimEnd() + " ▍")
                        }
                    } else if (busy) {
                        item { ThinkingRow(displayMessages.lastOrNull()) }
                    }
                    item { Spacer(Modifier.height(2.dp)) }
                }
            }
            if (activeChildCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onOpenChildRuns),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            AppLanguage.tr(
                                "子任务 $activeChildCount",
                                "$activeChildCount child tasks"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            if (busy && liveFrame != null) {
                val frame = liveFrame!!
                val modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 10.dp)
                if (frame.kind == LivePreview.KIND_BROWSER) {
                    LivePreviewBrowserCard(
                        frame = frame,
                        modifier = modifier,
                        onOpen = { showLivePreview = true },
                        onClose = { LivePreview.clear() }
                    )
                } else {
                    LivePreviewCard(
                        frame = frame,
                        modifier = modifier,
                        onOpen = { showLivePreview = true },
                        onClose = { LivePreview.clear() }
                    )
                }
            }
        }

        if (!busy && lastRun != null) {
            LastRunCard(
                run = lastRun!!,
                onOpenHistory = { showHistory = true },
                onDismiss = { AssistantConversation.dismissLastRun() }
            )
        }

        Composer(
            input = input,
            onInputChange = {
                input = it
                AssistantBridge.updateInput(it)
            },
            onSend = ::submit,
            onStop = onStop,
            busy = busy,
            queuedSteering = queuedSteering,
            config = llmConfig,
            configs = llmConfigs,
            activeLlmId = activeLlmId,
            onSelectModel = onSelectModel,
            onOpenSettings = onOpenModelSettings,
            permissionLevel = permissionLevel,
            onPermissionLevelChange = onPermissionLevelChange,
            onOpenPermissionSettings = onOpenPermissionSettings,
            onReasoningEffortChange = onReasoningEffortChange,
            focusRequester = focusRequester
        )
    }

    if (showLivePreview && liveFrame != null) {
        LivePreviewDialog(
            frame = liveFrame!!,
            onDismiss = { showLivePreview = false }
        )
    }

    if (showHistory) {
        RunHistoryDialog(
            context = context,
            onDismiss = { showHistory = false }
        )
    }
}

@Composable
private fun AgentHeader(
    llmConfig: LlmConfig,
    busy: Boolean,
    onClear: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val status = when {
        !llmConfig.isConfigured -> AppLanguage.tr("规则模式 · 未配置模型", "Rule mode · no model")
        busy -> AppLanguage.tr("正在处理任务…", "Working on it…")
        else -> AppLanguage.tr("在线 · 可操作容器与浏览器", "Online · can operate containers and the browser")
    }
    val dotColor = when {
        !llmConfig.isConfigured -> Color(0xFFFF9F0A)
        busy -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF34C759)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp)) {
            Image(
                painter = painterResource(R.drawable.ai_assistant),
                contentDescription = "NestSpace Agent",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .padding(2.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "NestSpace Agent",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onClear, enabled = !busy) {
            Icon(
                Icons.Rounded.AddComment,
                contentDescription = AppLanguage.tr("新对话", "New conversation"),
                tint = if (busy) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        IconButton(onClick = onOpenHistory) {
            Icon(
                Icons.Rounded.History,
                contentDescription = AppLanguage.tr("运行记录", "Run history"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = AppLanguage.tr("空间设置", "Space settings"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class Suggestion(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val prompt: String
)

private val suggestions = listOf(
    Suggestion(
        Icons.Rounded.Download,
        AppLanguage.tr("下载应用", "Download apps"),
        AppLanguage.tr("搜索并下载网易云音乐 APK", "Search for and download the NetEase Cloud Music APK"),
        AppLanguage.tr(
            "帮我下载网易音乐 app 到系统下载目录",
            "Download the NetEase Cloud Music app to the system Downloads folder"
        )
    ),
    Suggestion(
        Icons.Rounded.Apps,
        AppLanguage.tr("控制容器应用", "Control a container app"),
        AppLanguage.tr("无头启动 testapp，点击按钮并截图", "Launch testapp headlessly, tap the button and screenshot"),
        AppLanguage.tr(
            "以 headless 模式打开 virtual-1 的 Stage B Test，点击按钮并截图",
            "Open Stage B Test in virtual-1 in headless mode, tap the button and screenshot"
        )
    ),
    Suggestion(
        Icons.Rounded.Public,
        AppLanguage.tr("浏览网页", "Browse the web"),
        AppLanguage.tr("用无头浏览器打开并总结网页", "Open and summarize a page in the headless browser"),
        AppLanguage.tr(
            "用无头浏览器打开 https://www.bing.com 并告诉我页面内容",
            "Open https://www.bing.com in the headless browser and tell me what is on the page"
        )
    ),
    Suggestion(
        Icons.Rounded.Info,
        AppLanguage.tr("检查系统", "Check the system"),
        AppLanguage.tr("查看设备、网络和浏览器状态", "Inspect device, network and browser status"),
        AppLanguage.tr(
            "检查当前系统状态，并告诉我浏览器运行模式",
            "Check the current system status and tell me the browser run mode"
        )
    )
)

@Composable
private fun WelcomeState(
    configured: Boolean,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 5.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )
        ) {
            Image(
                painter = painterResource(R.drawable.ai_assistant),
                contentDescription = null,
                modifier = Modifier
                    .padding(5.dp)
                    .size(88.dp)
                    .clip(CircleShape)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            AppLanguage.tr("你好，我是 NestSpace Agent", "Hi, I'm NestSpace Agent"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(7.dp))
        Text(
            AppLanguage.tr(
                "我可以浏览网页、下载文件，也可以控制 virtual-1 里的应用完成多步任务。",
                "I can browse the web, download files and control apps inside " +
                    "virtual-1 to complete multi-step tasks."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(13.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FeaturePill(AppLanguage.tr("无头浏览器", "Headless browser"))
            FeaturePill(AppLanguage.tr("容器控制", "Container control"))
            FeaturePill(AppLanguage.tr("系统诊断", "System diagnostics"))
        }

        if (!configured) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        AppLanguage.tr(
                        "配置模型后可使用完整的浏览器与容器 Agent",
                        "Configure a model to use the full browser and container agent"
                    ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        AppLanguage.tr("去配置", "Configure"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onConfigure)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                AppLanguage.tr("试试这样问", "Try asking"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                AppLanguage.tr("点击填入", "Tap to fill"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(10.dp))
        suggestions.chunked(2).forEachIndexed { index, rowItems ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        modifier = Modifier.weight(1f),
                        onClick = { onSuggestion(suggestion.prompt) }
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun FeaturePill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .heightIn(min = 112.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    suggestion.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                suggestion.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                suggestion.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UserMessage(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomEnd = 6.dp,
                bottomStart = 20.dp
            ),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text,
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun PendingSkillCard(
    text: String,
    decision: String?,
    onEnable: () -> Unit,
    onIgnore: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.tr("启用这个技能吗？", "Enable this skill?"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(3.dp))
            when (decision) {
                "enabled" -> Text(
                    AppLanguage.tr("已启用，会出现在助手的技能索引中。", "Enabled; it is now in the assistant's skill index."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                "ignored" -> Text(
                    AppLanguage.tr("已忽略，技能已删除。", "Ignored; this skill was deleted."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onIgnore) {
                        Text(AppLanguage.tr("忽略", "Ignore"))
                    }
                    TextButton(onClick = onEnable) {
                        Text(AppLanguage.tr("启用", "Enable"))
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingMemoryCard(
    text: String,
    decision: String?,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    AppLanguage.tr("要记住这个做法吗？", "Remember this procedure?"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(7.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(3.dp))
            when (decision) {
                "approved" -> Text(
                    AppLanguage.tr("已确认，以后会按这个做法执行。", "Confirmed; I'll follow it from now on."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                "ignored" -> Text(
                    AppLanguage.tr("已忽略，这条记忆已删除。", "Ignored; this memory was deleted."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onIgnore) {
                        Text(AppLanguage.tr("忽略", "Ignore"))
                    }
                    TextButton(onClick = onConfirm) {
                        Text(AppLanguage.tr("确认", "Confirm"))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Image(
            painter = painterResource(R.drawable.ai_assistant),
            contentDescription = null,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "NestSpace Agent",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (copied) AppLanguage.tr("已复制", "Copied")
                        else AppLanguage.tr("复制回复", "Copy reply"),
                        modifier = Modifier.size(15.dp),
                        tint = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                MarkdownContent(text)
            }
        }
    }
}

@Composable
private fun MarkdownContent(text: String) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Prose -> Text(
                    markdownText(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                is MarkdownBlock.Heading -> Text(
                    markdownText(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                is MarkdownBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        "•",
                        modifier = Modifier.width(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        markdownText(block.text),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is MarkdownBlock.Numbered -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        "${block.number}.",
                        modifier = Modifier.width(26.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        markdownText(block.text),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is MarkdownBlock.Quote -> Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        markdownText(block.text),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MarkdownBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 2.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                is MarkdownBlock.Table -> MarkdownTable(block)

                is MarkdownBlock.Code -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
                ) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        if (block.language.isNotBlank()) {
                            Text(
                                block.language,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }
                        Text(
                            block.code,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 19.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
            ) {
                block.headers.forEach { cell ->
                    Text(
                        markdownText(cell),
                        modifier = Modifier.weight(1f).padding(8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            block.rows.forEach { row ->
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
                Row(Modifier.fillMaxWidth()) {
                    block.headers.indices.forEach { index ->
                        Text(
                            markdownText(row.getOrElse(index) { "" }),
                            modifier = Modifier.weight(1f).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class Prose(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Numbered(val number: String, val text: String) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Code(val language: String, val code: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = StringBuilder()
    var code = StringBuilder()
    var inCode = false
    var language = ""

    fun flushProse() {
        val value = prose.toString().trim()
        if (value.isNotEmpty()) blocks += MarkdownBlock.Prose(value)
        prose.setLength(0)
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        if (inCode) {
            if (trimmed.startsWith("```")) {
                blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
                code = StringBuilder()
                inCode = false
                language = ""
            } else {
                code.appendLine(line)
            }
            index++
            continue
        }

        if (trimmed.startsWith("```")) {
            flushProse()
            inCode = true
            language = trimmed.removePrefix("```").trim()
            index++
            continue
        }

        if (trimmed.contains('|') && index + 1 < lines.size &&
            isTableSeparator(lines[index + 1])
        ) {
            flushProse()
            val headers = tableCells(line)
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size &&
                lines[index].trim().isNotEmpty() &&
                lines[index].contains('|')
            ) {
                val cells = tableCells(lines[index])
                if (cells.size > 1) rows += cells
                index++
            }
            blocks += MarkdownBlock.Table(headers, rows)
            continue
        }

        val heading = HEADING.find(trimmed)
        if (heading != null) {
            flushProse()
            blocks += MarkdownBlock.Heading(
                heading.groupValues[1].length,
                heading.groupValues[2].trim()
            )
            index++
            continue
        }

        if (HR.matches(trimmed)) {
            flushProse()
            blocks += MarkdownBlock.Divider
            index++
            continue
        }

        val bullet = BULLET.find(line)
        if (bullet != null) {
            flushProse()
            blocks += MarkdownBlock.Bullet(bullet.groupValues[1].trim())
            index++
            continue
        }

        val numbered = NUMBERED.find(trimmed)
        if (numbered != null) {
            flushProse()
            blocks += MarkdownBlock.Numbered(
                numbered.groupValues[1],
                numbered.groupValues[2].trim()
            )
            index++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushProse()
            blocks += MarkdownBlock.Quote(quote.groupValues[1].trim())
            index++
            continue
        }

        if (trimmed.isEmpty()) {
            flushProse()
        } else {
            prose.appendLine(line)
        }
        index++
    }

    if (inCode) blocks += MarkdownBlock.Code(language, code.toString().trimEnd())
    flushProse()
    return blocks.ifEmpty { listOf(MarkdownBlock.Prose(text)) }
}

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|")
        .split('|')
        .map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { it.matches(Regex(":?-{2,}:?")) }
}

private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.+)$")
private val NUMBERED = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
private val QUOTE = Regex("^\\s*>\\s?(.*)$")
private val HR = Regex("^([-*_])\\1{2,}$")

@Composable
private fun markdownText(text: String): AnnotatedString {
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, inlineCodeColor, linkColor) {
        buildAnnotatedString {
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("**", index) -> {
                        val end = text.indexOf("**", index + 2)
                        if (end > index) {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                                append(text.substring(index + 2, end))
                            }
                            index = end + 2
                        } else {
                            append("**")
                            index += 2
                        }
                    }

                    text.startsWith("~~", index) -> {
                        val end = text.indexOf("~~", index + 2)
                        if (end > index) {
                            withStyle(
                                SpanStyle(textDecoration = TextDecoration.LineThrough)
                            ) {
                                append(text.substring(index + 2, end))
                            }
                            index = end + 2
                        } else {
                            append("~~")
                            index += 2
                        }
                    }

                    text[index] == '`' -> {
                        val end = text.indexOf('`', index + 1)
                        if (end > index) {
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    color = inlineCodeColor
                                )
                            ) {
                                append(text.substring(index + 1, end))
                            }
                            index = end + 1
                        } else {
                            append('`')
                            index++
                        }
                    }

                    text[index] == '[' -> {
                        val labelEnd = text.indexOf("](", index + 1)
                        val urlEnd = if (labelEnd > index) text.indexOf(')', labelEnd + 2) else -1
                        if (labelEnd > index && urlEnd > labelEnd) {
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            ) {
                                append(text.substring(index + 1, labelEnd))
                            }
                            index = urlEnd + 1
                        } else {
                            append('[')
                            index++
                        }
                    }

                    text[index] == '*' && (index + 1 >= text.length || text[index + 1] != '*') -> {
                        val end = text.indexOf('*', index + 1)
                        if (end > index + 1) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(index + 1, end))
                            }
                            index = end + 1
                        } else {
                            append('*')
                            index++
                        }
                    }

                    else -> {
                        append(text[index])
                        index++
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolActivityCard(text: String) {
    val tool = remember(text) { parseToolActivity(text) }
    val presentation = remember(tool) { presentTool(tool) }
    var expanded by remember(text) { mutableStateOf(false) }
    val accent = when {
        !presentation.ok -> MaterialTheme.colorScheme.error
        presentation.warning -> Color(0xFFFF9F0A)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.11f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        presentation.icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = accent
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        ToolStatusPill(
                            label = presentation.statusLabel,
                            ok = presentation.ok,
                            warning = presentation.warning
                        )
                    }
                    if (presentation.summary.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            presentation.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                            maxLines = if (expanded) 6 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) {
                        AppLanguage.tr("收起工具详情", "Collapse tool details")
                    } else {
                        AppLanguage.tr("展开工具详情", "Expand tool details")
                    },
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 9.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                Text(
                    "${presentation.toolName} · ${presentation.statusLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                if (presentation.detail.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    SelectionContainer {
                        Text(
                            presentation.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private data class ToolActivity(val name: String, val status: String, val body: String)

private data class ToolPresentation(
    val toolName: String,
    val title: String,
    val summary: String,
    val detail: String,
    val icon: ImageVector,
    val statusLabel: String,
    val ok: Boolean,
    val warning: Boolean = false
)

private fun parseToolActivity(text: String): ToolActivity {
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val separator = firstLine.indexOf(" -> ")
    val name = if (separator >= 0) firstLine.substring(0, separator) else firstLine
    val status = if (separator >= 0) firstLine.substring(separator + 4).trim() else "ok"
    val body = text.substringAfter('\n', "").trim()
    return ToolActivity(name.trim(), status, body)
}

private fun presentTool(tool: ToolActivity): ToolPresentation {
    val ok = tool.status == "ok"
    val warning = tool.status == "TIMEOUT"
    val statusLabel = when {
        ok -> AppLanguage.tr("完成", "Done")
        warning -> AppLanguage.tr("超时", "Timed out")
        tool.status == "USER_DENIED" -> AppLanguage.tr("已拒绝", "Denied")
        tool.status == "TAKEOVER_SKIPPED" -> AppLanguage.tr("已跳过", "Skipped")
        tool.status == "CONTAINER_NOT_RUNNING" -> AppLanguage.tr("容器未运行", "Container not running")
        else -> AppLanguage.tr("失败", "Failed")
    }
    val json = parseJsonObject(tool.body)
    val title = toolTitle(tool.name)
    val summary = toolSummary(tool, json)
    val icon = toolIcon(tool.name)
    val detail = toolDetail(tool, json)
    return ToolPresentation(
        toolName = tool.name,
        title = title,
        summary = summary,
        detail = detail,
        icon = icon,
        statusLabel = statusLabel,
        ok = ok,
        warning = warning
    )
}

private fun toolTitle(name: String): String = when (name) {
    "space_launch", "space_multi_open" -> AppLanguage.tr("打开应用", "Open app")
    "space_stop" -> AppLanguage.tr("关闭应用", "Close app")
    "space_list_apps" -> AppLanguage.tr("查看应用", "List apps")
    "space_list_installed" -> AppLanguage.tr("查看已安装应用", "List installed apps")
    "space_install_apk" -> AppLanguage.tr("安装应用", "Install app")
    "space_uninstall" -> AppLanguage.tr("卸载应用", "Uninstall app")
    "space_permissions" -> AppLanguage.tr("应用权限", "App permissions")
    "ui_observe" -> AppLanguage.tr("查看界面", "Inspect screen")
    "ui_tap" -> AppLanguage.tr("点击界面", "Tap screen")
    "ui_set_text" -> AppLanguage.tr("输入文字", "Type text")
    "ui_wait_for" -> AppLanguage.tr("等待界面", "Wait for screen")
    "ui_screenshot" -> AppLanguage.tr("截取界面", "Screenshot")
    "ui_key" -> AppLanguage.tr("返回按键", "Press key")
    "browser_open", "browser_navigate" -> AppLanguage.tr("打开网页", "Open page")
    "browser_read_text" -> AppLanguage.tr("读取网页", "Read page")
    "browser_click" -> AppLanguage.tr("点击网页", "Click page")
    "browser_download" -> AppLanguage.tr("下载文件", "Download file")
    "browser_tabs", "browser_switch_tab", "browser_close_tab" ->
        AppLanguage.tr("浏览器标签", "Browser tabs")
    "system_status" -> AppLanguage.tr("检查系统", "Check system")
    "hand_to_user" -> AppLanguage.tr("交给你处理", "Hand to you")
    "appfn_list", "appfn_status", "appfn_execute" ->
        AppLanguage.tr("应用功能", "App functions")
    else -> AppLanguage.tr("执行工具", "Tool action")
}

private fun toolIcon(name: String): ImageVector = when {
    name.startsWith("browser_") -> Icons.Rounded.Public
    name.startsWith("appfn_") -> Icons.Rounded.Build
    name == "hand_to_user" -> Icons.Rounded.SmartToy
    name == "system_status" -> Icons.Rounded.Info
    name == "space_install_apk" || name == "browser_download" -> Icons.Rounded.Download
    name == "space_uninstall" -> Icons.Rounded.Delete
    name == "ui_observe" || name == "ui_screenshot" -> Icons.Rounded.Visibility
    name == "ui_tap" -> Icons.Rounded.TouchApp
    name == "ui_set_text" -> Icons.Rounded.Keyboard
    name == "ui_wait_for" -> Icons.Rounded.HourglassEmpty
    name == "space_launch" || name == "space_multi_open" -> Icons.Rounded.Apps
    else -> Icons.Rounded.Build
}

private fun toolSummary(tool: ToolActivity, json: JSONObject?): String {
    if (tool.status != "ok") {
        return tool.body.lineSequence().firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: tool.status
    }
    if (json == null) {
        return tool.body.lineSequence().firstOrNull().orEmpty().take(160)
    }
    val body = tool.body
    return when (tool.name) {
        "space_launch", "space_multi_open" -> buildString {
            append(appLabel(json.optString("package")))
            json.optString("instance_id").takeIf { it.isNotBlank() }?.let {
                append(" · ").append(it)
            }
            if (json.optBoolean("headless")) {
                append(AppLanguage.tr(" · 无头", " · headless"))
            }
        }
        "space_list_apps" -> AppLanguage.tr(
            "${json.optJSONArray("apps")?.length() ?: 0} 个应用",
            "${json.optJSONArray("apps")?.length() ?: 0} apps"
        )
        "space_list_installed" -> AppLanguage.tr(
            "${json.optJSONArray("apps")?.length() ?: 0} 个虚拟应用",
            "${json.optJSONArray("apps")?.length() ?: 0} virtual apps"
        )
        "space_install_apk" -> json.optString("package").takeIf { it.isNotBlank() }
            ?: AppLanguage.tr("安装完成", "Installed")
        "space_uninstall" -> AppLanguage.tr(
            "已卸载 ${json.optString("package")}",
            "Uninstalled ${json.optString("package")}"
        )
        "space_permissions" -> listOf(
            json.optString("action"),
            json.optString("package"),
            json.optString("permission")
        ).filter { it.isNotBlank() }.joinToString(" · ")
        "ui_observe" -> {
            if (json.optBoolean("needs_user")) {
                val reason = when (json.optString("user_reason")) {
                    "payment" -> AppLanguage.tr("支付", "payment")
                    "captcha" -> AppLanguage.tr("验证码", "captcha")
                    "login" -> AppLanguage.tr("登录", "login")
                    "permission" -> AppLanguage.tr("权限", "permission")
                    else -> AppLanguage.tr("这一步", "this step")
                }
                AppLanguage.tr("需要你处理$reason", "Needs you to handle $reason")
            } else {
                val count = json.optInt(
                    "nodes_count",
                    json.optJSONArray("nodes")?.length() ?: 0
                )
                AppLanguage.tr("已读取 $count 个控件", "Read $count controls")
            }
        }
        "ui_tap" -> humanizeTap(tool.body)
        "ui_set_text" -> AppLanguage.tr("已输入文字", "Text entered")
        "ui_wait_for" -> humanizeWait(tool.body)
        "ui_key" -> AppLanguage.tr("已按下返回键", "Pressed back")
        "ui_screenshot" -> AppLanguage.tr("已截取当前界面", "Captured the screen")
        "browser_open", "browser_navigate" -> {
            val title = json.optString("title")
            val url = json.optString("url")
            listOf(title, url).firstOrNull { it.isNotBlank() } ?: AppLanguage.tr("页面已打开", "Page opened")
        }
        "browser_read_text" -> AppLanguage.tr(
            "已读取 ${body.length} 个字符",
            "Read ${body.length} characters"
        )
        "browser_download" -> listOf(
            json.optString("name"),
            json.optString("url")
        ).firstOrNull { it.isNotBlank() } ?: AppLanguage.tr("下载完成", "Downloaded")
        "system_status" -> listOf(
            json.optString("android"),
            json.optString("battery_percent").takeIf { it.isNotBlank() }?.let { "$it%" }
        ).mapNotNull { it?.takeIf(String::isNotBlank) }.joinToString(" · ")
        "hand_to_user" -> when (json.optString("takeover")) {
            "completed" -> AppLanguage.tr("你已完成处理", "You finished this step")
            "skipped" -> AppLanguage.tr("你跳过了这一步", "You skipped this step")
            else -> AppLanguage.tr("已把 App 交给你", "Handed the app to you")
        }
        else -> tool.body.lineSequence().firstOrNull().orEmpty().take(160)
    }
}

private fun humanizeTap(body: String): String {
    val line = body.lineSequence().firstOrNull().orEmpty()
    return when {
        line.startsWith("tapped ") -> AppLanguage.tr(
            "已点击 ${line.removePrefix("tapped ")}",
            "Tapped ${line.removePrefix("tapped ")}"
        )
        line == "tap failed" -> AppLanguage.tr("点击失败", "Tap failed")
        else -> line.take(160)
    }
}

private fun humanizeWait(body: String): String {
    val line = body.lineSequence().firstOrNull().orEmpty()
    return when {
        line.startsWith("found: ") -> {
            val text = line.removePrefix("found: ")
            AppLanguage.tr("已等到「$text」", "Found \"$text\"")
        }
        line.startsWith("timeout: ") -> {
            val text = line.removePrefix("timeout: ")
            AppLanguage.tr("等待「$text」超时", "Timed out waiting for \"$text\"")
        }
        else -> line.take(160)
    }
}

private fun appLabel(packageName: String): String = when (packageName) {
    "tv.danmaku.bili" -> AppLanguage.tr("哔哩哔哩", "Bilibili")
    "com.xunmeng.pinduoduo" -> AppLanguage.tr("拼多多", "Pinduoduo")
    "com.xingin.xhs" -> AppLanguage.tr("小红书", "Xiaohongshu")
    "com.tencent.mm" -> AppLanguage.tr("微信", "WeChat")
    "com.taobao.taobao" -> AppLanguage.tr("淘宝", "Taobao")
    "com.ss.android.ugc.aweme" -> AppLanguage.tr("抖音", "Douyin")
    "com.coke.testapp" -> AppLanguage.tr("测试应用", "Test app")
    else -> packageName.ifBlank { AppLanguage.tr("应用", "App") }
}

private fun toolDetail(tool: ToolActivity, json: JSONObject?): String {
    if (tool.status != "ok" || json == null) return prettyBody(tool.body)
    return when (tool.name) {
        "space_launch", "space_multi_open" -> buildString {
            append(AppLanguage.tr("应用：", "App: ")).append(appLabel(json.optString("package")))
            append('\n').append(AppLanguage.tr("包名：", "Package: ")).append(json.optString("package"))
            json.optString("instance_id").takeIf { it.isNotBlank() }?.let {
                append('\n').append(AppLanguage.tr("实例：", "Instance: ")).append(it)
            }
            json.optString("activity").takeIf { it.isNotBlank() }?.let {
                append('\n').append(AppLanguage.tr("页面：", "Activity: ")).append(it)
            }
            append('\n').append(AppLanguage.tr("模式：", "Mode: ")).append(
                if (json.optBoolean("headless")) {
                    AppLanguage.tr("无头（后台）", "Headless (background)")
                } else {
                    AppLanguage.tr("前台", "Foreground")
                }
            )
        }
        "ui_observe" -> buildString {
            append(AppLanguage.tr("应用：", "App: ")).append(appLabel(json.optString("package")))
            append('\n').append(AppLanguage.tr("页面：", "Activity: ")).append(json.optString("activity"))
            append('\n').append(AppLanguage.tr("控件：", "Controls: "))
                .append(json.optInt("nodes_count", json.optJSONArray("nodes")?.length() ?: 0))
                .append(AppLanguage.tr(" 个", ""))
            append('\n').append(AppLanguage.tr("需要你处理：", "Needs you: ")).append(
                if (json.optBoolean("needs_user")) {
                    json.optString("user_reason").ifBlank { AppLanguage.tr("是", "Yes") }
                } else {
                    AppLanguage.tr("否", "No")
                }
            )
        }
        "browser_open", "browser_navigate" -> buildString {
            json.optString("title").takeIf { it.isNotBlank() }?.let {
                append(AppLanguage.tr("标题：", "Title: ")).append(it).append('\n')
            }
            append(AppLanguage.tr("地址：", "URL: ")).append(json.optString("url")).append('\n')
            append(AppLanguage.tr("状态：", "State: ")).append(
                if (json.optBoolean("loaded")) {
                    AppLanguage.tr("已加载", "Loaded")
                } else {
                    AppLanguage.tr("加载中", "Loading")
                }
            )
        }
        "system_status" -> buildString {
            append(AppLanguage.tr("系统：", "System: ")).append(json.optString("android")).append('\n')
            append(AppLanguage.tr("机型：", "Model: ")).append(json.optString("model")).append('\n')
            append(AppLanguage.tr("电量：", "Battery: ")).append(json.optString("battery_percent")).append('%').append('\n')
            append(AppLanguage.tr("网络：", "Network: ")).append(json.optString("network"))
        }
        "hand_to_user" -> buildString {
            append(AppLanguage.tr("原因：", "Reason: ")).append(json.optString("reason")).append('\n')
            append(AppLanguage.tr("结果：", "Result: ")).append(json.optString("takeover"))
        }
        else -> prettyBody(tool.body)
    }
}

private fun parseJsonObject(text: String): JSONObject? =
    runCatching { JSONObject(text) }.getOrNull()

private fun prettyBody(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed).toString(2)
            else -> trimmed
        }
    }.getOrDefault(trimmed).take(6_000)
}

@Composable
private fun ToolStatusPill(label: String, ok: Boolean, warning: Boolean) {
    val color = when {
        !ok -> MaterialTheme.colorScheme.error
        warning -> Color(0xFFFF9F0A)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ErrorMessage(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.78f)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AppLanguage.tr("执行失败", "Failed"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

/**
 * CircularProgressIndicator freezes when the system animator scale is 0, which
 * is common on emulators and in "remove animations" accessibility mode. Drive
 * this small arc from the frame clock so the assistant always shows activity.
 */
@Composable
private fun ThinkingSpinner(color: Color, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    angle = (angle + (now - previous) / 1_000_000f * 360f / 750f) % 360f
                }
                previous = now
            }
        }
    }
    Canvas(modifier) {
        val stroke = 1.8.dp.toPx()
        rotate(angle) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun ThinkingRow(lastMessage: AgentMessage?) {
    val label = if (lastMessage?.role == AgentMessage.Role.TOOL) {
        AppLanguage.tr("正在继续处理…", "Continuing…")
    } else {
        AppLanguage.tr("正在思考…", "Thinking…")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ai_assistant),
            contentDescription = null,
            modifier = Modifier.size(26.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThinkingSpinner(
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    queuedSteering: Int,
    config: LlmConfig,
    configs: List<LlmConfig>,
    activeLlmId: String,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    permissionLevel: ToolPermissionLevel,
    onPermissionLevelChange: (ToolPermissionLevel) -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    focusRequester: FocusRequester
) {
    var modelMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    val canSend = input.isNotBlank()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val modelRevision by LocalSpeechModelStore.revision.collectAsState()
    val speechLanguage = remember { FloatingDotSettings.load(context).speechLanguage }
    val speechReady = remember(modelRevision, speechLanguage) {
        LocalSpeechModelStore.isReady(context, speechLanguage)
    }
    val dictation = remember { VoiceDictation(context.applicationContext) }

    fun startVoice() {
        voiceMode = true
        dictation.start { text ->
            onInputChange(text)
            voiceMode = false
        }
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoice()
        } else {
            Toast.makeText(
                context,
                AppLanguage.tr("需要麦克风权限才能语音输入", "Microphone permission is required"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun requestVoice() {
        if (!speechReady) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startVoice()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(dictation, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                dictation.stop()
                voiceMode = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dictation.shutdown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
    ) {
        if (queuedSteering > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr(
                        "已排队 $queuedSteering 条补充，将在下一步注入",
                        "$queuedSteering follow-up message(s) queued for the next step"
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { AssistantConversation.clearSteering() }) {
                    Text(AppLanguage.tr("清空", "Clear"))
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
            )
        ) {
            Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.SmartToy,
                            label = config.model.ifBlank {
                                config.name.ifBlank { AppLanguage.tr(AppLanguage.tr("选择模型", "Choose model"), "Choose model") }
                            },
                            onClick = { modelMenu = true }
                        )
                        ModelMenu(
                            expanded = modelMenu,
                            onDismiss = { modelMenu = false },
                            configs = configs,
                            activeLlmId = activeLlmId,
                            onSelect = {
                                modelMenu = false
                                onSelectModel(it)
                            },
                            onOpenSettings = {
                                modelMenu = false
                                onOpenSettings()
                            }
                        )
                    }
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.Psychology,
                            label = AppLanguage.tr(
                                "思考 · ${config.reasoningEffort.label}",
                                "Reasoning · ${config.reasoningEffort.label}"
                            ),
                            onClick = { effortMenu = true }
                        )
                        ReasoningMenu(
                            expanded = effortMenu,
                            onDismiss = { effortMenu = false },
                            current = config.reasoningEffort,
                            onSelect = {
                                effortMenu = false
                                onReasoningEffortChange(it)
                            }
                        )
                    }
                    Box {
                        ControlChip(
                            icon = Icons.Rounded.Security,
                            label = AppLanguage.tr(
                                "权限 · ${permissionLevel.label}",
                                "Access · ${permissionLevel.label}"
                            ),
                            onClick = { permissionMenu = true }
                        )
                        PermissionMenu(
                            expanded = permissionMenu,
                            onDismiss = { permissionMenu = false },
                            current = permissionLevel,
                            onSelect = {
                                permissionMenu = false
                                onPermissionLevelChange(it)
                            },
                            onOpenSettings = {
                                permissionMenu = false
                                onOpenPermissionSettings()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (voiceMode) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp, max = 150.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (dictation.preparing.value) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = if (dictation.listening.value) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(Modifier.width(9.dp))
                            Text(
                                dictation.transcript.value.ifBlank {
                                    dictation.status.value
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (dictation.transcript.value.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        BasicTextField(
                            value = input,
                            onValueChange = onInputChange,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 28.dp, max = 150.dp)
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { if (canSend) onSend() }
                            ),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (input.isBlank()) {
                                        Text(
                                            AppLanguage.tr(
                                                "给 AI 助手发送消息…",
                                                "Message the AI assistant…"
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                .copy(alpha = 0.68f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }
                    if (busy && input.isNotBlank()) {
                        // Keep a stop affordance even while the user has a
                        // steering draft; the main button sends it.
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = AppLanguage.tr("停止", "Stop"),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            when {
                                busy && input.isBlank() -> onStop()
                                voiceMode -> {
                                    val recognized = dictation.transcript.value
                                    dictation.stop()
                                    voiceMode = false
                                    if (recognized.isNotBlank()) onInputChange(recognized)
                                }
                                input.isBlank() && speechReady -> requestVoice()
                                else -> onSend()
                            }
                        },
                        enabled = voiceMode || busy || canSend ||
                            (input.isBlank() && speechReady),
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        when {
                            busy && input.isBlank() -> Icon(
                                Icons.Rounded.Stop,
                                contentDescription = AppLanguage.tr("停止", "Stop"),
                                modifier = Modifier.size(20.dp)
                            )
                            voiceMode -> Icon(
                                Icons.Rounded.Keyboard,
                                contentDescription = AppLanguage.tr(
                                    "切换到文字输入",
                                    "Switch to text input"
                                ),
                                modifier = Modifier.size(20.dp)
                            )
                            input.isBlank() && speechReady -> Icon(
                                Icons.Rounded.Mic,
                                contentDescription = AppLanguage.tr("语音输入", "Voice input"),
                                modifier = Modifier.size(20.dp)
                            )
                            else -> Icon(
                                Icons.Rounded.ArrowUpward,
                                contentDescription = AppLanguage.tr("发送", "Send"),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastRunCard(
    run: RunSummary,
    onOpenHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenHistory),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppLanguage.tr("本次运行", "This run"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        runStatusLabel(run.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatRunDuration(run.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    runMetricsLine(run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = AppLanguage.tr("关闭汇总", "Dismiss summary"),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LivePreviewCard(
    frame: LivePreview.Frame,
    modifier: Modifier,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    val bitmap = rememberLiveBitmap(frame.base64, sampleSize = 2)
    Surface(
        modifier = modifier
            .width(112.dp)
            .height(196.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black,
        shadowElevation = 7.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = AppLanguage.tr("实时界面预览", "Live screen preview"),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr("实时", "LIVE"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6EE7B7),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    frame.label.ifBlank { AppLanguage.tr("容器", "Container") },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = AppLanguage.tr("关闭预览", "Close preview"),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun LivePreviewBrowserCard(
    frame: LivePreview.Frame,
    modifier: Modifier,
    onOpen: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier
            .width(112.dp)
            .height(196.dp)
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 7.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            BrowserPreview(
                url = frame.url,
                modifier = Modifier.fillMaxSize()
            )
            // The WebView consumes touches; keep the card clickable and leave
            // the page itself non-interactive in the miniature preview.
            Box(Modifier.matchParentSize().clickable(onClick = onOpen))
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    AppLanguage.tr("浏览器", "BROWSER"),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF6EE7B7),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    frame.label.ifBlank { frame.url },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = AppLanguage.tr("关闭预览", "Close preview"),
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun BrowserPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val hostWidth = metrics.widthPixels.coerceAtLeast(1)
    val hostHeight = metrics.heightPixels.coerceAtLeast(1)
    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = { ctx ->
            FrameLayout(ctx).apply {
                clipChildren = true
                val web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = BrowserSession.MOBILE_USER_AGENT
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isClickable = false
                    isFocusable = false
                    setBackgroundColor(android.graphics.Color.WHITE)
                    pivotX = 0f
                    pivotY = 0f
                    webView = this
                    loadUrl(url)
                }
                addView(web, FrameLayout.LayoutParams(hostWidth, hostHeight))
                // Render the page at the host display size, then scale the
                // whole WebView down into the preview slot. This keeps the
                // page layout identical to what the host browser sees.
                addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                    val width = (right - left).coerceAtLeast(1)
                    val height = (bottom - top).coerceAtLeast(1)
                    web.scaleX = width.toFloat() / hostWidth
                    web.scaleY = height.toFloat() / hostHeight
                }
            }
        },
        update = { frame ->
            val web = webView ?: (frame.getChildAt(0) as? WebView)
            if (web != null && url.isNotBlank() && web.url != url) web.loadUrl(url)
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}

@Composable
private fun LivePreviewDialog(frame: LivePreview.Frame, onDismiss: () -> Unit) {
    val isBrowser = frame.kind == LivePreview.KIND_BROWSER
    val bitmap = rememberLiveBitmap(frame.base64)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.Black,
            shadowElevation = 10.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
            ) {
                if (isBrowser) {
                    BrowserPreview(
                        url = frame.url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(560.dp)
                    )
                } else if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = AppLanguage.tr("实时界面预览", "Live screen preview"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 620.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        AppLanguage.tr("暂时没有画面", "No frame yet"),
                        modifier = Modifier.padding(24.dp),
                        color = Color.White
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isBrowser) {
                            AppLanguage.tr("浏览器预览", "Browser preview")
                        } else {
                            AppLanguage.tr("实时界面预览", "Live screen preview")
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                    Text(
                        frame.label.ifBlank {
                            if (isBrowser) frame.url else AppLanguage.tr("容器", "Container")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = AppLanguage.tr("关闭预览", "Close preview"),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberLiveBitmap(
    base64: String,
    sampleSize: Int = 1
): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = base64
    ) {
        value = withContext(Dispatchers.Default) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }
                .getOrNull() ?: return@withContext null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        }
    }
    return bitmap
}

@Composable
private fun RunHistoryDialog(context: Context, onDismiss: () -> Unit) {
    var runs by remember { mutableStateOf<List<RunSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<RunSummary?>(null) }
    var events by remember { mutableStateOf<List<RunEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        runs = withContext(Dispatchers.IO) { RunRecordStore.recent(context, 20) }
        loading = false
    }
    LaunchedEffect(selected) {
        val id = selected?.id
        events = if (id == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { RunRecordStore.events(context, id) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    IconButton(onClick = { selected = null }) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = AppLanguage.tr("返回", "Back")
                        )
                    }
                }
                Text(
                    if (selected == null) {
                        AppLanguage.tr("运行记录", "Run history")
                    } else {
                        AppLanguage.tr("运行详情", "Run details")
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            val run = selected
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp
                    )
                }

                run == null && runs.isEmpty() -> Text(
                    AppLanguage.tr("还没有运行记录。", "No runs yet.")
                )

                run == null -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(runs, key = { it.id }) { item ->
                        RunSummaryRow(item) { selected = item }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            run.command,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "${runStatusLabel(run.status)} · " +
                                "${formatRunTime(run.startedAt)} · " +
                                formatRunDuration(run.durationMs) + " · " +
                                AppLanguage.tr(
                                    "${run.eventCount} 条记录",
                                    "${run.eventCount} events"
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (run.hasMetrics) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                runMetricsLine(run),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (events.isEmpty()) {
                        item {
                            Text(
                                AppLanguage.tr("没有详细记录。", "No detailed events."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(events) { event -> RunEventRow(event) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(AppLanguage.tr("关闭", "Close"))
            }
        }
    )
}

@Composable
private fun RunSummaryRow(run: RunSummary, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    runStatusLabel(run.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatRunTime(run.startedAt)} · " +
                    formatRunDuration(run.durationMs) + " · " +
                    AppLanguage.tr("${run.eventCount} 条记录", "${run.eventCount} events"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (run.hasMetrics) {
                Spacer(Modifier.height(3.dp))
                Text(
                    runMetricsLine(run),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (run.summary.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    run.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RunEventRow(event: RunEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(9.dp)) {
            Text(
                eventRoleLabel(event.role),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when (event.role) {
                    AgentMessage.Role.ERROR.name -> MaterialTheme.colorScheme.error
                    AgentMessage.Role.TOOL.name -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    event.text.take(2_000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (event.role == AgentMessage.Role.TOOL.name) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    maxLines = 16,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun runStatusLabel(status: String): String = when (status) {
    RunRecordStore.STATUS_DONE -> AppLanguage.tr("已完成", "Done")
    RunRecordStore.STATUS_STOPPED -> AppLanguage.tr("已停止", "Stopped")
    RunRecordStore.STATUS_ERROR -> AppLanguage.tr("失败", "Failed")
    RunRecordStore.STATUS_INTERRUPTED -> AppLanguage.tr("中断", "Interrupted")
    RunRecordStore.STATUS_RUNNING -> AppLanguage.tr("进行中", "Running")
    else -> status
}

private fun eventRoleLabel(role: String): String = when (role) {
    AgentMessage.Role.USER.name -> AppLanguage.tr("你", "You")
    AgentMessage.Role.AGENT.name -> AppLanguage.tr("助手", "Assistant")
    AgentMessage.Role.TOOL.name -> AppLanguage.tr("工具", "Tool")
    AgentMessage.Role.ERROR.name -> AppLanguage.tr("错误", "Error")
    else -> role
}

private fun formatRunTime(at: Long): String =
    if (at <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

private fun formatRunDuration(ms: Long): String {
    if (ms <= 0) return "—"
    return when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1_000.0)
        else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    }
}

private fun formatMetricTokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.US, "%.0fk", value / 1_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun runMetricsLine(run: RunSummary): String {
    if (!run.hasMetrics) return ""
    val parts = mutableListOf<String>()
    parts += AppLanguage.tr(
        "模型 ${run.modelRequests} 轮",
        "${run.modelRequests} model rounds"
    )
    parts += AppLanguage.tr(
        "工具 ${run.toolCalls} 次",
        "${run.toolCalls} tool calls"
    )
    parts += AppLanguage.tr(
        "输入 ${formatMetricTokens(run.inputTokens)} · 输出 ${formatMetricTokens(run.outputTokens)}",
        "in ${formatMetricTokens(run.inputTokens)} · out ${formatMetricTokens(run.outputTokens)}"
    )
    if (run.reasoningTokens > 0) {
        parts += AppLanguage.tr(
            "思考 ${formatMetricTokens(run.reasoningTokens)}",
            "thinking ${formatMetricTokens(run.reasoningTokens)}"
        )
    }
    if (run.firstFeedbackMs > 0) {
        parts += AppLanguage.tr(
            "首响 ${formatRunDuration(run.firstFeedbackMs)}",
            "first ${formatRunDuration(run.firstFeedbackMs)}"
        )
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ControlChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp)
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    configs: List<LlmConfig>,
    activeLlmId: String,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 280.dp, max = 330.dp)
    ) {
        Text(
            AppLanguage.tr("选择模型", "Choose model"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (configs.isEmpty()) {
            DropdownMenuItem(
                text = { Text(AppLanguage.tr("添加模型…", "Add model…")) },
                onClick = onOpenSettings,
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
            )
        } else {
            configs.forEach { config ->
                val active = config.id == activeLlmId
                DropdownMenuItem(
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    config.name.ifBlank { config.model },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (active) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Normal
                                    }
                                )
                                if (!config.isConfigured) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        AppLanguage.tr("未配置", "Not configured"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text(
                                "${config.model} · ${config.protocol.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = { onSelect(config.id) },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    trailingIcon = if (active) {
                        { Icon(Icons.Rounded.Check, contentDescription = AppLanguage.tr("当前模型", "Current model")) }
                    } else {
                        null
                    }
                )
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(AppLanguage.tr("管理模型…", "Manage models…")) },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
        )
    }
}

@Composable
private fun PermissionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    current: ToolPermissionLevel,
    onSelect: (ToolPermissionLevel) -> Unit,
    onOpenSettings: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)
    ) {
        Text(
            AppLanguage.tr("确认级别", "Confirmation level"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ToolPermissionLevel.entries.forEach { level ->
            val active = level == current
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            level.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            level.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = { onSelect(level) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = if (active) {
                    {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = AppLanguage.tr("当前级别", "Current level")
                        )
                    }
                } else {
                    null
                }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(AppLanguage.tr("自定义确认项…", "Customize confirmations…")) },
            onClick = onOpenSettings,
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) }
        )
    }
}

@Composable
private fun ReasoningMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    current: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = 260.dp, max = 310.dp)
    ) {
        Text(
            AppLanguage.tr("思考程度", "Reasoning effort"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        ReasoningEffort.entries.forEach { effort ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            effort.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (effort == current) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                        Text(
                            effort.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                onClick = { onSelect(effort) },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Psychology,
                        contentDescription = null,
                        tint = if (effort == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                },
                trailingIcon = if (effort == current) {
                    { Icon(Icons.Rounded.Check, contentDescription = AppLanguage.tr("当前思考程度", "Current reasoning effort")) }
                } else {
                    null
                }
            )
        }
    }
}
