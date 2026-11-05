package com.coke.nestspace.agent

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import com.coke.nestspace.AppGraph
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmConfigStore
import com.coke.nestspace.memory.MemoryExtractor
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillDrafter
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.replay.ReplayManager
import com.coke.nestspace.llm.ChatBlock
import com.coke.nestspace.llm.ChatMessage
import com.coke.nestspace.llm.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide assistant conversation.
 *
 * The assistant tab, the voice sheet and MCP-triggered prompts share one
 * message list and one LLM history. The history is trimmed to a small window
 * and replayed to the model on every turn, which is what makes follow-up
 * questions work ("那第二个呢？", "再点一下").
 */
object AssistantConversation {

    enum class RunOutcome { COMPLETED, FAILED, CANCELLED }

    private const val MAX_HISTORY_MESSAGES = 24

    private fun welcome(): AgentMessage = AgentMessage(
        AgentMessage.Role.AGENT,
        AppLanguage.tr(
            "你好，我是 NestSpace 助手。\n" +
                "点按圆点说话，或点左下角键盘输入；我会记住上下文，继续追问即可。\n" +
                "在 空间设置 → 模型设置 配置大模型后，可以执行更复杂的虚拟空间任务。",
            "Hi, I'm the NestSpace assistant.\n" +
                "Tap the dot to speak, or use the keyboard button; I remember the " +
                "context, so just keep asking.\n" +
                "Configure a model in Space settings → Model settings to handle more " +
                "complex virtual-space tasks."
        ),
        welcome = true
    )

    private val _messages = MutableStateFlow(listOf(welcome()))

    /** Replace the language-dependent greeting without touching user messages. */
    fun refreshWelcome() {
        _messages.update { current ->
            when {
                current.isEmpty() -> listOf(welcome())
                current.any { it.welcome } ->
                    current.map { if (it.welcome) welcome() else it }
                else -> current
            }
        }
    }
    val messages: StateFlow<List<AgentMessage>> = _messages

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Partial assistant text for the current model response. */
    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Memory extraction is a second, best-effort model call after the answer.
    // It must not keep the assistant busy or delay the next queued task.
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val submitLock = Mutex()

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var runContext: Context? = null

    private data class PendingTask(
        val context: Context,
        val text: String,
        val onFinished: ((RunOutcome, String?) -> Unit)?
    )

    private val stateLock = Any()
    private val pendingTasks = ArrayDeque<PendingTask>()
    private val steeringQueue = ArrayDeque<String>()
    private val _queuedSteering = MutableStateFlow(0)

    /** Number of follow-up messages waiting for the next model step. */
    val queuedSteering: StateFlow<Int> = _queuedSteering

    private val _lastRun = MutableStateFlow<RunSummary?>(null)

    /** Metrics from the most recently completed run. */
    val lastRun: StateFlow<RunSummary?> = _lastRun

    fun dismissLastRun() {
        _lastRun.value = null
    }

    /** Text-only turns replayed to the model as multi-turn context. */
    private val history = mutableListOf<ChatMessage>()

    /**
     * Submit a user turn.
     *
     * While a run is active, [queueIfBusy] false treats the text as steering:
     * it is injected before the next model step, or answered immediately if
     * the run has nothing left to do. [queueIfBusy] true is used by scheduled
     * tasks and MCP clients: their task waits for the current run to finish
     * instead of failing.
     */
    fun submit(
        context: Context,
        command: String,
        queueIfBusy: Boolean = false,
        onFinished: ((RunOutcome, String?) -> Unit)? = null
    ): Boolean {
        val text = command.trim()
        if (text.isBlank()) return false
        val app = context.applicationContext
        synchronized(stateLock) {
            if (_busy.value) {
                if (queueIfBusy) {
                    pendingTasks.addLast(PendingTask(app, text, onFinished))
                } else {
                    steeringQueue.addLast(text)
                    _queuedSteering.value = steeringQueue.size
                }
                return true
            }
            _busy.value = true
        }
        startJob(app, text, onFinished)
        return true
    }

