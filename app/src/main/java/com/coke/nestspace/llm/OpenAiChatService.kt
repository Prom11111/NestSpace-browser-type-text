package com.coke.nestspace.llm

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Chat Completions adapter for the shared chat protocol.
 *
 * Wire format: POST /v1/chat/completions, native `tool_calls` and role=`tool`
 * messages. The shared assistant tool_use blocks map to `tool_calls`; shared
 * tool_result blocks map to individual `tool` messages.
 */
class OpenAiChatService(private val config: LlmConfig) : ChatService {

    override val protocol: LlmProtocol = LlmProtocol.OPENAI

    override val supportsVision: Boolean
        get() = config.model.lowercase().let { name ->
            listOf(
                "gpt-4o", "gpt-4.1", "gpt-5", "gpt-6", "o1", "o3", "o4",
                "qwen-vl", "glm-4v", "doubao", "vision",
                "deepseek-flash", "deepseek-v4-flash"
            ).any { name.contains(it) }
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
        val text = StringBuilder()
        val tools = sortedMapOf<Int, ToolBuffer>()
        var stopReason: String? = null
        var usage: ChatUsage? = null
        var sawEvent = false

        LlmHttp.postSse(
            config.endpoint(),
            headers(),
            buildBody(request).put("stream", true)
        ) { data ->
            if (data == "[DONE]") return@postSse false
            val chunk = runCatching { JSONObject(data) }.getOrNull()
                ?: return@postSse true
            sawEvent = true
            chunk.optJSONObject("usage")?.let { usage = parseUsage(chunk) }
            val choice = chunk.optJSONArray("choices")?.optJSONObject(0)
                ?: return@postSse true
            choice.optString("finish_reason")
                .takeIf { it.isNotBlank() }
                ?.let { stopReason = it }
            val delta = choice.optJSONObject("delta") ?: return@postSse true
            when (val content = delta.opt("content")) {
                is String -> if (content.isNotEmpty()) {
                    text.append(content)
                    onTextDelta(content)
                }
                is JSONArray -> {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        val value = part.optString("text")
                        if (value.isNotEmpty()) {
                            text.append(value)
                            onTextDelta(value)
                        }
                    }
                }
            }
            val calls = delta.optJSONArray("tool_calls") ?: return@postSse true
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val index = call.optInt("index", i)
                val buffer = tools.getOrPut(index) { ToolBuffer() }
                call.optString("id").takeIf { it.isNotBlank() }?.let { buffer.id = it }
                val function = call.optJSONObject("function") ?: continue
                function.optString("name").takeIf { it.isNotBlank() }?.let { buffer.name = it }
                function.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                    buffer.arguments.append(it)
                }
            }
            true
        }
        if (!sawEvent) {
            throw LlmHttpException(
                AppLanguage.tr("模型接口没有返回流式数据", "The model endpoint returned no stream data")
            )
        }
        val blocks = mutableListOf<ChatBlock>()
        if (text.isNotEmpty()) blocks += ChatBlock.Text(text.toString())
        tools.values.forEachIndexed { fallbackIndex, buffer ->
            if (buffer.name.isBlank()) return@forEachIndexed
            blocks += ChatBlock.ToolUse(
                id = buffer.id.ifBlank { "call-$fallbackIndex" },
                name = buffer.name,
                input = runCatching {
                    if (buffer.arguments.isBlank()) {
                        JSONObject()
                    } else {
                        JSONObject(buffer.arguments.toString())
                    }
                }.getOrDefault(JSONObject())
            )
        }
        return ChatResponse(blocks, stopReason, usage)
    }

    private class ToolBuffer {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    internal fun headers(): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${config.apiKey}"
    )

    internal fun buildBody(request: ChatRequest): JSONObject = JSONObject().apply {
        put("model", request.model)
        val answerTokens = request.maxTokens.coerceAtLeast(16)
        val reasoning = supportsReasoning(request.model)
        if (reasoning) {
            // o-series/GPT-5 reject max_tokens and use max_completion_tokens.
            put(
                "max_completion_tokens",
                answerTokens + request.reasoningEffort.reasoningReserveTokens
            )
            put("reasoning_effort", reasoningValue(request.model, request.reasoningEffort))
        } else {
            // DeepSeek's OpenAI-compatible endpoint also bills hidden
            // reasoning against max_tokens.
            val reserve = if (request.model.lowercase().contains("deepseek")) {
                request.reasoningEffort.reasoningReserveTokens
            } else {
                0
            }
            put("max_tokens", answerTokens + reserve)
            put("temperature", request.temperature.coerceIn(0.0, 2.0))
        }
        put("messages", messagesJson(request))
        if (request.tools.isNotEmpty()) {
            put("tools", toolsJson(request.tools))
            put("tool_choice", "auto")
        }
    }

    private fun supportsReasoning(model: String): Boolean {
        val name = model.lowercase()
        return listOf("o1", "o3", "o4", "gpt-5", "gpt-6", "codex").any {
            name.contains(it)
        }
    }

    private fun reasoningValue(model: String, effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.OFF -> if (
            model.contains("gpt-5", ignoreCase = true) ||
            model.contains("gpt-6", ignoreCase = true)
        ) {
            "minimal"
        } else {
            "low"
        }
        ReasoningEffort.LOW -> "low"
        ReasoningEffort.MEDIUM -> "medium"
        ReasoningEffort.HIGH -> "high"
    }

    internal fun parseResponse(response: JSONObject): ChatResponse {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException(
                AppLanguage.tr(
                    "响应缺少 choices：${response.toString().take(300)}",
                    "Response is missing choices: ${response.toString().take(300)}"
                )
            )
        val message = choice.optJSONObject("message")
            ?: throw IllegalStateException(AppLanguage.tr("响应缺少 message", "Response is missing message"))

        val blocks = mutableListOf<ChatBlock>()
        when (val content = message.opt("content")) {
            is String -> if (content.isNotBlank()) blocks.add(ChatBlock.Text(content))
            is JSONArray -> {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    val text = part.optString("text")
                    if (text.isNotBlank()) blocks.add(ChatBlock.Text(text))
                }
            }
        }

        val calls = message.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank()) continue
                blocks.add(
                    ChatBlock.ToolUse(
                        id = call.optString("id").ifBlank { "call-$i" },
                        name = name,
                        input = parseArguments(function.opt("arguments"))
                    )
                )
            }
        }

        return ChatResponse(
            blocks = blocks,
            stopReason = choice.optString("finish_reason").ifBlank { null },
            usage = parseUsage(response)
        )
    }

    private fun parseUsage(response: JSONObject): ChatUsage? {
        val usage = response.optJSONObject("usage") ?: return null
        return ChatUsage(
            inputTokens = usage.optInt("prompt_tokens", 0),
            outputTokens = usage.optInt("completion_tokens", 0),
            reasoningTokens = usage.optJSONObject("completion_tokens_details")
                ?.optInt("reasoning_tokens", 0) ?: 0,
            cachedInputTokens = usage.optJSONObject("prompt_tokens_details")
                ?.optInt("cached_tokens", 0) ?: 0
        )
    }

    private fun messagesJson(request: ChatRequest): JSONArray = JSONArray().apply {
        if (request.system.isNotBlank()) {
            put(JSONObject().put("role", "system").put("content", request.system))
        }
        request.messages.forEach { message ->
            when (message.role) {
                ChatRole.USER -> {
                    val toolResults = message.blocks.filterIsInstance<ChatBlock.ToolResult>()
                    if (toolResults.isNotEmpty()) {
                        // OpenAI represents each tool result as its own `tool` message.
                        toolResults.forEach { result ->
                            put(
                                JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", result.toolUseId)
                                    .put("content", result.content)
                            )
                        }
                        // A steering note may be merged into this same user
                        // turn after its tool_result blocks.
                        putUserContent(message, skipText = false)
                    } else {
                        putUserContent(message)
                    }
                }

                ChatRole.ASSISTANT -> {
                    val text = message.text
                    val toolUses = message.blocks.filterIsInstance<ChatBlock.ToolUse>()
                    if (text.isBlank() && toolUses.isEmpty()) return@forEach
                    put(
                        JSONObject()
                            .put("role", "assistant")
                            .put(
                                "content",
                                if (text.isBlank()) JSONObject.NULL else text
                            )
                            .apply {
                                if (toolUses.isNotEmpty()) put("tool_calls", toolCallsJson(toolUses))
                            }
                    )
                }
            }
        }
    }

    private fun JSONArray.putUserContent(message: ChatMessage, skipText: Boolean = false) {
        val images = message.blocks.filterIsInstance<ChatBlock.Image>()
        val text = if (skipText) "" else message.text
        if (images.isEmpty()) {
            if (text.isNotBlank()) {
                put(JSONObject().put("role", "user").put("content", text))
            }
            return
        }
        val content = JSONArray()
        if (text.isNotBlank()) {
            content.put(JSONObject().put("type", "text").put("text", text))
        }
        images.forEach { image ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:${image.mimeType};base64,${image.data}"
                        )
                    )
            )
        }
        put(JSONObject().put("role", "user").put("content", content))
    }

    private fun toolCallsJson(toolUses: List<ChatBlock.ToolUse>): JSONArray = JSONArray().apply {
        toolUses.forEach { use ->
            put(
                JSONObject()
                    .put("id", use.id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", use.name)
                            .put("arguments", use.input.toString())
                    )
            )
        }
    }

    private fun toolsJson(tools: List<ChatTool>): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.inputSchema)
                    )
            )
        }
    }

    private fun parseArguments(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }
}
