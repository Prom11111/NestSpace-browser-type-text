package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.data.ReasoningEffort
import org.json.JSONObject

/**
 * Shared chat protocol used by every model provider.
 *
 * This mirrors the provider-neutral model of go-agent-sdk's `chat` package:
 * a conversation is a list of [ChatMessage], each message is a list of typed
 * [ChatBlock]s, and providers only translate that model to/from their wire
 * format. The agent loop in [com.coke.nestspace.agent.LlmAgentRuntime] is
 * written once against this protocol; adding a provider does not touch it.
 *
 * Providers stream text deltas through [ChatService.streamChat] while still
 * assembling complete blocks, so the agent loop can execute tool calls and
 * persist history exactly as it does for a non-streamed response.
 */
enum class ChatRole { USER, ASSISTANT }

sealed class ChatBlock {
    data class Text(val text: String) : ChatBlock()

    data class ToolUse(
        val id: String,
        val name: String,
        val input: JSONObject
    ) : ChatBlock()

    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean
    ) : ChatBlock()

    /** A base64 screenshot attached to a user turn for vision-capable models. */
    data class Image(
        val data: String,
        val mimeType: String = "image/png"
    ) : ChatBlock()

    /** Anthropic extended-thinking block, carried across tool-use turns. */
    data class Thinking(
        val thinking: String,
        val signature: String?
    ) : ChatBlock()

    data class RedactedThinking(val data: String) : ChatBlock()
}

data class ChatMessage(
    val role: ChatRole,
    val blocks: List<ChatBlock>
) {
    val text: String
        get() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.text }
}

data class ChatTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ChatTool>,
    val maxTokens: Int,
    val temperature: Double,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    /** Short label used by token statistics: chat, retry, summary or probe. */
    val purpose: String = "chat"
)

/**
 * Provider-reported token usage for one request.
 *
 * [reasoningTokens] and [cachedInputTokens] are subsets of output/input
 * respectively, not extra tokens. They stay separate for cost visibility.
 * [estimated] is set when the endpoint returned no usage object and the
 * values were derived from text length.
 */
data class ChatUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val estimated: Boolean = false
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

data class ChatResponse(
    val blocks: List<ChatBlock>,
    val stopReason: String? = null,
    val usage: ChatUsage? = null
) {
    val text: String
        get() = blocks.filterIsInstance<ChatBlock.Text>().joinToString("") { it.text }

    val toolUses: List<ChatBlock.ToolUse>
        get() = blocks.filterIsInstance<ChatBlock.ToolUse>()
}

interface ChatService {
    val protocol: LlmProtocol

    /** Whether this provider/model can accept [ChatBlock.Image] blocks. */
    val supportsVision: Boolean get() = false

    suspend fun chat(request: ChatRequest): ChatResponse

    /**
     * Same request, but text deltas are delivered while the response is being
     * generated. The returned [ChatResponse] is still the complete response so
     * the agent loop can execute tool calls and persist history unchanged.
     */
    suspend fun streamChat(
        request: ChatRequest,
        onTextDelta: (String) -> Unit
    ): ChatResponse {
        val response = chat(request)
        if (response.text.isNotEmpty()) onTextDelta(response.text)
        return response
    }
}
