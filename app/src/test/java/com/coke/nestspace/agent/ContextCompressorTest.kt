package com.coke.nestspace.agent

import com.coke.nestspace.llm.ChatBlock
import com.coke.nestspace.llm.ChatMessage
import com.coke.nestspace.llm.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    @Test
    fun shortConversationIsNotChanged() {
        val messages = mutableListOf(
            ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("你好"))),
            ChatMessage(ChatRole.ASSISTANT, listOf(ChatBlock.Text("你好，有什么可以帮你？")))
        )
        val original = messages.toList()
        val stats = ContextCompressor.compact(
            messages,
            systemPrompt = "system",
            tools = emptyList(),
            maxOutputTokens = 100,
            budgetTokens = 32_000
        )
        assertFalse(stats.compacted)
        assertEquals(original, messages)
    }

    @Test
    fun oldResultsAreShortenedAndRecentResultIsKept() {
        val bigOld = "旧结果".repeat(3_000)
        val bigRecent = "最近结果".repeat(2_000)
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage(ChatRole.USER, listOf(ChatBlock.Text("帮我完成一个长任务")))
        for (i in 0 until 6) {
            messages += ChatMessage(
                ChatRole.ASSISTANT,
                listOf(
                    ChatBlock.Text("继续执行第 $i 步，" + "说明".repeat(400)),
                    ChatBlock.ToolUse(
                        id = "t$i",
                        name = "ui_observe",
                        input = JSONObject().put("package", "com.coke.testapp")
                    )
                )
            )
            messages += ChatMessage(
                ChatRole.USER,
                listOf(
                    ChatBlock.ToolResult(
                        toolUseId = "t$i",
                        content = if (i == 5) bigRecent else bigOld,
                        isError = false
                    )
                )
            )
        }

        val stats = ContextCompressor.compact(
            messages,
            systemPrompt = "system prompt",
            tools = emptyList(),
            maxOutputTokens = 200,
            budgetTokens = 3_000
        )

        assertTrue(stats.compacted)
        assertTrue(stats.after < stats.before)
        assertEquals("帮我完成一个长任务", messages.first().text)
        val oldResult = messages[2].blocks.filterIsInstance<ChatBlock.ToolResult>().single()
        assertTrue(oldResult.content.length < bigOld.length)
        assertTrue(oldResult.content.contains("earlier context shortened"))
        val recentResult = messages.last().blocks.filterIsInstance<ChatBlock.ToolResult>().single()
        assertEquals(bigRecent, recentResult.content)
        assertEquals(6, messages.filter { message ->
            message.blocks.any { it is ChatBlock.ToolUse }
        }.size)
        assertEquals(6, messages.filter { message ->
            message.blocks.any { it is ChatBlock.ToolResult }
        }.size)
    }

    @Test
    fun userTextIsNeverTruncated() {
        val command = "目标：".repeat(2_000)
        val messages = mutableListOf(
            ChatMessage(ChatRole.USER, listOf(ChatBlock.Text(command))),
            ChatMessage(
                ChatRole.ASSISTANT,
                listOf(ChatBlock.Text("分析".repeat(3_000)))
            ),
            ChatMessage(
                ChatRole.USER,
                listOf(
                    ChatBlock.ToolResult(
                        toolUseId = "t1",
                        content = "结果".repeat(5_000),
                        isError = false
                    )
                )
            )
        )
        ContextCompressor.compact(
            messages,
            systemPrompt = "s",
            tools = emptyList(),
            maxOutputTokens = 10,
            budgetTokens = 1_000
        )
        assertEquals(command, messages.first().text)
    }

    @Test
    fun cjkCostsMoreThanAscii() {
        assertTrue(
            ContextCompressor.estimateText("你好世界") >
                ContextCompressor.estimateText("abcd")
        )
    }
}
