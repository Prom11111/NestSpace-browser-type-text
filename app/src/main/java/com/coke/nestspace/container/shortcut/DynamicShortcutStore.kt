package com.coke.nestspace.container.shortcut

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists dynamic shortcuts captured inside a container process so the
 * desktop (main process) can show them in the long-press menu.
 */
internal object DynamicShortcutStore {

    data class Record(
        val id: String,
        val label: String,
        val intentUri: String,
        val targetActivity: String?,
        val enabled: Boolean,
        val rank: Int
    )

    private val LOCK = Any()
    private const val MAX_RECORDS = 50

    fun load(root: File, packageName: String, instanceId: String): List<Record> {
        val file = file(root, packageName, instanceId)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val id = json.optString("id")
                val label = json.optString("label")
                val intentUri = json.optString("intent_uri")
                if (id.isBlank() || intentUri.isBlank()) return@mapNotNull null
                Record(
                    id = id,
                    label = label.ifBlank { id },
                    intentUri = intentUri,
                    targetActivity = json.optString("target_activity").takeIf { it.isNotBlank() },
                    enabled = json.optBoolean("enabled", true),
                    rank = json.optInt("rank", 0)
                )
            }.take(MAX_RECORDS)
        }.getOrDefault(emptyList())
    }

    fun save(root: File, packageName: String, instanceId: String, records: List<Record>) {
        synchronized(LOCK) {
            runCatching {
                val file = file(root, packageName, instanceId)
                file.parentFile?.mkdirs()
                val array = JSONArray()
                records.take(MAX_RECORDS).forEach { record ->
                    array.put(
                        JSONObject()
                            .put("id", record.id)
                            .put("label", record.label)
                            .put("intent_uri", record.intentUri)
                            .put("target_activity", record.targetActivity ?: "")
                            .put("enabled", record.enabled)
                            .put("rank", record.rank)
                    )
                }
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(array.toString())
                if (!temp.renameTo(file)) {
                    temp.delete()
                    file.writeText(array.toString())
                }
            }
        }
    }

    fun clearPackage(root: File, packageName: String) {
        synchronized(LOCK) {
            val prefix = "${sanitize(packageName)}_"
            root.listFiles()
                ?.filter { it.name.startsWith(prefix) && it.isFile }
                ?.forEach { it.delete() }
        }
    }

    private fun file(root: File, packageName: String, instanceId: String): File =
        File(root, "${sanitize(packageName)}_${sanitize(instanceId)}.json")

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
