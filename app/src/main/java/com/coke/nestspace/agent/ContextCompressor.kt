package com.coke.nestspace.agent

import com.coke.nestspace.llm.ChatBlock
import com.coke.nestspace.llm.ChatMessage
import com.coke.nestspace.llm.ChatTool
import org.json.JSONObject

/**
 * Keeps the agent request under a conservative token budget.
 *
 * The compressor never rewrites tool_use/tool_result pairing: it only shortens
 * old tool result bodies and old assistant prose, so provider protocols stay
 * valid. Recent steps and every user message (the task and its follow-ups) are
 * kept intact.
 *
 * It is deliberately deterministic and does not call a model, which keeps the
 * prompt prefix stable except when a compaction actually triggers.
 */
object ContextCompressor {

    /** Total request budget including system prompt, tools and output reserve. */
    const val DEFAULT_BUDGET_TOKENS = 32_000
    private const val TRIGGER_RATIO = 0.82
    private const val KEEP_RECENT_MESSAGES = 4
    private const val OLD_TOOL_RESULT_CHARS = 800
    private const val OLD_TEXT_CHARS = 2_000
    private const val AGGRESSIVE_TOOL_CHARS = 300
    private const val AGGRESSIVE_TEXT_CHARS = 700
    private const val TRUNCATED_MARK = "\n…[earlier context shortened]"

    data class Stats(
        val before: Int,
        val after: Int,
        val compacted: Boolean,
        val level: Int = 0
    )

    /**
     * Mutates [messages] in place. Call this immediately before building a
     * [com.coke.nestspace.llm.ChatRequest], not after appending a response.
     */
    fun compact(
        messages: MutableList<ChatMessage>,
        systemPrompt: String,
        tools: List<ChatTool>,
        maxOutputTokens: Int,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS
    ): Stats {
        val fixed = estimateText(systemPrompt) +
            tools.sumOf { tool ->
                estimateText(tool.name) + estimateText(tool.description) +
                    estimateText(tool.inputSchema.toString())
            } +
            maxOutputTokens.coerceAtLeast(0)
        val before = fixed + estimateMessages(messages)
        val trigger = (budgetTokens * TRIGGER_RATIO).toInt()
        if (before <= trigger) return Stats(before, before, false)

        var best: List<ChatMessage>? = null
        var bestAfter = before
        var bestLevel = 0
        val attempts = listOf(
            1 to (OLD_TOOL_RESULT_CHARS to OLD_TEXT_CHARS),
            2 to (AGGRESSIVE_TOOL_CHARS to AGGRESSIVE_TEXT_CHARS),
            3 to (AGGRESSIVE_TOOL_CHARS to AGGRESSIVE_TEXT_CHARS)
        )
        for ((attempt, limits) in attempts) {
            val includeRecent = attempt >= 3
            val candidate = compactOnce(
                messages = messages,
                keepRecent = if (includeRecent) 1 else KEEP_RECENT_MESSAGES,
                toolChars = limits.first,
                textChars = limits.second,
                includeRecent = includeRecent
            )
            val after = fixed + estimateMessages(candidate)
            if (after < bestAfter) {
                best = candidate
                bestAfter = after
                bestLevel = attempt
            }
            if (after <= budgetTokens) {
                messages.clear()
                messages.addAll(candidate)
                return Stats(before, after, true, attempt)
            }
        }

        val chosen = best ?: return Stats(before, before, false, 0)
        messages.clear()
        messages.addAll(chosen)
        return Stats(before, bestAfter, true, bestLevel)
    }

    /**
     * True when a request would exceed the trigger. Useful for tests and for
     * future UI that wants to show a context-pressure indicator.
     */
    fun shouldCompact(
        messages: List<ChatMessage>,
        systemPrompt: String,
        tools: List<ChatTool>,
        maxOutputTokens: Int,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS
    ): Boolean {
        val fixed = estimateText(systemPrompt) +
            tools.sumOf {
                estimateText(it.name) + estimateText(it.description) +
                    estimateText(it.inputSchema.toString())
            } +
            maxOutputTokens.coerceAtLeast(0)
        return fixed + estimateMessages(messages) > (budgetTokens * TRIGGER_RATIO).toInt()
    }

    private fun compactOnce(
        messages: List<ChatMessage>,
        keepRecent: Int,
        toolChars: Int,
        textChars: Int,
        includeRecent: Boolean
    ): List<ChatMessage> {
        val cutoff = (messages.size - keepRecent).coerceAtLeast(0)
        return messages.mapIndexed { index, message ->
            val old = if (includeRecent) index < messages.size - 1 else index < cutoff
            if (!old) return@mapIndexed message
            val blocks = message.blocks.map { block ->
                when (block) {
                    is ChatBlock.ToolResult -> {
                        if (block.content.length <= toolChars) {
                            block
                        } else {
                            block.copy(content = block.content.take(toolChars) + TRUNCATED_MARK)
                        }
                    }
                    is ChatBlock.Text -> {
                        // User text contains the task and instructions; never
                        // truncate it, even in older history.
                        if (message.role == com.coke.nestspace.llm.ChatRole.USER ||
                            block.text.length <= textChars
                        ) {
                            block
                        } else {
                            block.copy(text = block.text.take(textChars) + TRUNCATED_MARK)
                        }
                    }
                    is ChatBlock.Image -> ChatBlock.Text("[earlier screenshot omitted]")
                    else -> block
                }
            }
            message.copy(blocks = blocks)
        }
    }

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { message ->
            message.blocks.sumOf { block ->
                when (block) {
                    is ChatBlock.Text -> 4 + estimateText(block.text)
                    is ChatBlock.ToolUse ->
                        8 + estimateText(block.name) + estimateText(block.id) +
                            estimateText(block.input.toString())
                    is ChatBlock.ToolResult ->
                        8 + estimateText(block.content)
                    is ChatBlock.Thinking -> 8 + estimateText(block.thinking)
                    is ChatBlock.RedactedThinking -> 8 + estimateText(block.data)
                    is ChatBlock.Image -> 8 + (block.data.length / 4)
                }
            } + 4
        }

    /**
     * Conservative token estimate: CJK and other wide characters count as one
     * token, ASCII counts as roughly four characters per token.
     */
    fun estimateText(text: String): Int {
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

    /** Debug helper for tests and logs. */
    fun describe(messages: List<ChatMessage>): String = JSONObject()
        .put("messages", messages.size)
        .put("tokens", estimateMessages(messages))
        .toString()
}
