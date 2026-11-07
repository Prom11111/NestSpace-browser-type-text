package com.coke.nestspace.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmConfigTest {

    @Test
    fun `endpoint uses the complete base url unchanged`() {
        val anthropic = LlmConfig(
            protocol = LlmProtocol.ANTHROPIC,
            baseUrl = "https://open.bigmodel.cn/api/anthropic/v1/messages"
        )
        val openAi = LlmConfig(
            protocol = LlmProtocol.OPENAI,
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        )

        assertEquals(anthropic.baseUrl, anthropic.endpoint())
        assertEquals(openAi.baseUrl, openAi.endpoint())
    }

    @Test
    fun `anthropic base url gets the default messages path`() {
        val config = LlmConfig(
            protocol = LlmProtocol.ANTHROPIC,
            baseUrl = "https://api.deepseek.com/anthropic/"
        )

        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            config.withNormalizedEndpoint().baseUrl
        )
    }

    @Test
    fun `existing versioned messages path is not appended again`() {
        val configs = listOf(
            "https://api.deepseek.com/anthropic/v1/messages",
            "https://example.com/api/v2/messages",
            "https://example.com/v1beta/messages?beta=true"
        )

        configs.forEach { endpoint ->
            assertEquals(
                endpoint,
                LlmConfig(
                    protocol = LlmProtocol.ANTHROPIC,
                    baseUrl = endpoint
                ).withNormalizedEndpoint().baseUrl
            )
        }
    }

    @Test
    fun `openai endpoints are not treated as anthropic messages`() {
        val config = LlmConfig(
            protocol = LlmProtocol.OPENAI,
            baseUrl = "https://api.deepseek.com"
        )

        assertEquals(config, config.withNormalizedEndpoint())
    }
}
