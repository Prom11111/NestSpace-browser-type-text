package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.plugin.PluginLlmListener
import com.coke.nestspace.plugin.PluginLlmModel
import com.coke.nestspace.plugin.PluginLlmProvider
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginLlmRequest
import com.coke.nestspace.plugin.PluginLlmResponse
import com.coke.nestspace.plugin.PluginLlmStream
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapts a Java plugin LLM provider to the provider-neutral ChatService.
 */
internal class PluginChatService(
    private val config: LlmConfig,
    private val providerId: String
) : ChatService {

    private val provider: PluginLlmProvider
        get() = PluginLlmProviders.snapshot()
            .firstOrNull { it.providerId == providerId }
            ?: error("Plugin LLM provider is not available: $providerId")

    private val model: PluginLlmModel?
        get() = provider.models.firstOrNull { it.id == config.model }

    override val protocol: LlmProtocol = config.protocol

    override val supportsVision: Boolean
        get() = model?.supportsVision() == true

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val response = provider.completeAsync(toPluginRequest(request))
            .awaitCancellable()
        return response.toChatResponse()
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onTextDelta: (String) -> Unit
    ): ChatResponse {
        val completion = CompletableFuture<PluginLlmResponse>()
        val stream = AtomicReference<PluginLlmStream?>()
        return suspendCancellableCoroutine { continuation ->
            try {
                val active = provider.stream(
                    toPluginRequest(request),
                    object : PluginLlmListener {
                        override fun onTextDelta(text: String) {
                            if (text.isNotEmpty()) onTextDelta(text)
                        }

                        override fun onComplete(
                            response: PluginLlmResponse
                        ) {
                            completion.complete(response)
                        }

                        override fun onFailure(error: Throwable) {
                            completion.completeExceptionally(error)
                        }
                    }
                )
                stream.set(active)
                continuation.invokeOnCancellation {
                    runCatching { active.close() }
                }
            } catch (error: Throwable) {
                completion.completeExceptionally(error)
            }
            completion.whenComplete { response, error ->
                if (continuation.isActive) {
                    if (error != null) {
                        continuation.resumeWithException(error)
                    } else {
                        continuation.resume(response.toChatResponse())
                    }
                }
            }
        }
    }

    private fun toPluginRequest(request: ChatRequest): PluginLlmRequest {
        val messages = JSONArray()
        request.messages.forEach { message ->
            val blocks = JSONArray()
            message.blocks.forEach { block ->
                blocks.put(
                    when (block) {
                        is ChatBlock.Text -> JSONObject()
                            .put("type", "text")
                            .put("text", block.text)
                        is ChatBlock.ToolUse -> JSONObject()
                            .put("type", "tool_use")
                            .put("id", block.id)
                            .put("name", block.name)
                            .put("arguments", block.input)
                        is ChatBlock.ToolResult -> JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.toolUseId)
                            .put("content", block.content)
                            .put("is_error", block.isError)
                        is ChatBlock.Image -> JSONObject()
                            .put("type", "image")
                            .put("data", block.data)
                            .put("mime_type", block.mimeType)
                        is ChatBlock.Thinking -> JSONObject()
                            .put("type", "thinking")
                            .put("thinking", block.thinking)
                            .put("signature", block.signature ?: JSONObject.NULL)
                        is ChatBlock.RedactedThinking -> JSONObject()
                            .put("type", "redacted_thinking")
                            .put("data", block.data)
                    }
                )
            }
            messages.put(
                JSONObject()
                    .put(
                        "role",
                        message.role.name.lowercase()
                    )
                    .put("blocks", blocks)
            )
        }
        val tools = JSONArray()
        request.tools.forEach { tool ->
            tools.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("input_schema", tool.inputSchema)
            )
        }
        return PluginLlmRequest(
            request.model,
            request.system,
            messages.toString(),
            tools.toString(),
            request.maxTokens,
            request.temperature
        )
    }

    private fun PluginLlmResponse.toChatResponse(): ChatResponse {
        val blocks = mutableListOf<ChatBlock>()
        if (text.isNotBlank()) blocks += ChatBlock.Text(text)
        runCatching { JSONArray(toolCallsJson) }.getOrDefault(JSONArray())
            .let { array ->
                for (index in 0 until array.length()) {
                    val tool = array.optJSONObject(index) ?: continue
                    val name = tool.optString("name").trim()
                    if (name.isBlank()) continue
                    val arguments = tool.optJSONObject("arguments")
                        ?: JSONObject()
                    blocks += ChatBlock.ToolUse(
                        tool.optString("id")
                            .ifBlank { "plugin_tool_${System.nanoTime()}_$index" },
                        name,
                        arguments
                    )
                }
            }
        val usage = if (inputTokens > 0 || outputTokens > 0) {
            ChatUsage(inputTokens, outputTokens)
        } else {
            null
        }
        return ChatResponse(
            blocks,
            stopReason.takeIf { it.isNotBlank() },
            usage
        )
    }
}

private suspend fun <T> CompletableFuture<T>.awaitCancellable(): T =
    suspendCancellableCoroutine { continuation ->
        whenComplete { value, error ->
            if (!continuation.isActive) return@whenComplete
            if (error == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation {
            cancel(true)
        }
    }
