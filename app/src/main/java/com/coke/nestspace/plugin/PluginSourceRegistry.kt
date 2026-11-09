package com.coke.nestspace.plugin

import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal data class SourcePluginRecord(
    val manifest: PluginManifest,
    val directory: File,
    val plugin: Any,
    val host: AndroidPluginHostApi
)

/**
 * Debug/source plugins compiled directly into the host. This registry is empty
 * in release builds where only packaged plugins are loaded.
 */
internal object PluginSourceRegistry {
    private val plugins = ConcurrentHashMap<String, SourcePluginRecord>()

    fun register(record: SourcePluginRecord) {
        val previous = plugins.put(record.manifest.id, record)
        if (previous != null && previous !== record) {
            PluginRuntimeManager.detach(previous.plugin, previous.host)
        }
    }

    fun contains(pluginId: String): Boolean = plugins.containsKey(pluginId)

    fun manifests(): List<PluginManifest> =
        plugins.values.map { it.manifest }.sortedBy { it.name.lowercase() }

    fun iconFile(pluginId: String): File? =
        safeFile(pluginId, plugins[pluginId]?.manifest?.iconPath)

    fun htmlPage(pluginId: String): File? =
        safeFile(pluginId, plugins[pluginId]?.manifest?.htmlPage)

    fun settingsSchemaFile(pluginId: String): File? =
        safeFile(
            pluginId,
            plugins[pluginId]?.manifest?.contributions?.settingsPath
        )

    fun clear() {
        plugins.values.forEach {
            PluginRuntimeManager.detach(it.plugin, it.host)
        }
        plugins.clear()
    }

    private fun safeFile(pluginId: String, relative: String?): File? {
        if (relative.isNullOrBlank()) return null
        val record = plugins[pluginId] ?: return null
        val root = record.directory.canonicalPath + File.separator
        val file = File(record.directory, relative).canonicalFile
        return file.takeIf {
            it.path.startsWith(root) && it.isFile
        }
    }
}
