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
}
