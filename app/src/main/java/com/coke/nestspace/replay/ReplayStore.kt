package com.coke.nestspace.replay

import android.content.Context
import com.coke.nestspace.data.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ReplayStep(
    val name: String,
    val argsJson: String
)

data class ReplayItem(
    val id: String,
    val name: String,
    val description: String,
    val goal: String,
    val steps: List<ReplayStep>,
    val createdAt: Long
)

class ReplayStore(context: Context) {

    private val appContext = context.applicationContext
    // Shared across instances so concurrent saves cannot overwrite each other.
    private val lock: Any get() = LOCK

    fun list(): List<ReplayItem> = synchronized(lock) {
        load().sortedByDescending { it.createdAt }
    }

    fun get(nameOrId: String): ReplayItem? = synchronized(lock) {
        load().firstOrNull {
            it.id.equals(nameOrId, ignoreCase = true) ||
                it.name.equals(nameOrId.trim(), ignoreCase = true)
        }
    }

    fun save(
        name: String,
        description: String,
        goal: String,
        steps: List<ReplayStep>
    ): Result<ReplayItem> {
        val cleanName = name.trim().take(60)
        if (cleanName.isBlank()) return Result.failure(ReplayException("name_required"))
        if (steps.isEmpty()) return Result.failure(ReplayException("empty"))
        synchronized(lock) {
            val items = load().toMutableList()
            val existing = items.indexOfFirst { it.name.equals(cleanName, ignoreCase = true) }
            val item = ReplayItem(
                id = if (existing >= 0) items[existing].id else UUID.randomUUID().toString(),
                name = cleanName,
                description = description.trim().take(240),
                goal = goal.trim().take(1_000),
                steps = steps,
                createdAt = System.currentTimeMillis()
            )
            if (existing >= 0) items[existing] = item else items += item
            save(items)
            return Result.success(item)
        }
    }

    fun delete(nameOrId: String): Boolean = synchronized(lock) {
        val items = load().toMutableList()
        val removed = items.removeAll {
            it.id.equals(nameOrId, ignoreCase = true) ||
                it.name.equals(nameOrId.trim(), ignoreCase = true)
        }
        if (removed) save(items)
        removed
    }

    private fun load(): List<ReplayItem> {
        val raw = SecureStore.read(appContext, FILE) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val steps = json.optJSONArray("steps")?.let { values ->
                    (0 until values.length()).mapNotNull { stepIndex ->
                        val step = values.optJSONObject(stepIndex) ?: return@mapNotNull null
                        ReplayStep(
                            name = step.optString("name"),
                            argsJson = step.optString("args")
                        )
                    }
                }.orEmpty()
                ReplayItem(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    description = json.optString("description"),
                    goal = json.optString("goal"),
                    steps = steps,
                    createdAt = json.optLong("created_at")
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.steps.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<ReplayItem>) {
        val array = JSONArray()
        items.forEach { item ->
            val steps = JSONArray()
            item.steps.forEach { step ->
                steps.put(
                    JSONObject()
                        .put("name", step.name)
                        .put("args", step.argsJson)
                )
            }
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("description", item.description)
                    .put("goal", item.goal)
                    .put("steps", steps)
                    .put("created_at", item.createdAt)
            )
        }
        SecureStore.write(appContext, FILE, array.toString())
    }

    private companion object {
        val LOCK = Any()
        const val FILE = "replays.v1.enc"
    }
}

class ReplayException(val code: String) : Exception(code)

/**
 * Holds the tool calls of the most recent successful assistant run. A user can
 * save that flow as a replay and execute it later without another model pass.
 */
object ReplayManager {

    data class Trace(val goal: String, val steps: List<ReplayStep>)

    private class Live(val goal: String) {
        val steps = mutableListOf<ReplayStep>()
    }

    private val lock = Any()
    private var current: Live? = null
    private var last: Trace? = null
    private val _lastFlow = MutableStateFlow<Trace?>(null)

    /** Observable copy of [last] so the replay screen can enable Save live. */
    val lastFlow: StateFlow<Trace?> = _lastFlow

    fun begin(goal: String) {
        synchronized(lock) {
            current = Live(goal)
        }
    }

    fun record(name: String, args: JSONObject) {
        synchronized(lock) {
            current?.steps?.add(ReplayStep(name, args.toString()))
        }
    }

    fun complete() {
        val trace = synchronized(lock) {
            // A successful run with no tool steps must clear the previous
            // trace; otherwise replay_save would offer an older run's steps.
            val next = current
                ?.takeIf { it.steps.isNotEmpty() }
                ?.let { Trace(it.goal, it.steps.toList()) }
            last = next
            current = null
            next
        }
        _lastFlow.value = trace
    }

    fun abort() {
        synchronized(lock) {
            current = null
        }
    }

    fun last(): Trace? = synchronized(lock) { last }
}
