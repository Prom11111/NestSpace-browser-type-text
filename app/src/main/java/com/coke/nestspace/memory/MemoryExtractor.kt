package com.coke.nestspace.memory

import android.content.Context
import com.coke.nestspace.agent.TokenUsageStore
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.LlmConfig
import com.coke.nestspace.data.ReasoningEffort
import com.coke.nestspace.llm.ChatBlock
import com.coke.nestspace.llm.ChatMessage
import com.coke.nestspace.llm.ChatRequest
import com.coke.nestspace.llm.ChatRole
import com.coke.nestspace.llm.ChatServices
import com.coke.nestspace.llm.ChatUsageSink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Post-turn memory extraction, modelled on Mika's separate lightweight pass.
 *
 * It only reads the user's own turns and existing related notes, so a web page
 * or tool result cannot become a memory. Facts/preferences are active at once;
 * procedures wait for approval. The extractor is best-effort: a failed request
 * or malformed answer never affects the assistant reply.
 */
internal object MemoryExtractor {

    private const val MAX_OPS = 3
    private const val MAX_RELATED = 10

    // Skip a second model request for ordinary task chatter that cannot contain
    // a durable personal fact. This is deliberately broader than "remember":
    // "我不吃香菜" and "我喝咖啡只喝无糖美式" should both run.
    private val CUE = Regex(
        """喜欢|不喜欢|爱吃|爱喝|不吃|不喝|只吃|只喝|只买|偏好|习惯|通常|一般|总是|从不|以后|每次|忌口|过敏|记住|别再|我的(名字|生日|地址|电话|邮箱|账号)|叫我|我(住|叫|姓|今年)|我养|刚才|像这样|照这样|按照这样|I (like|love|prefer|hate|always|never|usually|don't|do not)|my (name|birthday|address|phone|email)|remember|allergic""",
        RegexOption.IGNORE_CASE
    )

    fun mightContainMemory(text: String): Boolean = CUE.containsMatchIn(text)

    suspend fun extract(
        context: Context,
        config: LlmConfig,
        utterances: List<String>,
        contextSummary: String? = null
    ): List<MemoryItem> {
        val turns = utterances.map { it.trim() }.filter { it.isNotBlank() }
        if (turns.isEmpty() || !turns.any(::mightContainMemory)) return emptyList()
        val store = MemoryStore(context)
        val existing = store.list()
        val related = MemoryRanking.select(existing, turns.joinToString("\n"), MAX_RELATED)
        val request = ChatRequest(
            model = config.model,
            system = SYSTEM_PROMPT,
            messages = listOf(
                ChatMessage(
                    ChatRole.USER,
                    listOf(ChatBlock.Text(userPrompt(turns, related, contextSummary)))
                )
            ),
            tools = emptyList(),
            maxTokens = 500,
            temperature = 0.1,
            reasoningEffort = ReasoningEffort.OFF,
            purpose = "memory"
        )
        val response = runCatching {
            ChatServices.create(
                config,
                ChatUsageSink { usage, purpose ->
                    TokenUsageStore.get(context.applicationContext)
                        .record(config, usage, kind = purpose)
                }
            ).chat(request)
        }.getOrNull() ?: return emptyList()
        val ops = parse(response.text) ?: return emptyList()
        val needsReview = ToolPermissionSettings.requiresReviewForLearning(context)
        val added = mutableListOf<MemoryItem>()
        for (index in 0 until ops.length()) {
            if (added.size >= MAX_OPS) break
            val op = ops.optJSONObject(index) ?: continue
            addOp(store, existing + added, op, turns, needsReview)?.let { added += it }
        }
        if (added.isNotEmpty()) AppSettingsEvents.notifyChanged()
        return added
    }

