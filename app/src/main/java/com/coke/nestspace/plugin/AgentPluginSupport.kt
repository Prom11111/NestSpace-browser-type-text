package com.coke.nestspace.plugin

import android.content.Context
import android.util.Log
import android.view.View
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import com.coke.nestspace.tools.ToolResult
import com.coke.nestspace.tools.ToolSpec
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

internal data class AgentPluginAnswer(
    val pluginName: String,
    val text: String
)

internal sealed interface AgentModelHookOutcome {
    data class Proceed(val systemPrompt: String) : AgentModelHookOutcome
    data class Answer(val text: String) : AgentModelHookOutcome
    data class Blocked(val message: String) : AgentModelHookOutcome
}

internal data class AgentToolGuardOutcome(
    val arguments: JSONObject,
    val blockedMessage: String?
)

/**
 * Java-first agent hooks adapted to the internal coroutine-based runtime.
 */
internal object AgentPluginSupport {
    const val TOOL_DISPLAY_MARKER = "@@nestspace_tool_display_v1"
    private const val TAG = "AgentPluginSupport"
    private const val ANSWER_TIMEOUT_MS = 1_000L
    private const val TOOL_LIST_TIMEOUT_MS = 2_000L
    private const val MAX_TOOL_VIEWS = 128

    private data class ToolBinding(
        val pluginId: String,
        val provider: AgentToolProvider,
        val spec: PluginToolSpec,
        val qualifiedName: String
    )

    private data class CommandBinding(
        val pluginId: String,
        val provider: PluginCommandProvider,
        val spec: PluginCommandSpec,
        val qualifiedName: String
    )

    private val toolBindings = ConcurrentHashMap<String, ToolBinding>()
    private val commandBindings = ConcurrentHashMap<String, CommandBinding>()
    private val viewBindings = ConcurrentHashMap<String, ToolViewBinding>()
    private val viewSequence = AtomicLong()
    private val toolCacheLock = Any()

    @Volatile
    private var cachedToolRevision = -1L to -1L

    @Volatile
    private var cachedToolSpecs: List<ToolSpec> = emptyList()

    private data class ToolViewBinding(
        val factory: AgentToolViewFactory,
        val toolName: String,
        val argumentsJson: String,
        val resultText: String,
        val successful: Boolean,
        val errorCode: String,
        val createdAt: Long
    )

    @Volatile
    private var appContext: Context? = null

    fun installBuiltins(context: Context) {
        appContext = context.applicationContext
        if (PluginCenterStore.isEnabled(
                context,
                PluginCenterStore.INSTALLATION_GUIDE
            )
        ) {
            AgentPlugins.install(InstallationHelpPlugin)
        } else {
            AgentPlugins.uninstall(PluginCenterStore.INSTALLATION_GUIDE)
        }
    }

