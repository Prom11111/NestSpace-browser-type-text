package com.coke.nestspace.memory

import android.content.Context
import android.util.Log

/**
 * A read-only skill shipped with the app under `assets/skills/<name>/SKILL.md`.
 * Built-ins have stable ids and can be disabled by the user, but not deleted or
 * overwritten by [SkillStore.save].
 */
data class BuiltinSkill(
    val name: String,
    val description: String,
    val body: String
)

object BuiltinSkills {

    private const val TAG = "BuiltinSkills"
    private const val DIR = "skills"
    private const val FILE = "SKILL.md"
    private const val ID_PREFIX = "builtin:"

    @Volatile
    private var cached: List<BuiltinSkill>? = null

    fun id(name: String): String = ID_PREFIX + name

    fun isBuiltinId(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun nameFromId(id: String): String? = id.takeIf { isBuiltinId(it) }
        ?.removePrefix(ID_PREFIX)
        ?.takeIf { it.isNotBlank() }

    fun load(context: Context): List<BuiltinSkill> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadAssets(context.applicationContext).also { cached = it }
        }
    }

    /** Keeps tests and asset linting independent of Android's AssetManager. */
    fun parse(directoryName: String, text: String): BuiltinSkill? {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.lines()
        if (lines.firstOrNull()?.trim() != "---") return null
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" } + 1
        if (end <= 0) return null

        val meta = mutableMapOf<String, String>()
        for (line in lines.subList(1, end)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            meta[trimmed.substring(0, colon).trim().lowercase()] =
                trimmed.substring(colon + 1).trim()
        }
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: directoryName
        val description = meta["description"]?.trim().orEmpty()
        val body = lines.subList(end + 1, lines.size).joinToString("\n").trim()
        if (!SkillPolicy.isValidName(name)) return null
        if (description.isBlank()) return null
        if (body.isBlank() || body.length > SkillPolicy.MAX_BODY) return null
        return BuiltinSkill(
            name = name,
            description = description.take(SkillPolicy.MAX_DESCRIPTION),
            body = body
        )
    }

    private fun loadAssets(context: Context): List<BuiltinSkill> {
        val names = runCatching { context.assets.list(DIR)?.sorted().orEmpty() }
            .getOrElse {
                Log.w(TAG, "Cannot list $DIR", it)
                emptyList()
            }
        return names.mapNotNull { name ->
            val text = runCatching {
                context.assets.open("$DIR/$name/$FILE")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "Cannot read $DIR/$name/$FILE", it)
                return@mapNotNull null
            }
            parse(name, text).also {
                if (it == null) Log.w(TAG, "Ignoring invalid skill $DIR/$name/$FILE")
            }
        }.distinctBy { it.name }
    }
}
