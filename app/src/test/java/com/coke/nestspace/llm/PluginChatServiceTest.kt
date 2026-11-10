package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.LlmProtocol
import com.coke.nestspace.plugin.PluginDescriptor
import com.coke.nestspace.plugin.PluginLlmModel
import com.coke.nestspace.plugin.PluginLlmProvider
import com.coke.nestspace.plugin.PluginLlmProviders
import com.coke.nestspace.plugin.PluginLlmRequest
import com.coke.nestspace.plugin.PluginLlmResponse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumSet
import com.coke.nestspace.plugin.PluginCapability
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

class PluginChatServiceTest {

    @After
    fun cleanup() {
        PluginLlmProviders.uninstallProvider(PROVIDER_ID)
    }

    @Test
    fun pluginProviderAnswersAndStreamsWithToolCalls() = runBlocking {
        PluginLlmProviders.install(provider())
        val config = LlmConfig(
            protocol = LlmProtocol.OPENAI,
            baseUrl = "plugin://$PROVIDER_ID",
            model = "test-model"
        )
        assertTrue(config.isConfigured)
        val service = ChatServices.create(config)
        val request = ChatRequest(
            model = "test-model",
            system = "system",
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(ChatBlock.Text("hello"))
                )
            ),
            tools = emptyList(),
            maxTokens = 100,
            temperature = 0.2
        )

        val response = service.chat(request)
        assertEquals("answer", response.text)
        assertEquals("search", response.toolUses.single().name)
        assertEquals(
            "query",
            response.toolUses.single().input.getString("q")
        )

        val deltas = mutableListOf<String>()
        val streamed = service.streamChat(request, deltas::add)
        assertEquals("answer", streamed.text)
        assertEquals(listOf("ans", "wer"), deltas)

        val nonStreamingDeltas = mutableListOf<String>()
        val nonStreaming = ChatServices.create(
            config.copy(streamingEnabled = false)
        )
        val completed = nonStreaming.streamChat(
            request,
            nonStreamingDeltas::add
        )
        assertEquals("answer", completed.text)
        assertTrue(nonStreamingDeltas.isEmpty())
    }

    private fun provider(): PluginLlmProvider =
        object : PluginLlmProvider {
            override fun getDescriptor(): PluginDescriptor =
                PluginDescriptor(
                    "test.llm.provider",
                    "Test LLM",
                    "1",
                    PluginDescriptor.API_VERSION,
                    0,
                    EnumSet.of(PluginCapability.LLM_PROVIDERS)
                )

            override fun getProviderId(): String = PROVIDER_ID

            override fun getDisplayName(): String = "Test provider"

            override fun getModels(): List<PluginLlmModel> =
                listOf(
                    PluginLlmModel(
                        "test-model",
                        "Test model",
                        32_000,
                        false
                    )
                )

            override fun executeAsync(
                request: PluginLlmRequest,
                onTextDelta: Consumer<String>
            ): CompletableFuture<PluginLlmResponse> {
                if (request.isStreamEnabled()) {
                    onTextDelta.accept("ans")
                    onTextDelta.accept("wer")
                }
                return CompletableFuture.completedFuture(response())
            }

            private fun response(): PluginLlmResponse =
                PluginLlmResponse(
                    "answer",
                    """
                    [{
                      "id":"call-1",
                      "name":"search",
                      "arguments":{"q":"query"}
                    }]
                    """.trimIndent(),
                    "stop",
                    10,
                    5
                )
        }

    private companion object {
        const val PROVIDER_ID = "test-provider"
    }
}
