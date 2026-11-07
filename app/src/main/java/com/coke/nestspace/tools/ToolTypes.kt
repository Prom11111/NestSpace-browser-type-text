package com.coke.nestspace.tools

import org.json.JSONObject

enum class Caller { LOCAL_AGENT, REMOTE_MCP, USER }

internal enum class AssistantPresentation { VISIBLE, HEADLESS }

/**
 * Presentation policy for virtual apps driven by the in-app assistant.
 *
 * Explicit in-app actions win over information words: "open Douyin and search"
 * is visible, while "open Douyin and check what is shown" remains headless.
 */
internal fun assistantPresentation(userUtterances: List<String>): AssistantPresentation {
    val text = userUtterances.joinToString(" ").lowercase()
    val operation = listOf(
        "搜索", "搜一下", "搜一搜", "查找", "点击", "点一下", "点开",
        "关注", "点赞", "收藏", "评论", "发布", "下单", "购买", "支付",
        "预约", "转发", "分享", "播放",
        "search", "tap", "click", "follow", "like", "comment", "post",
        "order", "buy", "play", "share"
    ).any(text::contains)
    if (operation) return AssistantPresentation.VISIBLE

    val open = listOf("打开", "启动", "进入", "open", "launch", "start")
        .any(text::contains)
    val information = listOf(
        "获取", "查看", "读取", "整理", "总结", "汇总", "列出", "告诉我",
        "有哪些", "是什么", "多少", "热门", "推荐", "热榜", "排名", "资讯",
        "内容", "查一下", "看看",
        "read", "get", "check", "collect", "summarize", "tell me", "what",
        "trending", "ranking"
    ).any(text::contains)
    return if (open && !information) {
        AssistantPresentation.VISIBLE
    } else {
        AssistantPresentation.HEADLESS
    }
}

data class ToolContext(
    val sessionId: String,
    val caller: Caller,
    val spaceId: String = "system",
    /** Guides already shown in this run, so the same app/site guide is not injected twice. */
    val seenKnowledge: MutableSet<String> = mutableSetOf(),
    /** User turns in this run; mutable so steering turns are visible to later tool calls. */
    val userUtterances: List<String> = emptyList(),
    /** Called when the agent reads a skill; used to track that skill's outcomes. */
    val onSkillUsed: ((String) -> Unit)? = null
)

data class ToolResult(
    val ok: Boolean,
    val text: String,
    val imageBase64: String? = null,
    val errorCode: String? = null,
    val imageMimeType: String = "image/jpeg"
)

data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val mutating: Boolean = false,
    val handler: suspend (JSONObject, ToolContext) -> ToolResult
)

class LeaseManager {
    private data class Holder(val sessionId: String, val at: Long)

    private val holders = mutableMapOf<String, Holder>()
    private val ttlMs = 30_000L

    @Synchronized
    fun acquire(spaceId: String, sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val current = holders[spaceId]
        if (current == null || current.sessionId == sessionId || now - current.at > ttlMs) {
            holders[spaceId] = Holder(sessionId, now)
            return true
        }
        return false
    }

    /** Keep a long-running mutating call from losing its lease to the TTL. */
    @Synchronized
    fun renew(spaceId: String, sessionId: String) {
        val current = holders[spaceId] ?: return
        if (current.sessionId == sessionId) {
            holders[spaceId] = Holder(sessionId, System.currentTimeMillis())
        }
    }

    @Synchronized
    fun release(sessionId: String) {
        val iterator = holders.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.sessionId == sessionId) iterator.remove()
        }
    }

    @Synchronized
    fun status(spaceId: String): JSONObject {
        val current = holders[spaceId]
        return JSONObject().apply {
            put("space_id", spaceId)
            put("holder", current?.sessionId ?: JSONObject.NULL)
            put("ttl_ms", ttlMs)
        }
    }
}

internal fun toolSchema(
    vararg props: Pair<String, String>,
    required: List<String> = emptyList()
): JSONObject {
    val properties = JSONObject()
    props.forEach { (name, type) ->
        properties.put(name, JSONObject().put("type", type))
    }
    val req = org.json.JSONArray()
    required.forEach { req.put(it) }
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", req)
        .put("additionalProperties", false)
}
