package com.coke.nestspace.tools

import org.json.JSONObject

enum class Caller { LOCAL_AGENT, REMOTE_MCP, USER }

enum class ContainerDisplayMode(val api: String) {
    AUTO("auto"),
    BACKGROUND("background"),
    MIRROR("mirror"),
    VISIBLE("visible");

    companion object {
        fun parse(
            value: String?,
            default: ContainerDisplayMode
        ): ContainerDisplayMode {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.api == normalized } ?: default
        }
    }
}

internal enum class AssistantPresentation { VISIBLE, HEADLESS }

/**
 * Presentation policy for virtual apps driven by the in-app assistant.
 *
 * AUTO always uses the mirror. A visible app is reserved for an explicit
 * display override or a user takeover; wording such as "open" or "search"
 * must never promote an assistant-controlled app to full screen.
 */
internal fun assistantPresentation(displayMode: ContainerDisplayMode): AssistantPresentation =
    if (displayMode == ContainerDisplayMode.VISIBLE) {
        AssistantPresentation.VISIBLE
    } else {
        AssistantPresentation.HEADLESS
    }

data class ToolContext(
    val sessionId: String,
    val caller: Caller,
    val spaceId: String = "system",
    /** Stable task owner; unlike sessionId it survives reconnect for the same client. */
    val ownerId: String = sessionId,
    val parentTaskId: String? = null,
    /** AUTO keeps the normal policy; MCP can force an off-screen, mirror or visible launch. */
    val displayMode: ContainerDisplayMode = ContainerDisplayMode.AUTO,
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
    /**
     * Maximum time a caller waits synchronously for a result. This is only an
     * upper bound: a completed tool returns immediately, while unfinished work
     * keeps running and returns a task id after the bound is reached.
     */
    val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    /**
     * The tool owns a durable task and returns its task id itself. The registry
     * must not wrap it in a second, immediately completed tool_call task.
     */
    val nativeAsync: Boolean = false,
    val handler: suspend (JSONObject, ToolContext) -> ToolResult
) {
    companion object {
        const val DEFAULT_MAX_WAIT_MS = 500L
    }
}

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
