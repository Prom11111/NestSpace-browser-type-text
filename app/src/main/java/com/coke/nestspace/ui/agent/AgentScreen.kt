package com.coke.nestspace.ui.agent

import com.coke.nestspace.i18n.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.agent.ChildRunRegistry
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.control.ContainerPreviewCapture
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.agent.ToolPermissionLevel
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.plugin.AgentPluginSupport
import com.coke.nestspace.task.McpTaskClassifier
import com.coke.nestspace.task.OperationTaskRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject

private fun taskIdFromToolMessage(message: AgentMessage): String? {
    if (message.role != AgentMessage.Role.TOOL) return null
    val marker = "${AgentPluginSupport.TOOL_DISPLAY_MARKER} "
    val raw = if (message.text.contains(marker)) {
        message.text.substringAfter(marker)
            .substringAfter('\n', "")
    } else {
        message.text.substringAfter('\n', "")
    }
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (!json.optBoolean("started") && !json.optBoolean("attached")) return null
    return json.optString("task_id").takeIf(String::isNotBlank)
}

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
    onOpenHistory: () -> Unit,
    onOpenMcpTasks: () -> Unit,
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
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val injectedText by AssistantBridge.pendingText.collectAsState()
    val clearVersion by AssistantBridge.clearInput.collectAsState()
    val activeChildCount by ChildRunRegistry.activeCount.collectAsState()
    val queuedSteering by AssistantConversation.queuedSteering.collectAsState()
    val liveFrame by LivePreview.frame.collectAsState()
    val lastRun by AssistantConversation.lastRun.collectAsState()
    val lastRunOrigin by AssistantConversation.lastRunOrigin.collectAsState()
    val activeRunOrigin by AssistantConversation.activeRunOrigin.collectAsState()
    val streamingText by AssistantConversation.streamingText.collectAsState()
    val downloads by BrowserSession.downloadsFlow.collectAsState()
    val operationTasks by OperationTaskRegistry.tasksFlow.collectAsState()
    val mirrorSlot = remember(operationTasks) {
        HeadlessMirrorWindow.activeSlot().takeIf { it >= 0 }
            ?: ContainerProcessPool.lastSlot()
    }
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
            val alive = withContext(Dispatchers.IO) {
                ContainerProcessPool.isProcessAlive(context, slot)
            }
            if (!alive) break
            ContainerPreviewCapture.capture(
                context = context,
                slot = slot,
                label = frame.label,
                rejectBlank = true
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    FloatingDotOverlay.setAssistantPageVisible(context, true)
                    HeadlessMirrorWindow.setAssistantVisible(true)
                }
                Lifecycle.Event.ON_PAUSE ->
                    FloatingDotOverlay.setAssistantPageVisible(context, false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            FloatingDotOverlay.setAssistantPageVisible(context, true)
            HeadlessMirrorWindow.setAssistantVisible(true)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            FloatingDotOverlay.setAssistantPageVisible(context, false)
            HeadlessMirrorWindow.setAssistantVisible(false)
        }
    }

    val externalTaskBusy = busy &&
        activeRunOrigin == AgentMessage.Origin.MCP
    val conversationBusy = busy && !externalTaskBusy
    val displayMessages = remember(messages) {
        messages.filterNot {
            it.welcome || it.origin == AgentMessage.Origin.MCP
        }
    }
    val inlineTaskIds = remember(displayMessages) {
        displayMessages.mapNotNull(::taskIdFromToolMessage).toSet()
    }
    val mcpTaskIds = remember(operationTasks) {
        operationTasks
            .filter { McpTaskClassifier.isMcpTask(it, operationTasks) }
            .mapTo(HashSet()) { it.id }
    }
    val activeMcpTaskCount = remember(operationTasks) {
        McpTaskClassifier.roots(operationTasks).count { !it.terminal }
    }
    val showWelcome = displayMessages.isEmpty() && !conversationBusy
    val visibleDownloads = downloads.filter {
        it.taskId == null ||
            (it.taskId !in inlineTaskIds && it.taskId !in mcpTaskIds)
    }.take(2)
    val visibleOperationTasks = remember(
        operationTasks,
        downloads,
        inlineTaskIds,
        mcpTaskIds
    ) {
        operationTasks
            .filterNot { task ->
                task.id in mcpTaskIds ||
                    task.id in inlineTaskIds ||
                    downloads.any { it.taskId == task.id }
            }
            .filter { task ->
                when (task.kind) {
                    "browser_download" ->
                        task.state == OperationTaskRegistry.State.FAILED &&
                            task.message == "interrupted"
                    "tool_call" ->
                        !task.terminal || task.message == "interrupted"
                    else -> true
                }
            }
            .take(2)
    }

    LaunchedEffect(
        displayMessages.size,
        conversationBusy,
        if (externalTaskBusy) 0 else streamingText.length,
        visibleDownloads.size,
        visibleOperationTasks.size
    ) {
        if (!showWelcome) {
            val count = displayMessages.size + if (conversationBusy) 1 else 0 +
                visibleDownloads.size + visibleOperationTasks.size
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
            externalTaskBusy = externalTaskBusy,
            onClear = clearConversation,
            onOpenHistory = onOpenHistory,
            onOpenMcpTasks = onOpenMcpTasks,
            activeMcpTaskCount = activeMcpTaskCount,
            onOpenSettings = onOpenSettings
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (showWelcome) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    }
                    visibleDownloads.forEach { download ->
                        DownloadProgressCard(
                            download = download,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            onCancel = download.taskId?.let { taskId ->
                                {
                                    OperationTaskRegistry.requestCancel(
                                        taskId,
                                        "user cancelled"
                                    )
                                }
                            }
                        )
                    }
                    visibleOperationTasks.forEach { task ->
                        OperationTaskStatusCard(
                            task = task,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            onCancel = if (task.terminal) null else {
                                {
                                    OperationTaskRegistry.requestCancel(
                                        task.id,
                                        "user cancelled"
                                    )
                                }
                            },
                            onDismiss = if (task.terminal) {
                                { OperationTaskRegistry.dismiss(task.id) }
                            } else {
                                null
                            },
                            onOpenMirror = mirrorSlot?.let { slot ->
                                {
                                    memoryScope.launch {
                                        HeadlessMirrorWindow.promoteToPanel(context, slot)
                                    }
                                }
                            }
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(displayMessages) { message ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            taskIdFromToolMessage(message)?.let { taskId ->
                                val operationTask = operationTasks.firstOrNull {
                                    it.id == taskId
                                }
                                if (operationTask != null &&
                                    McpTaskClassifier.isMcpTask(
                                        operationTask,
                                        operationTasks
                                    )
                                ) {
                                    return@let
                                }
                                val download = downloads.firstOrNull {
                                    it.taskId == taskId
                                }
                                if (download != null) {
                                    DownloadProgressCard(
                                        download = download,
                                        onCancel = if (
                                            download.state == "downloading" ||
                                            download.state == "cancelling"
                                        ) {
                                            {
                                                OperationTaskRegistry.requestCancel(
                                                    taskId,
                                                    "user cancelled"
                                                )
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                } else {
                                    operationTask?.let { task ->
                                        OperationTaskStatusCard(
                                            task = task,
                                            onCancel = if (task.terminal) null else {
                                                {
                                                    OperationTaskRegistry.requestCancel(
                                                        task.id,
                                                        "user cancelled"
                                                    )
                                                }
                                            },
                                            onDismiss = if (task.terminal) {
                                                { OperationTaskRegistry.dismiss(task.id) }
                                            } else {
                                                null
                                            },
                                            onOpenMirror = mirrorSlot?.let { slot ->
                                                {
                                                    memoryScope.launch {
                                                        HeadlessMirrorWindow.promoteToPanel(
                                                            context,
                                                            slot
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!externalTaskBusy && streamingText.isNotBlank()) {
                        item {
                            AssistantMessage(streamingText.trimEnd() + " ▍")
                        }
                    } else if (conversationBusy) {
                        item { ThinkingRow(displayMessages.lastOrNull()) }
                    }
                    items(visibleDownloads, key = { "download:${it.id}" }) { download ->
                        DownloadProgressCard(
                            download = download,
                            onCancel = download.taskId?.let { taskId ->
                                {
                                    OperationTaskRegistry.requestCancel(
                                        taskId,
                                        "user cancelled"
                                    )
                                }
                            }
                        )
                    }
                    items(
                        visibleOperationTasks,
                        key = { "operation:${it.id}" }
                    ) { task ->
                        OperationTaskStatusCard(
                            task = task,
                            onCancel = if (task.terminal) null else {
                                {
                                    OperationTaskRegistry.requestCancel(
                                        task.id,
                                        "user cancelled"
                                    )
                                }
                            },
                            onDismiss = if (task.terminal) {
                                { OperationTaskRegistry.dismiss(task.id) }
                            } else {
                                null
                            },
                            onOpenMirror = mirrorSlot?.let { slot ->
                                {
                                    memoryScope.launch {
                                        HeadlessMirrorWindow.promoteToPanel(context, slot)
                                    }
                                }
                            }
                        )
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

        if (!busy &&
            lastRun != null &&
            lastRunOrigin != AgentMessage.Origin.MCP
        ) {
            LastRunCard(
                run = lastRun!!,
                onOpenHistory = onOpenHistory,
                onDismiss = { AssistantConversation.dismissLastRun() }
            )
        }

        if (llmConfig.isConfigured) {
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
    }

    if (showLivePreview && liveFrame != null) {
        LivePreviewDialog(
            frame = liveFrame!!,
            onDismiss = { showLivePreview = false }
        )
    }

}
