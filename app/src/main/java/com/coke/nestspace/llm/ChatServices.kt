package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol

/** Records one completed model request, including usage estimated locally. */
fun interface ChatUsageSink {
    fun record(usage: ChatUsage, purpose: String)
}

/** Maps the configured protocol to its shared-protocol adapter. */
object ChatServices {

    fun create(config: LlmConfig, usageSink: ChatUsageSink? = null): ChatService {
        val service = config.pluginProviderId()?.let {
            PluginChatService(config, it)
        } ?: when (config.protocol) {
                LlmProtocol.ANTHROPIC -> AnthropicChatService(config)
                LlmProtocol.OPENAI -> OpenAiChatService(config)
            }
        return if (usageSink == null) service else RecordingChatService(service, usageSink)
    }
}

/**
 * Counts every completed request at the transport boundary. Retries, final
 * summaries and probe requests all go through here, so token statistics do not
 * depend on the agent loop remembering to record them.
 */
private class RecordingChatService(
    private val delegate: ChatService,
    private val usageSink: ChatUsageSink
) : ChatService {

    override val protocol: LlmProtocol get() = delegate.protocol

    override val supportsVision: Boolean get() = delegate.supportsVision

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val response = delegate.chat(request)
        val reported = response.usage
        usageSink.record(
            reported ?: estimateUsage(request, response),
            request.purpose
        )
        return response
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onTextDelta: (String) -> Unit
    ): ChatResponse {
        val response = delegate.streamChat(request, onTextDelta)
        val reported = response.usage
        usageSink.record(
            reported ?: estimateUsage(request, response),
            request.purpose
        )
        return response
    }

    private fun estimateUsage(request: ChatRequest, response: ChatResponse): ChatUsage {
        val input = estimateText(request.system) +
            request.tools.sumOf { tool ->
                estimateText(tool.name) + estimateText(tool.description) +
                    estimateText(tool.inputSchema.toString())
            } +
            request.messages.sumOf { message ->
                message.blocks.sumOf { block ->
                    when (block) {
                        is ChatBlock.Text -> estimateText(block.text)
                        is ChatBlock.ToolUse ->
                            estimateText(block.name) + estimateText(block.id) +
                                estimateText(block.input.toString())
                        is ChatBlock.ToolResult -> estimateText(block.content)
                        is ChatBlock.Thinking -> estimateText(block.thinking)
                        is ChatBlock.RedactedThinking -> estimateText(block.data)
                        is ChatBlock.Image -> block.data.length / 4
                    } + 4
                }
            }
        val output = response.blocks.sumOf { block ->
            when (block) {
                is ChatBlock.Text -> estimateText(block.text)
                is ChatBlock.ToolUse ->
                    estimateText(block.name) + estimateText(block.input.toString())
                is ChatBlock.Thinking -> estimateText(block.thinking)
                is ChatBlock.RedactedThinking -> estimateText(block.data)
                is ChatBlock.ToolResult, is ChatBlock.Image -> 0
            }
        }
        return ChatUsage(
            inputTokens = input,
            outputTokens = output,
            estimated = true
        )
    }

    private fun estimateText(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            if (isWide(ch)) wide++ else narrow++
        }
        return wide + (narrow + 3) / 4 + 1
    }

    private fun isWide(ch: Char): Boolean =
        ch.code in 0x2E80..0x9FFF ||
            ch.code in 0xAC00..0xD7AF ||
            ch.code in 0xF900..0xFAFF ||
            ch.code in 0xFF00..0xFFEF
}
