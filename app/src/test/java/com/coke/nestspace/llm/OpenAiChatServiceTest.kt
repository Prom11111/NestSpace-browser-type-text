package com.coke.nestspace.llm

import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatServiceTest {

    private val service = OpenAiChatService(
        LlmConfig(baseUrl = "https://api.openai.com", apiKey = "key")
    )

    @Test
    fun `maps assistant tool use and tool results to chat completions`() {
        val request = ChatRequest(
            model = "gpt-test",
            system = "system prompt",
            messages = listOf(
                ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("list apps"))),
                ChatMessage(
                    ChatRole.ASSISTANT,
                    listOf(
                        ChatBlock.ToolUse(
                            "call-1",
                            "space_list_apps",
                            JSONObject().put("space_id", "virtual-1")
                        )
                    )
                ),
                ChatMessage(
                    ChatRole.USER,
                    listOf(ChatBlock.ToolResult("call-1", """{"apps":[]}""", false))
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
        assertEquals("gpt-test", body.getString("model"))
        assertEquals("auto", body.getString("tool_choice"))

        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))

        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.isNull("content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-1", call.getString("id"))
        assertEquals("space_list_apps", call.getJSONObject("function").getString("name"))
        assertEquals(
            "virtual-1",
            JSONObject(call.getJSONObject("function").getString("arguments"))
                .getString("space_id")
        )

        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-1", tool.getString("tool_call_id"))
    }

    @Test
    fun `uses reasoning effort and max completion tokens for gpt-5`() {
        val request = ChatRequest(
            model = "gpt-5",
            system = "",
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("hi")))),
            tools = emptyList(),
            maxTokens = 1024,
            temperature = 0.7,
            reasoningEffort = ReasoningEffort.HIGH
        )

        val body = service.buildBody(request)
        assertEquals("high", body.getString("reasoning_effort"))
        // Visible answer budget (1024) plus high-effort reasoning headroom.
        assertEquals(17408, body.getInt("max_completion_tokens"))
        assertTrue(!body.has("max_tokens"))
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `keeps temperature for non reasoning models`() {
        val request = ChatRequest(
            model = "gpt-4o-mini",
            system = "",
            messages = listOf(ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("hi")))),
            tools = emptyList(),
            maxTokens = 512,
            temperature = 0.4,
            reasoningEffort = ReasoningEffort.HIGH
        )

        val body = service.buildBody(request)
        assertTrue(!body.has("reasoning_effort"))
        assertEquals(512, body.getInt("max_tokens"))
        assertEquals(0.4, body.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `parses tool calls with string arguments`() {
        val response = JSONObject(
            """
            {
              "choices": [{
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call-7",
                    "type": "function",
                    "function": {
                      "name": "ui_observe",
                      "arguments": "{\"x\":1}"
                    }
                  }]
                }
              }]
            }
            """.trimIndent()
        )

        val parsed = service.parseResponse(response)
        assertEquals(1, parsed.toolUses.size)
        assertEquals("call-7", parsed.toolUses[0].id)
        assertEquals("ui_observe", parsed.toolUses[0].name)
        assertEquals(1, parsed.toolUses[0].input.getInt("x"))
        assertTrue(parsed.text.isBlank())
        assertEquals("tool_calls", parsed.stopReason)
    }

    @Test
    fun `parses plain text response`() {
        val response = JSONObject(
            """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"done"}}]}
            """.trimIndent()
        )

        val parsed = service.parseResponse(response)
        assertEquals("done", parsed.text)
        assertTrue(parsed.toolUses.isEmpty())
        assertEquals("stop", parsed.stopReason)
    }

    @Test
    fun `attaches images in user content for vision models`() {
        val request = ChatRequest(
            model = "gpt-4o",
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
        val visionService = OpenAiChatService(
            LlmConfig(
                baseUrl = "https://api.openai.com",
                apiKey = "key",
                model = "gpt-4o"
            )
        )
        val message = visionService.buildBody(request).getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/jpeg;base64,")
        )
        assertTrue(visionService.supportsVision)
    }

    @Test
    fun `deepseek compatible model does not advertise vision`() {
        val deepseek = OpenAiChatService(
            LlmConfig(
                baseUrl = "https://api.deepseek.com",
                apiKey = "key",
                model = "deepseek-chat"
            )
        )
        assertTrue(!deepseek.supportsVision)
    }

    @Test
    fun `deepseek flash advertises vision`() {
        val deepseek = OpenAiChatService(
            LlmConfig(
                baseUrl = "https://api.deepseek.com",
                apiKey = "key",
                model = "deepseek-flash"
            )
        )
        assertTrue(deepseek.supportsVision)
    }

}