    /** Add a steering message to the active run. Returns false when idle. */
    fun steer(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        synchronized(stateLock) {
            if (!_busy.value) return false
            steeringQueue.addLast(value)
            _queuedSteering.value = steeringQueue.size
        }
        return true
    }

    fun clearSteering(): Int {
        synchronized(stateLock) {
            val count = steeringQueue.size
            steeringQueue.clear()
            _queuedSteering.value = 0
            return count
        }
    }

    private fun startJob(
        app: Context,
        text: String,
        onFinished: ((RunOutcome, String?) -> Unit)?
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            submitLock.withLock {
                executeRun(app, text, onFinished)
            }
        }
        synchronized(stateLock) { currentJob = job }
        job.invokeOnCompletion {
            synchronized(stateLock) {
                if (currentJob === job) currentJob = null
            }
        }
        if (!job.start()) {
            synchronized(stateLock) {
                currentJob = null
                _busy.value = false
            }
            runCatching { onFinished?.invoke(RunOutcome.FAILED, null) }
        }
    }

    private suspend fun executeRun(
        app: Context,
        text: String,
        onFinished: ((RunOutcome, String?) -> Unit)?
    ) {
        runContext = app
        LivePreview.clear()
        _lastRun.value = null
        _streamingText.value = ""
        val runStartedAt = System.currentTimeMillis()
        val metrics = RunMetrics()
        var recorder: RunRecorder? = null
        var outcome = RunOutcome.FAILED
        var answer: String? = null
        var runFailed = false
        val consumedSteering = mutableListOf<String>()
        val usedSkills = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            // Creating the record is quick and must survive a stop that lands
            // before the first event is written.
            recorder = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { RunRecordStore.begin(app, text) }.getOrNull()
            }
            currentCoroutineContext().ensureActive()
            recorder?.record(AgentMessage.Role.USER, text)
            val state = LlmConfigStore(app).load()
            val config = state.configs.firstOrNull { it.id == state.activeId }
                ?: state.configs.firstOrNull()
            val registry = AppGraph.registry(app)
            val priorHistory = synchronized(stateLock) { history.toList() }
            val priorAssistantAnswer = priorHistory.asReversed()
                .firstOrNull { it.role == ChatRole.ASSISTANT }
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?.take(1_500)

            if (config != null && config.isConfigured) {
                val runtime = LlmAgentRuntime(registry)
                val memoryContext = withContext(Dispatchers.IO) {
                    MemoryStore(app).contextFor(text)
                }
                val skillIndex = withContext(Dispatchers.IO) {
                    SkillStore(app).enabledIndex()
                }
                ReplayManager.begin(text)
                var firstUserMessage = true
                val trace = runtime.run(
                    config = config,
                    command = text,
                    sessionId = "local-llm",
                    history = priorHistory,
                    memoryContext = memoryContext,
                    skillIndex = skillIndex,
                    onToolCall = { name, args ->
                        _streamingText.value = ""
                        ReplayManager.record(name, args)
                    },
                    onMessage = { message ->
                        _messages.update { it + message }
                        if (message.role == AgentMessage.Role.USER && firstUserMessage) {
                            // The initial command was already recorded above.
                            firstUserMessage = false
                        } else {
                            recorder?.record(message.role, message.text)
                        }
                    },
                    steeringProvider = {
                        drainSteering().also { consumedSteering += it }
                    },
                    metrics = metrics,
                    onTextDelta = { delta ->
                        _streamingText.update { it + delta }
                    },
                    onSkillUsed = { id -> usedSkills += id }
                )
                runFailed = trace.any { it.role == AgentMessage.Role.ERROR }
                answer = trace.lastOrNull { it.role == AgentMessage.Role.AGENT }?.text
                    ?: trace.lastOrNull { it.role == AgentMessage.Role.ERROR }?.text
                recordHistory(text, answer, consumedSteering)
            } else {
                // Rule mode still reaches the container/UI tools; run it off
                // the main thread like the LLM runtime.
                val trace = withContext(Dispatchers.IO) {
                    LocalAgentRuntime(registry).run(text, "local-ui")
                }
                _messages.update { it + trace }
                trace.filter { it.role != AgentMessage.Role.USER }
                    .forEach { recorder?.record(it.role, it.text) }
                metrics.addToolCalls(
                    trace.count { it.role == AgentMessage.Role.TOOL }
                )
                runFailed = trace.any { it.role == AgentMessage.Role.ERROR }
                answer = trace.lastOrNull { it.role == AgentMessage.Role.AGENT }?.text
                    ?: trace.lastOrNull { it.role == AgentMessage.Role.ERROR }?.text
                recordHistory(text, answer, consumedSteering)
            }
            currentCoroutineContext().ensureActive()
            _lastRun.value = withContext(NonCancellable + Dispatchers.IO) {
                recorder?.finish(
                    if (runFailed) RunRecordStore.STATUS_ERROR else RunRecordStore.STATUS_DONE,
                    metrics = metrics.snapshot()
                )
            }
            if (runFailed) {
                ReplayManager.abort()
                outcome = RunOutcome.FAILED
            } else {
                ReplayManager.complete()
                outcome = RunOutcome.COMPLETED
                showPendingMemoryCards(app, runStartedAt)
                showPendingSkillCards(app, runStartedAt)
                val replay = ReplayManager.last()
                if (config?.isConfigured == true &&
                    replay != null &&
                    SkillDrafter.eligible(replay.steps.map { it.name })
                ) {
                    scheduleSkillDraft(
                        app = app,
                        config = config,
                        goal = text,
                        answer = answer,
                        toolNames = replay.steps.map { it.name },
                        runStartedAt = runStartedAt
                    )
                }
                val memoryContext = listOfNotNull(
                    priorAssistantAnswer?.let { "上一轮助手回复：\n$it" },
                    answer?.let { "本轮助手回复：\n$it" }
                ).joinToString("\n\n")
                scheduleMemoryExtraction(
                    app,
                    config,
                    text,
                    consumedSteering,
                    memoryContext,
                    runStartedAt
                )
            }
        } catch (cancelled: CancellationException) {
            val current = recorder
            val summary = current?.stopSummary()
                ?: RunRecordStore.buildStopSummary(text, emptyList())
            _messages.update { it + AgentMessage(AgentMessage.Role.AGENT, summary) }
            recordHistory(text, summary, consumedSteering)
            answer = summary
            ReplayManager.abort()
            outcome = RunOutcome.CANCELLED
            if (current != null) {
                _lastRun.value = withContext(NonCancellable + Dispatchers.IO) {
                    current.finish(
                        RunRecordStore.STATUS_STOPPED,
                        summary,
                        metrics.snapshot()
                    )
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            val message = AppLanguage.tr(
                "助手执行失败：${t.message ?: t.javaClass.simpleName}",
                "Assistant failed: ${t.message ?: t.javaClass.simpleName}"
            )
            recorder?.record(AgentMessage.Role.ERROR, message)
            _lastRun.value = withContext(NonCancellable + Dispatchers.IO) {
                recorder?.finish(
                    RunRecordStore.STATUS_ERROR,
                    metrics = metrics.snapshot()
                )
            }
            _messages.update { it + AgentMessage(AgentMessage.Role.ERROR, message) }
            answer = message
            ReplayManager.abort()
            outcome = RunOutcome.FAILED
        } finally {
            _streamingText.value = ""
            if (usedSkills.isNotEmpty() && outcome != RunOutcome.CANCELLED) {
                val success = outcome == RunOutcome.COMPLETED
                memoryScope.launch {
                    val store = SkillStore(app)
                    usedSkills.forEach { store.recordOutcome(it, success) }
                }
            }
            HeadlessMirrorWindow.hide()
            advanceAfterRun(app, onFinished, outcome, answer)
        }
    }

    private fun scheduleSkillDraft(
        app: Context,
        config: LlmConfig,
        goal: String,
        answer: String?,
        toolNames: List<String>,
        runStartedAt: Long
    ) {
        memoryScope.launch {
            val existing = runCatching { SkillStore(app).list() }.getOrDefault(emptyList())
            val draft = runCatching {
                SkillDrafter.draft(
                    context = app,
                    config = config,
                    goal = goal,
                    answer = answer.orEmpty(),
                    toolNames = toolNames,
                    existing = existing
                )
            }.getOrNull()
            if (draft != null) {
                AppSettingsEvents.notifyChanged()
                showPendingSkillCards(app, runStartedAt)
            }
        }
    }

    private fun scheduleMemoryExtraction(
        app: Context,
        config: LlmConfig?,
        command: String,
        steering: List<String>,
        contextSummary: String?,
        runStartedAt: Long
    ) {
        if (config?.isConfigured != true) return
        val utterances = listOf(command) + steering
        if (!MemoryExtractor.mightContainMemory(utterances.joinToString("\n"))) return
        memoryScope.launch {
            runCatching {
                MemoryExtractor.extract(
                    app,
                    config,
                    utterances,
                    contextSummary = contextSummary
                )
            }
            showPendingMemoryCards(app, runStartedAt)
        }
    }

    /** Persist the enable/ignore state on the inline skill card across tab switches. */
    fun resolveSkillCard(id: String, enabled: Boolean) {
        val decision = if (enabled) "enabled" else "ignored"
        _messages.update { current ->
            current.map {
                if (it.pendingSkillId == id && it.skillDecision == null) {
                    it.copy(skillDecision = decision)
                } else {
                    it
                }
            }
        }
    }

    /** Persist the confirm/ignore state on the inline card across tab switches. */
    fun resolveMemoryCard(id: String, approved: Boolean) {
        val decision = if (approved) "approved" else "ignored"
        _messages.update { current ->
            current.map {
                if (it.pendingMemoryId == id && it.memoryDecision == null) {
                    it.copy(memoryDecision = decision)
                } else {
                    it
                }
            }
        }
    }

    /**
     * Pending procedures are confirmed in the conversation, not in settings.
     * Add one card per pending memory created by this run; a memory id already
     * present in the transcript is never duplicated.
     */
    private fun showPendingMemoryCards(app: Context, runStartedAt: Long) {
        memoryScope.launch {
            val pending = runCatching {
                MemoryStore(app).list().filter {
                    !it.approved && it.createdAt >= runStartedAt
                }
            }.getOrDefault(emptyList())
            if (pending.isEmpty()) return@launch
            _messages.update { current ->
                val shown = current.mapNotNull { it.pendingMemoryId }.toSet()
                val fresh = pending.filter { it.id !in shown }
                if (fresh.isEmpty()) {
                    current
                } else {
                    current + fresh.map {
                        AgentMessage(
                            AgentMessage.Role.AGENT,
                            it.content,
                            pendingMemoryId = it.id
                        )
                    }
                }
            }
        }
    }

    /**
     * Newly saved skills are disabled by default. Offer to enable them right in
     * the conversation instead of sending the user back to settings.
     */
    private fun showPendingSkillCards(app: Context, runStartedAt: Long) {
        memoryScope.launch {
            val pending = runCatching {
                SkillStore(app).list().filter {
                    !it.builtIn && !it.enabled && it.createdAt >= runStartedAt
                }
            }.getOrDefault(emptyList())
            if (pending.isEmpty()) return@launch
            _messages.update { current ->
                val shown = current.mapNotNull { it.pendingSkillId }.toSet()
                val fresh = pending.filter { it.id !in shown }
                if (fresh.isEmpty()) {
                    current
                } else {
                    current + fresh.map {
                        AgentMessage(
                            AgentMessage.Role.AGENT,
                            "${it.name}：${it.description.ifBlank { it.body.take(240) }}",
                            pendingSkillId = it.id
                        )
                    }
                }
            }
        }
    }

    private fun advanceAfterRun(
        app: Context,
        onFinished: ((RunOutcome, String?) -> Unit)?,
        outcome: RunOutcome,
        answer: String?
    ) {
        val next: PendingTask?
        synchronized(stateLock) {
            currentJob = null
            // A message that arrived after the runtime's last drain still
            // deserves an answer: promote it to a follow-up run.
            val leftovers = drainSteeringLocked()
            if (leftovers.isNotEmpty()) {
                pendingTasks.addFirst(
                    PendingTask(app, leftovers.joinToString("\n\n"), null)
                )
            }
            next = pendingTasks.removeFirstOrNull()
            if (next == null) _busy.value = false
        }
        runCatching { onFinished?.invoke(outcome, answer) }
        if (next != null) startJob(next.context, next.text, next.onFinished)
    }

    private fun drainSteering(): List<String> = synchronized(stateLock) {
        drainSteeringLocked()
    }

    private fun drainSteeringLocked(): List<String> {
        if (steeringQueue.isEmpty()) return emptyList()
        val items = steeringQueue.toList()
        steeringQueue.clear()
        _queuedSteering.value = 0
        return items
    }

    /** Stop the current run. The run's stop summary is appended by [submit]. */
    fun cancel(): Boolean {
        val job = currentJob ?: return false
        val app = runContext
        synchronized(stateLock) {
            steeringQueue.clear()
            _queuedSteering.value = 0
        }
        _streamingText.value = ""
        job.cancel(CancellationException("user stopped"))
        // A tool can be blocked in a Binder call to a stalled container main
        // thread. Closing that slot makes the call fail fast instead of
        // leaving the stop button waiting for the full tool timeout.
        if (app != null) {
            memoryScope.launch {
                val slot = ContainerProcessPool.lastSlot()
                if (slot != null) ContainerProcessPool.closeSlot(app, slot)
                HeadlessMirrorWindow.hide()
            }
        }
        return true
    }

    fun append(message: AgentMessage) {
        _messages.update { it + message }
    }

    /**
     * Tell the user that runs from the previous process could not finish.
     * This is display-only: the old conversation turn is not replayed to the
     * model, so "continue" still needs the user to restate the goal.
     */
    fun noticeInterrupted(runs: List<RunSummary>) {
        if (runs.isEmpty()) return
        val text = buildString {
            append(
                AppLanguage.tr(
                    "上次有 ${runs.size} 个任务被系统中断，已经标记为中断。",
                    "${runs.size} task(s) from the previous session were interrupted " +
                        "and have been marked as interrupted."
                )
            )
            runs.take(3).forEach { run ->
                append("\n• ").append(run.command.take(120))
            }
            if (runs.size > 3) {
                append('\n')
                append(AppLanguage.tr("另有 ${runs.size - 3} 个任务", "${runs.size - 3} more task(s)"))
            }
            append('\n')
            append(
                AppLanguage.tr(
                    "详情可在助手的运行记录里查看；要继续请重新说明目标。",
                    "See run history for details; restate the goal if you want to continue."
                )
            )
        }
        append(AgentMessage(AgentMessage.Role.AGENT, text))
    }

    fun clear() {
        synchronized(stateLock) {
            history.clear()
            steeringQueue.clear()
            _queuedSteering.value = 0
        }
        LivePreview.clear()
        _lastRun.value = null
        _messages.value = emptyList()
    }

    fun resetToWelcome() {
        synchronized(stateLock) {
            history.clear()
            steeringQueue.clear()
            _queuedSteering.value = 0
        }
        LivePreview.clear()
        _lastRun.value = null
        _messages.value = listOf(welcome())
    }

    private fun recordHistory(
        user: String,
        assistant: String?,
        steering: List<String> = emptyList()
    ) {
        synchronized(stateLock) {
            val userBlocks = mutableListOf(ChatBlock.Text(user))
            steering.forEach { text ->
                userBlocks += ChatBlock.Text(
                    "\n" + AppLanguage.tr("用户补充：$text", "User follow-up: $text")
                )
            }
            history.add(ChatMessage(ChatRole.USER, userBlocks))
            if (!assistant.isNullOrBlank()) {
                history.add(ChatMessage(ChatRole.ASSISTANT, listOf(ChatBlock.Text(assistant))))
            }
            while (history.size > MAX_HISTORY_MESSAGES) history.removeAt(0)
        }
    }
}
