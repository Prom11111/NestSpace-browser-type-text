package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.data.SecureStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ChildRunPhase(val api: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    INTERRUPTED("interrupted"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String?): ChildRunPhase =
            entries.firstOrNull { it.api == value?.lowercase() } ?: UNKNOWN
    }
}

data class ChildRun(
    val id: String,
    val parentId: String,
    val goal: String,
    val phase: ChildRunPhase,
    val result: String,
    val maxSteps: Int,
    val toolMessages: Int,
    val hadErrors: Boolean,
    val startedAt: Long,
    val updatedAt: Long
)

/**
 * Persistent audit trail for child agents. The result is an encrypted preview;
 * the full answer returned to the parent can be larger per request.
 */
object ChildRunStore {
    private const val FILE = "child_runs.v1.enc"
    private const val KEEP = 40
    private const val MAX_RESULT = 4_000
    private const val MAX_GOAL = 2_000
    private const val STALE_RUNNING_MS = 10 * 60_000L
    private val lock = Any()

    fun begin(context: Context, parentId: String, goal: String, maxSteps: Int): ChildRun {
        val now = System.currentTimeMillis()
        val record = ChildRun(
            id = "child-$now-${UUID.randomUUID().toString().take(8)}",
            parentId = parentId.take(200),
            goal = goal.trim().take(MAX_GOAL),
            phase = ChildRunPhase.RUNNING,
            result = "",
            maxSteps = maxSteps,
            toolMessages = 0,
            hadErrors = false,
            startedAt = now,
            updatedAt = now
        )
        synchronized(lock) {
            val items = load(context).toMutableList()
            items += record
            save(context, items)
        }
        return record
    }

    fun finish(
        context: Context,
        id: String,
        phase: ChildRunPhase,
        result: String,
        toolMessages: Int,
        hadErrors: Boolean
    ): ChildRun? = synchronized(lock) {
        val items = load(context).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = items[index].copy(
            phase = phase,
            result = result.trim().take(MAX_RESULT),
            toolMessages = toolMessages,
            hadErrors = hadErrors,
            updatedAt = System.currentTimeMillis()
        )
        items[index] = updated
        save(context, items)
        updated
    }

    fun list(
        context: Context,
        parentId: String? = null,
        limit: Int = KEEP
    ): List<ChildRun> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val loaded = load(context)
        val normalized = loaded.map {
            if (it.phase == ChildRunPhase.RUNNING &&
                now - it.updatedAt > STALE_RUNNING_MS
            ) {
                it.copy(phase = ChildRunPhase.UNKNOWN, updatedAt = now)
            } else {
                it
            }
        }
        if (normalized != loaded) save(context, normalized)
        normalized
            .asSequence()
            .filter { parentId == null || it.parentId == parentId }
            .sortedByDescending { it.startedAt }
            .take(limit.coerceIn(1, KEEP))
            .toList()
    }

    fun get(context: Context, id: String): ChildRun? =
        list(context, limit = KEEP).firstOrNull { it.id == id }

    private fun load(context: Context): List<ChildRun> {
        val raw = SecureStore.read(context, FILE) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                ChildRun(
                    id = json.optString("id"),
                    parentId = json.optString("parent_id"),
                    goal = json.optString("goal"),
                    phase = ChildRunPhase.from(json.optString("phase")),
                    result = json.optString("result"),
                    maxSteps = json.optInt("max_steps", 15),
                    toolMessages = json.optInt("tool_messages"),
                    hadErrors = json.optBoolean("had_errors"),
                    startedAt = json.optLong("started_at"),
                    updatedAt = json.optLong("updated_at")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<ChildRun>) {
        val trimmed = items.sortedByDescending { it.startedAt }.take(KEEP)
        val array = JSONArray()
        trimmed.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("parent_id", item.parentId)
                    .put("goal", item.goal)
                    .put("phase", item.phase.api)
                    .put("result", item.result)
                    .put("max_steps", item.maxSteps)
                    .put("tool_messages", item.toolMessages)
                    .put("had_errors", item.hadErrors)
                    .put("started_at", item.startedAt)
                    .put("updated_at", item.updatedAt)
            )
        }
        SecureStore.write(context, FILE, array.toString())
    }
}

/** In-process registry used by the MCP cancel tools and the child-task screen. */
object ChildRunRegistry {
    private data class Active(val parentId: String, val job: Job)
    private val active = ConcurrentHashMap<String, Active>()
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount

    private fun publishCount() {
        _activeCount.value = active.size
    }

    fun register(runId: String, parentId: String, job: Job) {
        active[runId] = Active(parentId, job)
        publishCount()
    }

    fun unregister(runId: String) {
        active.remove(runId)
        publishCount()
    }

    fun isActive(runId: String): Boolean = active.containsKey(runId)

    fun cancel(runId: String): Boolean {
        val item = active.remove(runId) ?: return false
        publishCount()
        item.job.cancel()
        return true
    }

    fun cancelAll(parentId: String): Int {
        val ids = active.entries
            .filter { it.value.parentId == parentId }
            .map { it.key }
        ids.forEach(::cancel)
        return ids.size
    }

    fun activeIds(): List<String> = active.keys.toList()
}
