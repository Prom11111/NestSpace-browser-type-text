package com.coke.nestspace.memory

import android.content.Context
import com.coke.nestspace.data.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SkillRevision(
    val at: Long,
    val description: String,
    val body: String
)

data class SkillItem(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val source: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val builtIn: Boolean = false,
    val useCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val recentOutcomes: List<Boolean> = emptyList(),
    val lastUsedAt: Long = 0L,
    val revisions: List<SkillRevision> = emptyList(),
    val autoSuspended: Boolean = false
)

/**
 * Reusable procedures written as Markdown. New skills are saved disabled and
 * must be enabled by the user before they appear in the model's skill index.
 * The model can still read any skill explicitly with skill_read.
 */
class SkillStore(context: Context) {

    private val appContext = context.applicationContext
    private val skillFile = File(appContext.filesDir, FILE)
    // Shared across instances so concurrent skill edits cannot overwrite
    // each other's changes.
    private val lock: Any get() = LOCK
    private val prefs by lazy {
        appContext.getSharedPreferences(BUILTIN_PREFS, android.content.Context.MODE_PRIVATE)
    }

    fun list(): List<SkillItem> = synchronized(lock) {
        val builtins = builtinItems()
        val names = builtins.map { it.name.lowercase() }.toMutableSet()
        var migrated = false
        val stored = load().map { item ->
            val key = item.name.lowercase()
            if (key in names) {
                // A built-in shipped after the user saved a skill with the
                // same name would otherwise shadow it forever (invisible and
                // undeletable). Keep the user's skill under a unique name.
                migrated = true
                val renamed = uniqueName(item.name, names)
                names += renamed.lowercase()
                item.copy(name = renamed, updatedAt = System.currentTimeMillis())
            } else {
                names += key
                item
            }
        }
        if (migrated) saveFile(stored)
        builtins + stored
    }

    fun enabled(): List<SkillItem> = list().filter { it.enabled }

    fun get(nameOrId: String): SkillItem? {
        val key = nameOrId.trim().lowercase()
        return list().firstOrNull {
            it.id.equals(nameOrId, ignoreCase = true) ||
                it.name.equals(key, ignoreCase = true)
        }
    }

    fun save(
        name: String,
        description: String,
        body: String,
        source: String,
        enabled: Boolean = false
    ): Result<SkillItem> {
        val validName = SkillPolicy.normalizeName(name)
        if (!SkillPolicy.isValidName(validName)) {
            return Result.failure(SkillException("invalid_name"))
        }
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return Result.failure(SkillException("empty"))
        if (cleanBody.length > SkillPolicy.MAX_BODY) {
            return Result.failure(SkillException("too_long"))
        }
        if (BuiltinSkills.load(appContext).any {
                it.name.equals(validName, ignoreCase = true)
            }) {
            return Result.failure(SkillException("builtin_name"))
        }
        val cleanDescription = description.trim().take(SkillPolicy.MAX_DESCRIPTION)
        val cleanSource = source.trim().take(MemoryPolicy.MAX_SOURCE)
        if (listOf(cleanBody, cleanDescription, cleanSource).any {
                MemoryPolicy.sensitiveReason(it) != null
            }
        ) {
            return Result.failure(SkillException("sensitive"))
        }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val items = loadForWrite()?.toMutableList()
                ?: return Result.failure(SkillException("unavailable"))
            val existing = items.indexOfFirst {
                it.name.equals(validName, ignoreCase = true)
            }
            val item = if (existing >= 0) {
                val old = items[existing]
                val revision = if (old.description != cleanDescription || old.body != cleanBody) {
                    SkillRevision(now, old.description, old.body)
                } else {
                    null
                }
                old.copy(
                    description = cleanDescription,
                    body = cleanBody,
                    source = cleanSource,
                    updatedAt = now,
                    revisions = (old.revisions + listOfNotNull(revision)).takeLast(20)
                )
            } else {
                SkillItem(
                    id = UUID.randomUUID().toString(),
                    name = validName,
                    description = cleanDescription,
                    body = cleanBody,
                    source = cleanSource,
                    enabled = enabled,
                    createdAt = now,
                    updatedAt = now
                )
            }
            if (existing >= 0) items[existing] = item else items += item
            if (!saveFile(items)) {
                return Result.failure(SkillException("write_failed"))
            }
            return Result.success(item)
        }
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = synchronized(lock) {
        val builtinName = BuiltinSkills.nameFromId(id)
        if (builtinName != null) {
            if (BuiltinSkills.load(appContext).none { it.name == builtinName }) return false
            prefs.edit().putBoolean(builtinKey(builtinName), enabled).apply()
            return true
        }
        val items = loadForWrite()?.toMutableList() ?: return false
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val current = items[index]
        items[index] = current.copy(
            enabled = enabled,
            autoSuspended = if (enabled) false else current.autoSuspended,
            recentOutcomes = if (enabled) emptyList() else current.recentOutcomes,
            updatedAt = System.currentTimeMillis()
        )
        saveFile(items)
    }

