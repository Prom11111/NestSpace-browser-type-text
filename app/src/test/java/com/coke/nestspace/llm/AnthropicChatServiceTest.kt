package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicChatServiceTest {

    private val service = AnthropicChatService(
        LlmConfig(baseUrl = "https://api.anthropic.com", apiKey = "key")
    )

    @Test
    fun `builds native tool use and tool result blocks`() {
        val request = ChatRequest(
            model = "claude-test",
            system = "system prompt",
            messages = listOf(
                ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("list apps"))),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    listOf(
                        ChatBlock.ToolUse(
                            "tool-1",
                            "space_list_apps",
                            JSONObject().put("space_id", "virtual-1")
                        )
                    )
                ),
                ChatMessage(
                    ChatRole.USER,
                    listOf(ChatBlock.ToolResult("tool-1", """{"apps":[]}""", false))
                )
            ),
            tools = listOf(
                ChatTool(
                    "space_list_apps",
                    "list apps",
                    JSONObject().put("type", "object")
                )
            ),
            maxTokens = 512,
            temperature = 0.3
        )

        val body = service.buildBody(request)
        assertEquals("claude-test", body.getString("model"))
        assertEquals("system prompt", body.getString("system"))
        assertEquals(512, body.getInt("max_tokens"))
        assertTrue(body.has("tools"))

        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())

        val assistant = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", assistant.getString("type"))
        assertEquals("tool-1", assistant.getString("id"))
        assertEquals("space_list_apps", assistant.getString("name"))

        val result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("tool-1", result.getString("tool_use_id"))
        assertEquals(false, result.getBoolean("is_error"))
    }

    @Test
    fun `parses text and tool use blocks`() {
        val response = JSONObject(
            """
            {
              "stop_reason": "tool_use",
              "content": [
                {"type": "text", "text": "checking"},
                {"type": "tool_use", "id": "tool-9", "name": "ui_observe", "input": {}}
              ]
            }
            """.trimIndent()
        )

        val parsed = service.parseResponse(response)
        assertEquals("checking", parsed.text)
        assertEquals(1, parsed.toolUses.size)
        assertEquals("tool-9", parsed.toolUses[0].id)
        assertEquals("ui_observe", parsed.toolUses[0].name)
        assertEquals("tool_use", parsed.stopReason)
    }

    @Test
    fun `enables extended thinking for supported claude models`() {
        val request = ChatRequest(
            model = "claude-sonnet-5-5",
            system = "",
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("hi")))),
            tools = emptyList(),
            maxTokens = 2048,
            temperature = 0.2,
            reasoningEffort = ReasoningEffort.HIGH
        )

        val body = service.buildBody(request)
        // Visible answer budget (2048) plus the thinking budget (8192).
        assertEquals(10240, body.getInt("max_tokens"))
        assertEquals(1.0, body.getDouble("temperature"), 0.0001)
        val thinking = body.getJSONObject("thinking")
        assertEquals("enabled", thinking.getString("type"))
        assertEquals(8192, thinking.getInt("budget_tokens"))
    }

    @Test
    fun `enables extended thinking for fable models without the claude prefix`() {
        val request = ChatRequest(
            model = "fable-5-1",
            system = "",
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("hi")))),
            tools = emptyList(),
            maxTokens = 2048,
            temperature = 0.2,
            reasoningEffort = ReasoningEffort.HIGH
        )

        val body = service.buildBody(request)
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(8192, body.getJSONObject("thinking").getInt("budget_tokens"))
    }

    @Test
    fun `does not add native thinking to compatible non claude endpoints`() {
        val request = ChatRequest(
            model = "compatible-model",
            system = "",
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("hi")))),
            tools = emptyList(),
            maxTokens = 2048,
            temperature = 0.7,
            reasoningEffort = ReasoningEffort.HIGH
        )

        val body = service.buildBody(request)
        assertTrue(!body.has("thinking"))
        assertEquals(2048, body.getInt("max_tokens"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `preserves thinking blocks in follow up requests`() {
        val request = ChatRequest(
            model = "claude-sonnet-5-5",
            system = "",
            messages = listOf(
                ChatMessage(
                    ChatRole.ASSISTANT,
                    listOf(
                        ChatBlock.Thinking("plan", "signature-1"),
                        ChatBlock.ToolUse("t1", "system_status", JSONObject())
                    )
                )
            ),
            tools = emptyList(),
            maxTokens = 1024,
            temperature = 1.0
        )

        val content = service.buildBody(request)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("signature-1", content.getJSONObject(0).getString("signature"))
        assertEquals("tool_use", content.getJSONObject(1).getString("type"))
    }

    @Test
    fun `multiple tool results stay in one user message`() {
        val request = ChatRequest(
            model = "claude-test",
            system = "",
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(
                        ChatBlock.ToolResult("a", "ok", false),
                        ChatBlock.ToolResult("b", "ERROR", true)
                    )
                )
            ),
            tools = emptyList(),
            maxTokens = 128,
            temperature = 0.0
        )

        val content = service.buildBody(request)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals(true, content.getJSONObject(1).getBoolean("is_error"))
    }

    @Test
    fun `serializes image blocks for vision models`() {
        val request = ChatRequest(
            model = "claude-sonnet-5-5",
            system = "",
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(
                        ChatBlock.Text("what is on screen?"),
                        ChatBlock.Image("abc123", "image/jpeg")
                    )
                )
            ),
            tools = emptyList(),
            maxTokens = 512,
            temperature = 0.3
        )
        val content = service.buildBody(request)
            .getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("image", content.getJSONObject(1).getString("type"))
        val source = content.getJSONObject(1).getJSONObject("source")
        assertEquals("base64", source.getString("type"))
        assertEquals("image/jpeg", source.getString("media_type"))
        assertEquals("abc123", source.getString("data"))
        assertTrue(service.supportsVision)
    }

    @Test
    fun `deepseek flash advertises vision on anthropic protocol`() {
        val deepseek = AnthropicChatService(
            LlmConfig(
                baseUrl = "https://api.deepseek.com/anthropic",
                apiKey = "key",
                model = "deepseek-flash"
            )
        )
        assertTrue(deepseek.supportsVision)
    }

}
