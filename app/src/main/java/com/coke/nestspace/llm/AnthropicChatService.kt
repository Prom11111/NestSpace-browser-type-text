package com.coke.nestspace.llm

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API adapter for the shared chat protocol.
 *
 * Wire format: POST /v1/messages, native `tools` / `tool_use` / `tool_result`.
 */
class AnthropicChatService(private val config: LlmConfig) : ChatService {

    override val protocol: LlmProtocol = LlmProtocol.ANTHROPIC

    override val supportsVision: Boolean
        get() = config.model.lowercase().let {
            it.contains("claude") || it.contains("fable") || it.contains("mythos") ||
                it.contains("deepseek-flash") || it.contains("deepseek-v4-flash")
        }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val response = LlmHttp.postJson(
            config.endpoint(),
            headers(),
            buildBody(request)
        )
        return parseResponse(response)
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onTextDelta: (String) -> Unit
    ): ChatResponse {
        val blocks = mutableListOf<ChatBlock>()
        var currentType: String? = null
        val currentText = StringBuilder()
        val currentThinking = StringBuilder()
        var currentSignature: String? = null
        var toolId = ""
        var toolName = ""
        val toolArgs = StringBuilder()
        var stopReason: String? = null
        var usage: ChatUsage? = null
        var sawEvent = false

        fun flushBlock() {
            when (currentType) {
                "text" -> if (currentText.isNotEmpty()) {
                    blocks += ChatBlock.Text(currentText.toString())
                }
                "thinking" -> if (currentThinking.isNotEmpty()) {
                    blocks += ChatBlock.Thinking(
                        currentThinking.toString(),
                        currentSignature
                    )
                }
                "tool_use" -> if (toolName.isNotBlank()) {
                    val input = runCatching {
                        if (toolArgs.isBlank()) JSONObject() else JSONObject(toolArgs.toString())
                    }.getOrDefault(JSONObject())
                    blocks += ChatBlock.ToolUse(
                        toolId.ifBlank { "tool-${blocks.size}" },
                        toolName,
                        input
                    )
                }
            }
            currentType = null
            currentText.clear()
            currentThinking.clear()
            currentSignature = null
            toolId = ""
            toolName = ""
            toolArgs.clear()
        }

        LlmHttp.postSse(
            config.endpoint(),
            headers(),
            buildBody(request).put("stream", true)
        ) { data ->
            val event = runCatching { JSONObject(data) }.getOrNull()
                ?: return@postSse true
            sawEvent = true
            when (event.optString("type")) {
                "message_start" -> {
                    event.optJSONObject("message")?.let { usage = parseUsage(it) }
                }

                "content_block_start" -> {
                    flushBlock()
                    val block = event.optJSONObject("content_block")
                        ?: return@postSse true
                    currentType = when (block.optString("type")) {
                        "text" -> "text"
                        "thinking" -> "thinking"
                        "tool_use" -> {
                            toolId = block.optString("id")
                            toolName = block.optString("name")
                            "tool_use"
                        }
                        else -> null
                    }
                }

                "content_block_delta" -> {
                    val delta = event.optJSONObject("delta")
                        ?: return@postSse true
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text")
                            if (text.isNotEmpty()) {
                                currentText.append(text)
                                onTextDelta(text)
                            }
                        }
                        "thinking_delta" -> currentThinking.append(delta.optString("thinking"))
                        "signature_delta" -> currentSignature = delta.optString("signature")
                        "input_json_delta" -> toolArgs.append(delta.optString("partial_json"))
                    }
                }

                "content_block_stop" -> flushBlock()

                "message_delta" -> {
                    event.optJSONObject("usage")?.let { raw ->
                        val base = usage ?: ChatUsage()
                        usage = base.copy(
                            outputTokens = raw.optInt("output_tokens", base.outputTokens)
                        )
                    }
                    event.optJSONObject("delta")
                        ?.optString("stop_reason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stopReason = it }
                }

                "error" -> {
                    val message = event.optJSONObject("error")
                        ?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: AppLanguage.tr("模型流返回错误", "The model stream returned an error")
                    throw LlmHttpException(message)
                }

                "message_stop" -> return@postSse false
            }
            true
        }
        flushBlock()
        if (!sawEvent) {
            throw LlmHttpException(
                AppLanguage.tr("模型接口没有返回流式数据", "The model endpoint returned no stream data")
            )
        }
        return ChatResponse(blocks, stopReason, usage)
    }

    internal fun headers(): Map<String, String> = mapOf(
        "x-api-key" to config.apiKey,
        "anthropic-version" to "2023-06-01"
    )

    internal fun buildBody(request: ChatRequest): JSONObject = JSONObject().apply {
        put("model", request.model)
        val answerTokens = request.maxTokens.coerceAtLeast(16)
        val thinkingBudget = thinkingBudget(request)
        val useThinking = thinkingBudget > 0
        // request.maxTokens is the visible answer budget. Providers that count
        // hidden reasoning against max_tokens need extra room, otherwise
        // thinking can consume the whole request and yield empty content.
        val reserve = if (useThinking) {
            thinkingBudget
        } else {
            implicitReasoningReserve(request)
        }
        val maxTokens = if (useThinking) {
            maxOf(answerTokens + reserve, thinkingBudget + 1024)
        } else {
            answerTokens + reserve
        }
        put("max_tokens", maxTokens.coerceAtLeast(16))
        if (useThinking) {
            // Extended thinking requires temperature=1 and budget_tokens < max_tokens.
            put(
                "thinking",
                JSONObject()
                    .put("type", "enabled")
                    .put("budget_tokens", thinkingBudget)
            )
            put("temperature", 1.0)
        } else {
            put("temperature", request.temperature.coerceIn(0.0, 1.0))
        }
        if (request.system.isNotBlank()) put("system", request.system)
        put("messages", messagesJson(request.messages))
        if (request.tools.isNotEmpty()) put("tools", toolsJson(request.tools))
    }

    private fun thinkingBudget(request: ChatRequest): Int {
        if (!supportsThinking(request.model)) return 0
        return when (request.reasoningEffort) {
            ReasoningEffort.OFF -> 0
            ReasoningEffort.LOW -> 1024
            ReasoningEffort.MEDIUM -> 4096
            ReasoningEffort.HIGH -> 8192
        }
    }

    private fun supportsThinking(model: String): Boolean {
        val name = model.lowercase()
        val claudeFamily = name.contains("claude") ||
            name.contains("fable") || name.contains("mythos")
        if (!claudeFamily) return false
        return name.contains("3-7") || name.contains("3.7") ||
            name.contains("sonnet-4") || name.contains("opus-4") ||
            name.contains("sonnet-5") || name.contains("opus-5") ||
            name.contains("fable") || name.contains("mythos")
    }

    /**
     * DeepSeek's Claude-compatible endpoint reasons by default and counts
     * those tokens in max_tokens even without a `thinking` block. Other
     * non-Claude models are assumed not to have hidden reasoning.
     */
    private fun implicitReasoningReserve(request: ChatRequest): Int {
        if (!request.model.lowercase().contains("deepseek")) return 0
        return request.reasoningEffort.reasoningReserveTokens
    }

    internal fun parseResponse(response: JSONObject): ChatResponse {
        val content = response.optJSONArray("content")
            ?: throw IllegalStateException(
                AppLanguage.tr(
                    "响应缺少 content：${response.toString().take(300)}",
                    "Response is missing content: ${response.toString().take(300)}"
                )
            )
        val blocks = mutableListOf<ChatBlock>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val text = block.optString("text")
                    if (text.isNotEmpty()) blocks.add(ChatBlock.Text(text))
                }

                "tool_use" -> {
                    val name = block.optString("name")
                    if (name.isNotBlank()) {
                        blocks.add(
                            ChatBlock.ToolUse(
                                id = block.optString("id").ifBlank { "tool-$i" },
                                name = name,
                                input = block.optJSONObject("input") ?: JSONObject()
                            )
                        )
                    }
                }

                "thinking" -> {
                    blocks.add(
                        ChatBlock.Thinking(
                            thinking = block.optString("thinking"),
                            signature = block.optString("signature").ifBlank { null }
                        )
                    )
                }

                "redacted_thinking" -> {
                    blocks.add(ChatBlock.RedactedThinking(block.optString("data")))
                }
            }
        }
        return ChatResponse(
            blocks,
            response.optString("stop_reason").ifBlank { null },
            parseUsage(response)
        )
    }

    private fun parseUsage(response: JSONObject): ChatUsage? {
        val usage = response.optJSONObject("usage") ?: return null
        val cacheRead = usage.optInt("cache_read_input_tokens", 0)
        val cacheWrite = usage.optInt("cache_creation_input_tokens", 0)
        val input = usage.optInt("input_tokens", 0) + cacheRead + cacheWrite
        val output = usage.optInt("output_tokens", 0)
        val details = usage.optJSONObject("output_tokens_details")
            ?: usage.optJSONObject("completion_tokens_details")
        val reasoning = details?.let {
            it.optInt("reasoning_tokens", it.optInt("thinking_tokens", 0))
        } ?: usage.optInt("reasoning_tokens", usage.optInt("thinking_tokens", 0))
        return ChatUsage(
            inputTokens = input,
            outputTokens = output,
            reasoningTokens = reasoning,
            cachedInputTokens = cacheRead
        )
    }

    private fun messagesJson(messages: List<ChatMessage>): JSONArray = JSONArray().apply {
        messages.forEach { message ->
            if (message.blocks.isEmpty()) return@forEach
            put(
                JSONObject()
                    .put(
                        "role",
                        when (message.role) {
                            ChatRole.USER -> "user"
                            ChatRole.ASSISTANT -> "assistant"
                        }
                    )
                    .put("content", contentJson(message.blocks))
            )
        }
    }

    private fun contentJson(blocks: List<ChatBlock>): JSONArray = JSONArray().apply {
        blocks.forEach { block ->
            when (block) {
                is ChatBlock.Text -> put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", block.text)
                )

                is ChatBlock.ToolUse -> put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", block.id)
                        .put("name", block.name)
                        .put("input", block.input)
                )

                is ChatBlock.ToolResult -> put(
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", block.toolUseId)
                        .put("content", block.content)
                        .put("is_error", block.isError)
                )

                is ChatBlock.Image -> put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", block.mimeType)
                                .put("data", block.data)
                        )
                )

                is ChatBlock.Thinking -> put(
                    JSONObject()
                        .put("type", "thinking")
                        .put("thinking", block.thinking)
                        .apply {
                            block.signature?.let { put("signature", it) }
                        }
                )

                is ChatBlock.RedactedThinking -> put(
                    JSONObject()
                        .put("type", "redacted_thinking")
                        .put("data", block.data)
                )
            }
        }
    }

    private fun toolsJson(tools: List<ChatTool>): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("input_schema", tool.inputSchema)
            )
        }
    }
}