    /**
     * Record whether a run that read this skill completed successfully.
     * Two failures in the last three reviewed uses disable it automatically;
     * re-enabling starts a fresh outcome window.
     */
    fun recordOutcome(id: String, success: Boolean): SkillItem? = synchronized(lock) {
        if (BuiltinSkills.isBuiltinId(id)) return null
        val items = loadForWrite()?.toMutableList() ?: return null
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return null
        val old = items[index]
        val outcomes = (old.recentOutcomes + success).takeLast(5)
        val autoSuspend = SkillOutcomePolicy.shouldSuspend(outcomes)
        val updated = old.copy(
            useCount = old.useCount + 1,
            successCount = old.successCount + if (success) 1 else 0,
            failureCount = old.failureCount + if (success) 0 else 1,
            recentOutcomes = outcomes,
            lastUsedAt = System.currentTimeMillis(),
            enabled = if (autoSuspend) false else old.enabled,
            autoSuspended = autoSuspend || old.autoSuspended,
            updatedAt = System.currentTimeMillis()
        )
        items[index] = updated
        if (!saveFile(items)) return null
        updated
    }

    fun restoreRevision(id: String, at: Long): Result<SkillItem>? {
        val current = get(id) ?: return null
        if (current.builtIn) return null
        val revision = current.revisions.firstOrNull { it.at == at } ?: return null
        return save(
            name = current.name,
            description = revision.description,
            body = revision.body,
            source = current.source,
            enabled = current.enabled
        )
    }

    fun delete(id: String): Boolean {
        if (BuiltinSkills.isBuiltinId(id)) return false
        return synchronized(lock) {
            val items = loadForWrite()?.toMutableList() ?: return false
            val removed = items.removeAll { it.id == id }
            if (!removed) return false
            saveFile(items)
        }
    }

    /** One line per enabled skill for the system prompt. */
    fun enabledIndex(limit: Int = 20): String = enabled()
        .take(limit.coerceIn(1, 50))
        .joinToString("\n") { "- ${it.name}: ${it.description}" }

    private fun builtinItems(): List<SkillItem> =
        BuiltinSkills.load(appContext).map { skill ->
            SkillItem(
                id = BuiltinSkills.id(skill.name),
                name = skill.name,
                description = skill.description,
                body = skill.body,
                source = "builtin",
                enabled = prefs.getBoolean(builtinKey(skill.name), true),
                createdAt = 0L,
                updatedAt = 0L,
                builtIn = true
            )
        }

    private fun builtinKey(name: String): String = "enabled:$name"

    private fun uniqueName(base: String, taken: Set<String>): String {
        var candidate = "${base.take(32)}-user"
        var index = 2
        while (candidate.lowercase() in taken) {
            candidate = "${base.take(30)}-user-$index"
            index++
        }
        return candidate
    }

    private fun load(): List<SkillItem> = loadForWrite() ?: emptyList()

    private fun loadForWrite(): List<SkillItem>? {
        val raw = SecureStore.read(appContext, FILE)
        if (raw == null) return if (skillFile.isFile) null else emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                SkillItem(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    description = json.optString("description"),
                    body = json.optString("body"),
                    source = json.optString("source"),
                    enabled = json.optBoolean("enabled"),
                    createdAt = json.optLong("created_at"),
                    updatedAt = json.optLong("updated_at"),
                    useCount = json.optInt("use_count", 0),
                    successCount = json.optInt("success_count", 0),
                    failureCount = json.optInt("failure_count", 0),
                    recentOutcomes = json.optJSONArray("recent_outcomes")?.let { values ->
                        (0 until values.length()).map { values.optBoolean(it) }
                    }.orEmpty(),
                    lastUsedAt = json.optLong("last_used_at", 0L),
                    revisions = json.optJSONArray("revisions")?.let { values ->
                        (0 until values.length()).mapNotNull { i ->
                            val rev = values.optJSONObject(i) ?: return@mapNotNull null
                            SkillRevision(
                                at = rev.optLong("at"),
                                description = rev.optString("description"),
                                body = rev.optString("body")
                            )
                        }
                    }.orEmpty(),
                    autoSuspended = json.optBoolean("auto_suspended", false)
                )
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() && it.body.isNotBlank() }
        }.getOrNull()
    }

    private fun saveFile(items: List<SkillItem>): Boolean {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("description", item.description)
                    .put("body", item.body)
                    .put("source", item.source)
                    .put("enabled", item.enabled)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt)
                    .put("use_count", item.useCount)
                    .put("success_count", item.successCount)
                    .put("failure_count", item.failureCount)
                    .put(
                        "recent_outcomes",
                        JSONArray(item.recentOutcomes)
                    )
                    .put("last_used_at", item.lastUsedAt)
                    .put(
                        "revisions",
                        JSONArray().apply {
                            item.revisions.forEach { revision ->
                                put(
                                    JSONObject()
                                        .put("at", revision.at)
                                        .put("description", revision.description)
                                        .put("body", revision.body)
                                )
                            }
                        }
                    )
                    .put("auto_suspended", item.autoSuspended)
            )
        }
        return SecureStore.write(appContext, FILE, array.toString())
    }

    private companion object {
        val LOCK = Any()
        const val FILE = "skills.v1.enc"
        const val BUILTIN_PREFS = "builtin_skills"
    }
}

class SkillException(val code: String) : Exception(code)

internal object SkillOutcomePolicy {
    /** Two failures in the last three reviewed uses disable a skill. */
    fun shouldSuspend(outcomes: List<Boolean>): Boolean =
        outcomes.takeLast(3).count { !it } >= 2
}

object SkillPolicy {
    const val MAX_DESCRIPTION = 160
    const val MAX_BODY = 12_000
    private val NAME = Regex("[a-z0-9][a-z0-9-]{1,39}")
    private val WHITESPACE = Regex("\\s+")

    fun normalizeName(raw: String): String =
        WHITESPACE.replace(raw.trim().lowercase(), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .trim('-')
            .take(40)

    fun isValidName(value: String): Boolean = NAME.matches(value)
}
