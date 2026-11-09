package com.coke.nestspace.data

import com.coke.nestspace.i18n.AppLanguage

enum class LlmProtocol {
    ANTHROPIC,
    OPENAI;

    val label: String
        get() = when (this) {
            ANTHROPIC -> AppLanguage.tr("Claude 兼容", "Claude compatible")
            OPENAI -> AppLanguage.tr("OpenAI 兼容", "OpenAI compatible")
        }
}

/** User-facing reasoning effort. Providers that support native reasoning get
 * the matching API parameter; other compatible endpoints receive a prompt hint. */
enum class ReasoningEffort {
    OFF,
    LOW,
    MEDIUM,
    HIGH;

    val label: String
        get() = when (this) {
            OFF -> AppLanguage.tr("快速", "Fast")
            LOW -> AppLanguage.tr("轻量", "Light")
            MEDIUM -> AppLanguage.tr("标准", "Standard")
            HIGH -> AppLanguage.tr("深度", "Deep")
        }

    val description: String
        get() = when (this) {
            OFF -> AppLanguage.tr("直接回答，尽量少做额外推理", "Answer directly with minimal extra reasoning")
            LOW -> AppLanguage.tr("先做简短判断再执行", "Make a quick judgment, then act")
            MEDIUM -> AppLanguage.tr(
                "平衡速度和质量（默认）",
                "Balance speed and quality (default)"
            )
            HIGH -> AppLanguage.tr(
                "充分拆解和验证，适合复杂任务",
                "Break tasks down and verify thoroughly; best for complex work"
            )
        }

    /**
     * Hidden reasoning room providers receive on top of the configured answer
     * budget. DeepSeek-style reasoning models count thinking against
     * `max_tokens`, so the answer budget alone can be consumed entirely and
     * return empty content. Higher effort gets more room.
     */
    val reasoningReserveTokens: Int
        get() = when (this) {
            OFF -> 0
            LOW -> 4_096
            MEDIUM -> 8_192
            HIGH -> 16_384
        }
}

data class LlmConfig(
    val id: String = "",
    val name: String = "",
    val protocol: LlmProtocol = LlmProtocol.ANTHROPIC,
    val baseUrl: String = DEFAULT_ANTHROPIC_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_ANTHROPIC_MODEL,
    /** Total request budget used by local context compression. */
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    /** Maximum tool-calling steps before the agent asks for a text summary. */
    val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /** Visible answer budget. Hidden reasoning is added by providers on top. */
    val maxTokens: Int = 2048,
    val temperature: Double = 0.7,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
) {
    val isConfigured: Boolean
        get() = if (pluginProviderId() != null) {
            model.isNotBlank()
        } else {
            baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
        }

    /** The Base URL field stores the complete request endpoint. */
    fun endpoint(): String = baseUrl.trim()

    /**
     * Anthropic-compatible clients commonly accept a base URL such as
     * `https://api.deepseek.com/anthropic` and append `/v1/messages`
     * themselves. NestSpace sends the complete endpoint, so normalize the
     * value when the user saves or tests it. Existing versioned message
     * endpoints are left unchanged.
     */
    fun withNormalizedEndpoint(): LlmConfig {
        if (pluginProviderId() != null) {
            return if (baseUrl == baseUrl.trim()) this
            else copy(baseUrl = baseUrl.trim())
        }
        if (protocol != LlmProtocol.ANTHROPIC) return this
        val raw = baseUrl.trim()
        if (raw.isEmpty()) return this
        val suffixIndex = listOf(raw.indexOf('?'), raw.indexOf('#'))
            .filter { it >= 0 }
            .minOrNull()
        val path = suffixIndex?.let { raw.substring(0, it) } ?: raw
        val suffix = suffixIndex?.let { raw.substring(it) }.orEmpty()
        if (VERSIONED_MESSAGES_PATH.containsMatchIn(path)) {
            return if (baseUrl == raw) this else copy(baseUrl = raw)
        }
        return copy(baseUrl = path.trimEnd('/') + "/v1/messages" + suffix)
    }

    /**
     * A plugin provider uses `plugin://provider-id` in the existing Base URL
     * field. The plugin owns credentials and wire protocol.
     */
    fun pluginProviderId(): String? {
        val value = baseUrl.trim()
        if (!value.startsWith("plugin://", ignoreCase = true)) return null
        return value
            .substring("plugin://".length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) }
    }

    companion object {
        private val VERSIONED_MESSAGES_PATH =
            Regex("/v\\d+[a-z0-9._-]*/messages/?$", RegexOption.IGNORE_CASE)
        const val DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_CONTEXT_TOKENS = 800_000
        const val MIN_CONTEXT_TOKENS = 100_000
        const val CONTEXT_TOKEN_STEP = 100_000
        const val DEFAULT_MAX_TOOL_ROUNDS = 128
        const val MIN_TOOL_ROUNDS = 1
        const val MAX_TOOL_ROUNDS = 1_000
        const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_OPENAI_MODEL = "gpt-6.1-sol"

        fun defaultsFor(protocol: LlmProtocol): LlmConfig = when (protocol) {
            LlmProtocol.ANTHROPIC -> LlmConfig(
                name = "Claude",
                protocol = protocol,
                baseUrl = DEFAULT_ANTHROPIC_BASE,
                model = DEFAULT_ANTHROPIC_MODEL
            )
            LlmProtocol.OPENAI -> LlmConfig(
                name = "OpenAI",
                protocol = protocol,
                baseUrl = DEFAULT_OPENAI_BASE,
                model = DEFAULT_OPENAI_MODEL
            )
        }
    }
}