    fun toolSpecs(): List<ToolSpec> {
        val revision = AgentToolPlugins.revision() to
            PluginCommandPlugins.revision()
        synchronized(toolCacheLock) {
            if (revision == cachedToolRevision) return cachedToolSpecs
        }
        toolBindings.clear()
        commandBindings.clear()
        val result = mutableListOf<ToolSpec>()
        result += AgentToolPlugins.snapshot().flatMap { provider ->
            val specs = runCatching {
                provider.listToolsAsync().get(
                    TOOL_LIST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "plugin tool list failed: ${provider.descriptor.id}",
                    it
                )
            }.getOrDefault(emptyList())
            specs.mapNotNull { spec ->
                val schema = runCatching { JSONObject(spec.inputSchemaJson) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "invalid plugin tool schema: " +
                                "${provider.descriptor.id}/${spec.id}",
                            it
                        )
                    }
                    .getOrNull() ?: return@mapNotNull null
                val name = AgentToolPlugins.qualify(
                    provider.descriptor.id,
                    spec.id
                )
                val binding = ToolBinding(
                    provider.descriptor.id,
                    provider,
                    spec,
                    name
                )
                toolBindings[name] = binding
                ToolSpec(
                    name = name,
                    description = "[Plugin: ${provider.descriptor.name}] " +
                        spec.title + "\n" + spec.description,
                    inputSchema = schema,
                    mutating = spec.isMutating
                ) { args, context ->
                    executePluginTool(binding, args, context)
                }
            }
        }
        result += PluginCommandPlugins.snapshot().flatMap { provider ->
            val specs = runCatching {
                provider.listCommandsAsync().get(
                    TOOL_LIST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "plugin command list failed: ${provider.descriptor.id}",
                    it
                )
            }.getOrDefault(emptyList())
            specs.mapNotNull { spec ->
                val schema = runCatching {
                    JSONObject(spec.argumentsSchemaJson)
                }.getOrNull() ?: return@mapNotNull null
                val name = AgentToolPlugins.qualify(
                    "command:" + provider.descriptor.id,
                    spec.id
                )
                val binding = CommandBinding(
                    provider.descriptor.id,
                    provider,
                    spec,
                    name
                )
                commandBindings[name] = binding
                ToolSpec(
                    name = name,
                    description = "[Plugin command: " +
                        "${provider.descriptor.name}] ${spec.title}\n" +
                        spec.description,
                    inputSchema = schema,
                    mutating = spec.isMutating
                ) { args, context ->
                    executePluginCommand(binding, args, context)
                }
            }
        }
        synchronized(toolCacheLock) {
            cachedToolSpecs = result
            cachedToolRevision = revision
        }
        return result
    }

    fun beforeToolCall(
        toolName: String,
        arguments: JSONObject,
        context: ToolContext
    ): AgentToolGuardOutcome {
        var current = arguments
        val hooks = AgentToolHookPlugins.snapshot().filter {
            PluginCapability.AGENT_TOOL_HOOKS in it.descriptor.capabilities
        }
        for (hook in hooks) {
            val decision = runCatching {
                hook.beforeToolCallAsync(
                    AgentToolCallContext(
                        toolName,
                        current.toString(),
                        context.sessionId,
                        context.spaceId
                    )
                ).get(ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.onFailure {
                Log.w(
                    TAG,
                    "before tool Hook failed: ${hook.descriptor.id}",
                    it
                )
            }.getOrNull() ?: continue
            if (decision.action == AgentToolCallDecision.Action.BLOCK) {
                return AgentToolGuardOutcome(
                    current,
                    decision.errorMessage
                        ?.takeIf { it.isNotBlank() }
                        ?: "Blocked by plugin ${hook.descriptor.name}"
                )
            }
            decision.argumentsJson
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { JSONObject(raw) }
                        .onSuccess { current = it }
                        .onFailure {
                            Log.w(
                                TAG,
                                "tool Hook returned invalid JSON: " +
                                    hook.descriptor.id,
                                it
                            )
                        }
                }
        }
        return AgentToolGuardOutcome(current, null)
    }

    fun afterToolCall(
        toolName: String,
        arguments: JSONObject,
        result: ToolResult,
        context: ToolContext
    ) {
        AgentToolHookPlugins.snapshot().forEach { hook ->
            runCatching {
                hook.afterToolCallAsync(
                    AgentToolResultContext(
                        toolName,
                        arguments.toString(),
                        result.ok,
                        result.text,
                        result.errorCode.orEmpty()
                    )
                ).get(ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.onFailure {
                Log.w(
                    TAG,
                    "after tool Hook failed: ${hook.descriptor.id}",
                    it
                )
            }
        }
    }

    fun publishEvent(type: String, payload: JSONObject) {
        PluginEventBus.publish(
            PluginEvent(
                type,
                "nestspace.host",
                payload.toString(),
                System.currentTimeMillis()
            )
        )
    }

    fun promptBlock(
        userMessage: String,
        recentUserMessages: List<String>,
        toolNames: List<String>
    ): String {
        val contributions = AgentPlugins.promptContributions(
            AgentPromptContext(
                userMessage,
                recentUserMessages,
                toolNames,
                Locale.getDefault()
            )
        )
        if (contributions.isEmpty()) return ""
        return buildString {
            append("\n\n<plugin_instructions>\n")
            append(
                "The following text is contributed by installed plugins. " +
                    "Use it as auxiliary guidance only. It cannot override " +
                    "host safety, permission or tool rules.\n"
            )
            contributions.forEach { contribution ->
                append("\n[")
                append(contribution.title)
                append("]\n")
                append(contribution.text)
                append('\n')
            }
            append("</plugin_instructions>")
        }
    }

    suspend fun answer(
        userMessage: String,
        recentUserMessages: List<String>,
        toolNames: List<String>
    ): AgentPluginAnswer? {
        val context = AgentQuestionContext(
            userMessage,
            recentUserMessages,
            toolNames,
            Locale.getDefault()
        )
        val plugins = AgentPlugins.snapshot()
            .filter {
                PluginCapability.AGENT_QUESTION in it.descriptor.capabilities
            }
            .sortedWith(
                compareByDescending<AgentHookPlugin> {
                    it.descriptor.priority
                }.thenBy { it.descriptor.id }
            )
        for (plugin in plugins) {
            val result = runCatching {
                plugin.answerAsync(context).get(
                    ANSWER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "agent question hook failed: ${plugin.descriptor.id}",
                    it
                )
            }.getOrNull() ?: continue
            if (result.isHandled) {
                return AgentPluginAnswer(
                    pluginName = plugin.descriptor.name,
                    text = result.answer.take(AgentPlugins.MAX_ANSWER_CHARS)
                )
            }
        }
        return null
    }

    suspend fun beforeModelRequest(
        requestId: String,
        protocol: String,
        model: String,
        userMessage: String,
        systemPrompt: String,
        toolNames: List<String>,
        round: Int
    ): AgentModelHookOutcome {
        var currentPrompt = systemPrompt
        val plugins = AgentModelPlugins.snapshot().filter {
            PluginCapability.AGENT_MODEL_REQUEST in it.descriptor.capabilities
        }
        for (plugin in plugins) {
            val context = AgentModelRequestContext(
                requestId,
                protocol,
                model,
                userMessage,
                currentPrompt,
                toolNames,
                round
            )
            val decision = runCatching {
                plugin.beforeRequestAsync(context).get(
                    ANSWER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "agent model hook failed: ${plugin.descriptor.id}",
                    it
                )
            }.getOrNull() ?: continue
            when (decision.action) {
                AgentModelRequestAction.PROCEED -> {
                    decision.systemPrompt
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            currentPrompt = it.take(
                                AgentModelPlugins.MAX_SYSTEM_PROMPT_CHARS
                            )
                        }
                }
                AgentModelRequestAction.SHORT_CIRCUIT -> {
                    return AgentModelHookOutcome.Answer(
                        decision.answer.orEmpty().take(
                            AgentModelPlugins.MAX_ANSWER_CHARS
                        )
                    )
                }
                AgentModelRequestAction.BLOCK -> {
                    return AgentModelHookOutcome.Blocked(
                        decision.errorMessage.orEmpty().take(
                            AgentModelPlugins.MAX_BLOCK_MESSAGE_CHARS
                        )
                    )
                }
            }
        }
        return AgentModelHookOutcome.Proceed(currentPrompt)
    }

    suspend fun presentTool(
        toolName: String,
        arguments: JSONObject,
        resultText: String,
        successful: Boolean,
        errorCode: String?
    ): AgentToolPresentation? {
        val context = AgentToolPresentationContext(
            toolName,
            arguments.toString(),
            resultText,
            successful,
            errorCode
        )
        val plugins = AgentToolPresentationPlugins.snapshot().filter {
            PluginCapability.AGENT_TOOL_PRESENTATION in
                it.descriptor.capabilities
        }
        for (plugin in plugins) {
            val presentation = runCatching {
                plugin.presentAsync(context).get(
                    ANSWER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            }.onFailure {
                Log.w(
                    TAG,
                    "tool presentation hook failed: ${plugin.descriptor.id}",
                    it
                )
            }.getOrNull()
            if (presentation != null) return presentation
        }
        return null
    }

    fun formatToolMessage(
        toolName: String,
        status: String,
        rawText: String,
        presentation: AgentToolPresentation?,
        arguments: JSONObject,
        successful: Boolean,
        errorCode: String?
    ): String {
        if (presentation == null) return "$toolName -> $status\n$rawText"
        val viewKey = presentation.viewFactory?.let { factory ->
            registerToolView(
                factory,
                toolName,
                presentation,
                argumentsJson = arguments.toString(),
                successful = successful,
                errorCode = errorCode.orEmpty()
            )
        }.orEmpty()
        val json = JSONObject()
            .put("title", presentation.title)
            .put("summary", presentation.summary)
            .put("detail", presentation.detail)
            .put("status", presentation.statusLabel)
            .put("renderer", presentation.rendererId)
            .apply {
                if (viewKey.isNotBlank()) put("view_key", viewKey)
            }
        return "$toolName -> $status\n$TOOL_DISPLAY_MARKER ${json}\n$rawText"
    }

    fun createToolView(key: String, context: Context): View? {
        val binding = viewBindings[key] ?: return null
        return runCatching {
            binding.factory.createView(
                PluginToolViewContext(
                    context,
                    binding.toolName,
                    binding.argumentsJson,
                    binding.resultText,
                    binding.successful,
                    binding.errorCode
                )
            )
        }.onFailure {
            Log.w(TAG, "custom tool View factory failed: $key", it)
        }.getOrNull()
    }

    private fun registerToolView(
        factory: AgentToolViewFactory,
        toolName: String,
        presentation: AgentToolPresentation,
        argumentsJson: String,
        successful: Boolean,
        errorCode: String
    ): String {
        if (viewBindings.size >= MAX_TOOL_VIEWS) {
            viewBindings.entries
                .minByOrNull { it.value.createdAt }
                ?.let { viewBindings.remove(it.key) }
        }
        val key = "tool_view_${viewSequence.incrementAndGet()}"
        viewBindings[key] = ToolViewBinding(
            factory,
            toolName,
            argumentsJson,
            presentation.detail,
            successful,
            errorCode,
            System.currentTimeMillis()
        )
        return key
    }

    private suspend fun executePluginTool(
        binding: ToolBinding,
        arguments: JSONObject,
        context: ToolContext
    ): ToolResult {
        if (AgentToolPlugins.snapshot().none {
                it.descriptor.id == binding.pluginId
            }
        ) {
            return ToolResult(
                false,
                "Plugin tool is disabled",
                errorCode = "PLUGIN_DISABLED"
            )
        }
        if (binding.spec.isMutating &&
            context.caller != Caller.USER &&
            ToolPermissionSettings.shouldConfirm(
                requireNotNull(appContext) {
                    "Plugin platform is not initialized"
                },
                ToolConfirmationCategory.PLUGIN
            )
        ) {
            val allowed = UserGateManager.askConfirmation(
                title = AppLanguage.tr("调用插件工具？", "Call plugin tool?"),
                detail = AppLanguage.tr(
                    "${binding.spec.title}\n${binding.spec.description}",
                    "${binding.spec.title}\n${binding.spec.description}"
                )
            )
            if (!allowed) {
                return ToolResult(
                    false,
                    "User denied the plugin tool call",
                    errorCode = "USER_DENIED"
                )
            }
        }
        val result = runCatching {
            binding.provider.invokeAsync(
                PluginToolCall(
                    binding.spec.id,
                    arguments.toString(),
                    context.sessionId,
                    context.spaceId
                )
            ).get(
                binding.spec.timeoutMs.toLong(),
                TimeUnit.MILLISECONDS
            )
        }.getOrElse {
            return ToolResult(
                false,
                "Plugin tool ${binding.qualifiedName} failed: " +
                    (it.message ?: it.javaClass.simpleName),
                errorCode = "PLUGIN_TOOL_ERROR"
            )
        }
        return ToolResult(
            result.isSuccessful,
            result.text,
            imageBase64 = result.imageBase64.takeIf { it.isNotBlank() },
            errorCode = result.errorCode.takeIf { it.isNotBlank() }
                ?: if (result.isSuccessful) null else "PLUGIN_TOOL_ERROR",
            imageMimeType = result.imageMimeType.ifBlank { "image/png" }
        )
    }

    private suspend fun executePluginCommand(
        binding: CommandBinding,
        arguments: JSONObject,
        context: ToolContext
    ): ToolResult {
        if (PluginCommandPlugins.snapshot().none {
                it.descriptor.id == binding.pluginId
            }
        ) {
            return ToolResult(
                false,
                "Plugin command is disabled",
                errorCode = "PLUGIN_DISABLED"
            )
        }
        if (binding.spec.isMutating &&
            context.caller != Caller.USER &&
            ToolPermissionSettings.shouldConfirm(
                requireNotNull(appContext) {
                    "Plugin platform is not initialized"
                },
                ToolConfirmationCategory.PLUGIN
            )
        ) {
            val allowed = UserGateManager.askConfirmation(
                title = AppLanguage.tr("执行插件命令？", "Run plugin command?"),
                detail = AppLanguage.tr(
                    "${binding.spec.title}\n${binding.spec.description}",
                    "${binding.spec.title}\n${binding.spec.description}"
                )
            )
            if (!allowed) {
                return ToolResult(
                    false,
                    "User denied the plugin command",
                    errorCode = "USER_DENIED"
                )
            }
        }
        val result = runCatching {
            binding.provider.invokeCommandAsync(
                PluginCommandCall(
                    binding.spec.id,
                    arguments.toString(),
                    context.sessionId,
                    context.spaceId
                )
            ).get(binding.spec.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        }.getOrElse {
            return ToolResult(
                false,
                "Plugin command ${binding.qualifiedName} failed: " +
                    (it.message ?: it.javaClass.simpleName),
                errorCode = "PLUGIN_COMMAND_ERROR"
            )
        }
        return ToolResult(
            result.isSuccessful,
            result.message.ifBlank { result.dataJson },
            errorCode = result.errorCode.takeIf { it.isNotBlank() }
                ?: if (result.isSuccessful) null else "PLUGIN_COMMAND_ERROR"
        )
    }

}

internal object InstallationHelpPlugin : AgentHookPlugin {
    private val descriptor = PluginDescriptor(
        "builtin.agent.installation-guide",
        "Installation guide",
        "1",
        PluginDescriptor.API_VERSION,
        10,
        setOf(
            PluginCapability.AGENT_PROMPT,
            PluginCapability.AGENT_QUESTION
        )
    )

    override fun getDescriptor(): PluginDescriptor = descriptor

    override fun promptContributions(
        context: AgentPromptContext
    ): List<AgentPromptContribution> =
        listOf(
            AgentPromptContribution(
                "installation-guidance",
                "Installation guidance",
                "When the user asks how to install or import an app, explain " +
                    "Space settings > App management > Import app or Import APK. " +
                    "Do not claim installation succeeded until a tool confirms it.",
                10
            )
        )

    override fun answerAsync(
        context: AgentQuestionContext
    ): CompletableFuture<AgentQuestionResult> {
        val question = context.userMessage.trim()
        if (question.length > 120) {
            return CompletableFuture.completedFuture(
                AgentQuestionResult.notHandled()
            )
        }
        val normalized = question.lowercase(Locale.ROOT)
        val asksInstallation = listOf(
            "怎么安装",
            "如何安装",
            "安装应用",
            "安装 apk",
            "导入应用",
            "how to install",
            "install app",
            "install apk"
        ).any(normalized::contains)
        if (!asksInstallation) {
            return CompletableFuture.completedFuture(
                AgentQuestionResult.notHandled()
            )
        }
        val answer = if (AppLanguage.isChinese()) {
            "在 NestSpace 中可以这样安装：\n" +
                "1. 打开「空间设置 → 应用管理」。\n" +
                "2. 点「导入应用」安装平板已安装的应用，或点「导入 APK」选择文件。\n" +
                "3. Split APK 需要一次选中 base APK 和全部 split。\n" +
                "4. 安装完成后从应用列表启动，需要多账号时可点「多开」。"
        } else {
            "To install an app in NestSpace:\n" +
                "1. Open Space settings > App management.\n" +
                "2. Choose Import app for an installed device app, or " +
                "Import APK to select files.\n" +
                "3. For split APKs, select the base APK and every split together.\n" +
                "4. Launch it from the app list; use Multi-open for another instance."
        }
        return CompletableFuture.completedFuture(
            AgentQuestionResult.handled(answer)
        )
    }
}