    private fun addOp(
        store: MemoryStore,
        existing: List<MemoryItem>,
        op: JSONObject,
        turns: List<String>,
        needsReview: Boolean
    ): MemoryItem? {
        val kind = MemoryKind.from(op.optString("kind"))
        val content = MemoryPolicy.normalize(op.optString("content"))
        val quote = MemoryPolicy.normalize(op.optString("quote"))
        if (content.isBlank() || quote.isBlank()) return null
        if (!MemoryPolicy.quoteSupported(quote, turns)) return null
        if (existing.any {
                sameMemory(it.content, content) ||
                    (kind == MemoryKind.PROCEDURE &&
                        it.kind == MemoryKind.PROCEDURE &&
                        similarProcedure(it.content, content))
            }
        ) {
            return null
        }
        val keywords = when (val raw = op.opt("keywords")) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotBlank) }
            else -> op.optString("keywords")
                .split(',', '，', ';', '；')
                .map(String::trim)
                .filter(String::isNotEmpty)
        }
        return store.add(
            kind = kind,
            content = content,
            keywords = keywords,
            source = quote,
            approved = kind != MemoryKind.PROCEDURE || !needsReview
        ).getOrNull()
    }

    private fun sameMemory(old: String, new: String): Boolean {
        val a = MemoryPolicy.normalize(old).lowercase()
        val b = MemoryPolicy.normalize(new).lowercase()
        return a == b || (a.length >= 6 && b.length >= 6 && (a.contains(b) || b.contains(a)))
    }

    /** Avoid accumulating two slightly different versions of the same workflow. */
    private fun similarProcedure(old: String, new: String): Boolean {
        val a = MemoryRanking.queryTerms(old).toSet()
        val b = MemoryRanking.queryTerms(new).toSet()
        if (a.isEmpty() || b.isEmpty()) return false
        val overlap = b.count { it in a }.toDouble() / minOf(a.size, b.size)
        return overlap >= 0.6
    }

    private fun parse(text: String): JSONArray? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            JSONObject(text.substring(start, end + 1)).optJSONArray("ops")
        }.getOrNull()
    }

    private fun userPrompt(
        turns: List<String>,
        related: List<MemoryItem>,
        contextSummary: String?
    ): String = buildString {
        appendLine("用户本次说的话：")
        turns.forEachIndexed { index, turn -> appendLine("${index + 1}. $turn") }
        appendLine()
        if (related.isEmpty()) {
            appendLine("已有相关记忆：无")
        } else {
            appendLine("已有相关记忆（已覆盖的内容不要重复）：")
            related.forEach { appendLine("- [${it.kind.api}] ${it.content}") }
        }
        val summary = contextSummary?.trim()?.take(1_500)
        if (!summary.isNullOrBlank()) {
            appendLine()
            appendLine("助手刚才对本次任务的回复（只用于理解「刚才/这样」指代的流程，不能作为用户事实来源）：")
            append(summary)
        }
    }

    private val SYSTEM_PROMPT = """
        你是一个记忆整理器。只从“用户本次说的话”中提取值得长期记住的个人信息，输出 JSON，不要 Markdown：
        {"ops":[{"kind":"fact|preference|procedure","content":"...","quote":"用户原话","keywords":["..."]}]}

        规则：
        - quote 必须逐字出现在用户本次说的话里，不能改写、拼接或引用网页/工具内容。
        - fact：稳定事实（姓名、城市、职业、家人、过敏等）。preference：喜好、忌口、习惯。procedure：用户要求以后按某种方式做事，或描述了一个会重复使用的复杂操作流程。
        - 用户说“以后都这样/照刚才那样”时，可以根据“助手刚才的回复”总结 procedure，但 quote 仍必须是用户原话；fact 和 preference 不能来自助手回复。
        - 只提取用户明确说出的内容，不要猜测。一次性任务、临时状态、密码、验证码、银行卡、身份证、API Key 一律跳过。
        - content 用第三人称一句话，最多 200 字；keywords 最多 6 个。
        - 已有记忆已覆盖的内容不要重复输出。没有新内容时返回 {"ops":[]}。
        - 最多 3 条。
    """.trimIndent()
}
