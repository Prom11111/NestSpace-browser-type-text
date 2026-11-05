package com.coke.nestspace.memory

import android.content.Context
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.llm.ChatBlock
import com.coke.nestspace.llm.ChatMessage
import com.coke.nestspace.llm.ChatRequest
import com.coke.nestspace.llm.ChatRole
import com.coke.nestspace.llm.ChatServices
import com.coke.nestspace.llm.ChatUsageSink
import org.json.JSONObject

/**
 * Turns a successful multi-step run into a reusable skill draft. The draft is
 * saved disabled and shown as an inline enable card; it never auto-enables.
 */
internal object SkillDrafter {

    const val MIN_TOOL_STEPS = 4
    private const val MAX_DRAFT_BODY = SkillPolicy.MAX_BODY

    private val IGNORED_TOOLS = setOf(
        "skill_list", "skill_read", "skill_save",
        "memory_search", "memory_save", "memory_forget",
        "system_status", "assistant_status",
        "ui_observe", "ui_screenshot", "browser_status", "browser_tabs"
    )

    fun eligible(toolNames: List<String>): Boolean {
        if (toolNames.size < MIN_TOOL_STEPS) return false
        if (toolNames.any { it == "skill_save" }) return false
        return toolNames.any { it !in IGNORED_TOOLS }
    }

    suspend fun draft(
        context: Context,
        config: LlmConfig,
        goal: String,
        answer: String,
        toolNames: List<String>,
        existing: List<SkillItem>
    ): SkillItem? {
        if (!config.isConfigured || !eligible(toolNames)) return null
        val store = SkillStore(context)
        val existingNames = existing.map { it.name }.toSet()
        val request = ChatRequest(
            model = config.model,
            system = SYSTEM_PROMPT,
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(ChatBlock.Text(userPrompt(goal, answer, toolNames, existing)))
                )
            ),
            tools = emptyList(),
            maxTokens = 1_600,
            temperature = 0.2,
            reasoningEffort = ReasoningEffort.OFF,
            purpose = "skill"
        )
        val response = runCatching {
            ChatServices.create(
                config,
                ChatUsageSink { usage, purpose ->
                    TokenUsageStore.get(context.applicationContext)
                        .record(config, usage, kind = purpose)
                }
            ).chat(request)
        }.getOrNull() ?: return null
        val json = parse(response.text) ?: return null
        if (json.optBoolean("skip", false)) return null
        val name = SkillPolicy.normalizeName(json.optString("name"))
        val description = json.optString("description").trim()
        val body = json.optString("body").trim()
        if (!SkillPolicy.isValidName(name)) return null
        if (name in existingNames) return null
        if (description.isBlank() || body.isBlank()) return null
        if (body.length > MAX_DRAFT_BODY) return null
        if (existing.any { it.name == name || it.description.equals(description, true) }) {
            return null
        }
        if (listOf(body, description).any { MemoryPolicy.sensitiveReason(it) != null }) {
            return null
        }
        return store.save(
            name = name,
            description = description,
            body = body,
            source = "auto: ${goal.take(200)}",
            enabled = false
        ).getOrNull()
    }

    private fun parse(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    private fun userPrompt(
        goal: String,
        answer: String,
        toolNames: List<String>,
        existing: List<SkillItem>
    ): String = buildString {
        appendLine("任务目标：")
        appendLine(goal.take(1_500))
        appendLine()
        appendLine("最终结果：")
        appendLine(answer.take(2_500))
        appendLine()
        appendLine("实际调用过的工具（按顺序）：")
        toolNames.take(60).forEach { appendLine("- $it") }
        appendLine()
        if (existing.isEmpty()) {
            append("已有技能：无")
        } else {
            appendLine("已有技能（不要重复或换名重复）：")
            existing.take(30).forEach {
                appendLine("- ${it.name}: ${it.description}")
            }
        }
    }

    private val SYSTEM_PROMPT = """
        你负责把一次成功的多步骤任务整理成可复用的技能。只输出 JSON，不要 Markdown 代码块：
        {"name":"lowercase-hyphen-name","description":"一句话说明何时使用","body":"Markdown 正文"}
        如果这次任务只是一次性操作、没有可复用步骤，或已有技能已经覆盖，输出 {"skip":true}。

        body 要求：
        - 用 Markdown 写清适用场景、入口、步骤、容易出错的地方、怎样算完成、何时交给用户。
        - 用 <参数> 代替具体地址、账号、金额、日期、订单号等一次性值。
        - 不要写入密码、验证码、银行卡、身份证、API Key，也不要抄录网页原文。
        - 步骤要来自实际执行过程，不要编造未使用的工具。
        - 名称只使用小写字母、数字和连字符。
    """.trimIndent()
}
