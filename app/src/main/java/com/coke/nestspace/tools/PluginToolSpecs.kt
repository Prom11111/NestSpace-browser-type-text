package com.coke.nestspace.tools

import android.content.Context
import android.net.Uri
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.plugin.AndroidPluginSettings
import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginCenterStore
import com.coke.nestspace.plugin.PluginCommandCall
import com.coke.nestspace.plugin.PluginCommandPlugins
import com.coke.nestspace.plugin.PluginCommandResult
import com.coke.nestspace.plugin.PluginManifest
import com.coke.nestspace.plugin.PluginPackageInstaller
import com.coke.nestspace.plugin.PluginFileStore
import com.coke.nestspace.plugin.PluginSourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal fun ToolRegistry.pluginToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "plugin_list",
        description = "List installed, source and built-in NestSpace plugins and their " +
            "capabilities. Plugin-provided Agent tools and " +
            "commands also appear as regular MCP tools.",
        inputSchema = schema()
    ) { _, _ ->
        ToolResult(true, pluginCatalogJson(context).toString())
    },
    ToolSpec(
        name = "plugin_manage",
        description = "Manage NestSpace plugins. Actions: install from URL/content URI/" +
            "local path, uninstall external packages, rename, list or change settings, " +
            "enable/disable built-ins, and list or invoke plugin commands.",
        inputSchema = schema(
            "action" to "string",
            "plugin_id" to "string",
            "source" to "string",
            "name" to "string",
            "setting_key" to "string",
            "setting_value" to "string",
            "setting_type" to "string",
            "command_id" to "string",
            "arguments" to "object",
            "enabled" to "boolean"
        ).apply {
            getJSONObject("properties").getJSONObject("action").put(
                "enum",
                JSONArray(
                    listOf(
                        "install",
                        "uninstall",
                        "rename",
                        "settings_get",
                        "settings_set",
                        "settings_remove",
                        "set_enabled",
                        "commands",
                        "invoke_command"
                    )
                )
            )
            put("required", JSONArray(listOf("action")))
        },
        mutating = true
    ) { args, ctx ->
        when (args.optString("action").trim().lowercase()) {
            "install" -> installPlugin(context, args, ctx)
            "uninstall" -> uninstallPlugin(context, args, ctx)
            "rename" -> renamePlugin(context, args)
            "settings_get" -> pluginSettings(context, args)
            "settings_set" -> setPluginSetting(context, args, ctx)
            "settings_remove" -> removePluginSetting(context, args, ctx)
            "set_enabled" -> setBuiltInEnabled(context, args, ctx)
            "commands" -> pluginCommands(context, args)
            "invoke_command" -> invokePluginCommand(context, args, ctx)
            else -> invalidPluginAction(args.optString("action"))
        }
    }
)

private suspend fun installPlugin(
    context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val source = args.optString("source").trim()
    if (source.isEmpty()) {
        return ToolResult(
            false,
            "source is required",
            errorCode = "INVALID_ARGUMENT"
        )
    }
    requirePluginConfirmation(
        context,
        ctx,
        "install plugin from $source",
        AppLanguage.tr(
            "从 $source 安装插件。",
            "Install a plugin from $source."
        ),
        ToolConfirmationCategory.INSTALL
    )?.let { return it }
    return try {
        val manifest = withContext(Dispatchers.IO) {
            openPluginSource(context, source).use {
                PluginPackageInstaller.get(context).installFromInput(it)
            }
        }
        ToolResult(
            true,
            JSONObject()
                .put("installed", true)
                .put("plugin", pluginManifestJson(context, manifest, "external"))
                .toString()
        )
    } catch (error: Throwable) {
        ToolResult(
            false,
            "Plugin install failed: ${error.message ?: error.javaClass.simpleName}",
            errorCode = "PLUGIN_INSTALL_FAILED"
        )
    }
}
private suspend fun uninstallPlugin(
    context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    if (PluginSourceRegistry.contains(pluginId)) {
        return ToolResult(
            false,
            "Source plugins are removed by changing the Debug source build",
            errorCode = "SOURCE_PLUGIN"
        )
    }
    val installed = PluginPackageInstaller.get(context)
        .installedManifests()
        .any { it.id == pluginId }
    if (!installed) {
        return ToolResult(
            false,
            "External plugin not found: $pluginId",
            errorCode = "PLUGIN_NOT_FOUND"
        )
    }
    requirePluginConfirmation(
        context,
        ctx,
        "uninstall plugin $pluginId",
        AppLanguage.tr(
            "将删除插件 $pluginId 及其私有数据、设置和密钥。",
            "Delete plugin $pluginId and its private data, settings and secrets."
        ),
        ToolConfirmationCategory.INSTALL
    )?.let { return it }
    return try {
        withContext(Dispatchers.IO) {
            PluginPackageInstaller.get(context).uninstall(pluginId)
        }
        ToolResult(true, JSONObject().put("uninstalled", pluginId).toString())
    } catch (error: Throwable) {
        ToolResult(
            false,
            "Plugin uninstall failed: ${error.message ?: error.javaClass.simpleName}",
            errorCode = "PLUGIN_UNINSTALL_FAILED"
        )
    }
}

private fun renamePlugin(context: Context, args: JSONObject): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val name = args.optString("name").trim()
    if (name.isEmpty()) {
        return ToolResult(false, "name is required", errorCode = "INVALID_ARGUMENT")
    }
    findPluginManifest(context, pluginId)
        ?: return pluginNotFound(pluginId)
    return runCatching {
        PluginCenterStore.setDisplayName(context, pluginId, name)
        ToolResult(
            true,
            JSONObject().put("plugin_id", pluginId).put("name", name).toString()
        )
    }.getOrElse {
        ToolResult(
            false,
            "Plugin rename failed: ${it.message ?: it.javaClass.simpleName}",
            errorCode = "PLUGIN_RENAME_FAILED"
        )
    }
}

private fun pluginSettings(context: Context, args: JSONObject): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val manifest = findPluginManifest(context, pluginId)
        ?: return pluginNotFound(pluginId)
    if (PluginCapability.SETTINGS !in manifest.capabilities) {
        return ToolResult(
            false,
            "Plugin does not declare settings",
            errorCode = "SETTINGS_UNAVAILABLE"
        )
    }
    val prefix = PluginFileStore.storageNamespace(pluginId) + ":"
    val values = JSONObject()
    context.getSharedPreferences("plugin_settings", Context.MODE_PRIVATE)
        .all
        .forEach { (key, value) ->
            if (key.startsWith(prefix)) {
                values.put(key.removePrefix(prefix), JSONObject.wrap(value))
            }
        }
    return ToolResult(
        true,
        JSONObject()
            .put("plugin_id", pluginId)
            .put("settings", values)
            .toString()
    )
}

private suspend fun setPluginSetting(
    context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val key = args.optString("setting_key").trim()
    if (key.isEmpty()) {
        return ToolResult(false, "setting_key is required", errorCode = "INVALID_ARGUMENT")
    }
    val manifest = findPluginManifest(context, pluginId)
        ?: return pluginNotFound(pluginId)
    if (PluginCapability.SETTINGS !in manifest.capabilities) {
        return ToolResult(
            false,
            "Plugin does not declare settings",
            errorCode = "SETTINGS_UNAVAILABLE"
        )
    }
    requirePluginConfirmation(
        context,
        ctx,
        "change plugin setting $pluginId/$key",
        AppLanguage.tr(
            "修改插件 $pluginId 的设置 $key。",
            "Change plugin $pluginId setting $key."
        ),
        ToolConfirmationCategory.PLUGIN
    )?.let { return it }
    val settings = AndroidPluginSettings(context, pluginId)
    val value = args.optString("setting_value")
    return runCatching {
        when (args.optString("setting_type").trim().lowercase().ifBlank {
            inferSettingType(value)
        }) {
            "boolean" -> settings.putBoolean(
                key,
                value.equals("true", true) || value == "1"
            ).get(10, TimeUnit.SECONDS)
            "long" -> settings.putLong(
                key,
                value.toLongOrNull()
                    ?: error("setting_value is not a long")
            ).get(10, TimeUnit.SECONDS)
            else -> settings.putString(key, value)
                .get(10, TimeUnit.SECONDS)
        }
        ToolResult(
            true,
            JSONObject()
                .put("plugin_id", pluginId)
                .put("setting_key", key)
                .put("saved", true)
                .toString()
        )
    }.getOrElse {
        ToolResult(
            false,
            "Plugin setting failed: ${it.message ?: it.javaClass.simpleName}",
            errorCode = "PLUGIN_SETTING_FAILED"
        )
    }
}

private suspend fun removePluginSetting(
    context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val key = args.optString("setting_key").trim()
    if (key.isEmpty()) {
        return ToolResult(false, "setting_key is required", errorCode = "INVALID_ARGUMENT")
    }
    requirePluginConfirmation(
        context,
        ctx,
        "remove plugin setting $pluginId/$key",
        AppLanguage.tr(
            "移除插件 $pluginId 的设置 $key。",
            "Remove plugin $pluginId setting $key."
        ),
        ToolConfirmationCategory.PLUGIN
    )?.let { return it }
    return runCatching {
        AndroidPluginSettings(context, pluginId).remove(key)
            .get(10, TimeUnit.SECONDS)
        ToolResult(
            true,
            JSONObject()
                .put("plugin_id", pluginId)
                .put("setting_key", key)
                .put("removed", true)
                .toString()
        )
    }.getOrElse {
        ToolResult(
            false,
            "Plugin setting removal failed: ${it.message ?: it.javaClass.simpleName}",
            errorCode = "PLUGIN_SETTING_FAILED"
        )
    }
}

private suspend fun setBuiltInEnabled(
    context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val builtIn = PluginCenterStore.builtIns(context)
        .firstOrNull { it.descriptor.id == pluginId }
        ?: return ToolResult(
            false,
            "Only built-in plugins can be enabled or disabled",
            errorCode = "PLUGIN_TOGGLE_UNSUPPORTED"
        )
    val enabled = args.optBoolean("enabled", !builtIn.enabled)
    requirePluginConfirmation(
        context,
        ctx,
        "${if (enabled) "enable" else "disable"} $pluginId",
        AppLanguage.tr(
            "将${if (enabled) "启用" else "停用"}插件 $pluginId。",
            "${if (enabled) "Enable" else "Disable"} plugin $pluginId."
        ),
        ToolConfirmationCategory.PLUGIN
    )?.let { return it }
    PluginCenterStore.setEnabled(context, pluginId, enabled)
    return ToolResult(
        true,
        JSONObject().put("plugin_id", pluginId).put("enabled", enabled).toString()
    )
}

private fun pluginCommands(@Suppress("UNUSED_PARAMETER") context: Context, args: JSONObject): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val provider = PluginCommandPlugins.snapshot()
        .firstOrNull { it.descriptor.id == pluginId }
        ?: return ToolResult(
            false,
            "Plugin command provider not found: $pluginId",
            errorCode = "PLUGIN_COMMAND_NOT_FOUND"
        )
    val commands = provider.listCommandsAsync()
        .get(10, TimeUnit.SECONDS)
    return ToolResult(
        true,
        JSONObject()
            .put("plugin_id", pluginId)
            .put("commands", JSONArray().apply {
                commands.forEach { spec ->
                    put(
                        JSONObject()
                            .put("id", spec.id)
                            .put("title", spec.title)
                            .put("description", spec.description)
                            .put("mutating", spec.isMutating)
                            .put("schema", JSONObject(spec.argumentsSchemaJson))
                    )
                }
            })
            .toString()
    )
}

private suspend fun invokePluginCommand(
    @Suppress("UNUSED_PARAMETER") context: Context,
    args: JSONObject,
    ctx: ToolContext
): ToolResult {
    val pluginId = args.optString("plugin_id").trim()
    if (pluginId.isEmpty()) return missingPluginId()
    val commandId = args.optString("command_id").trim()
    if (commandId.isEmpty()) {
        return ToolResult(false, "command_id is required", errorCode = "INVALID_ARGUMENT")
    }
    val provider = PluginCommandPlugins.snapshot()
        .firstOrNull { it.descriptor.id == pluginId }
        ?: return ToolResult(
            false,
            "Plugin command provider not found: $pluginId",
            errorCode = "PLUGIN_COMMAND_NOT_FOUND"
        )
    val spec = provider.listCommandsAsync()
        .get(10, TimeUnit.SECONDS)
        .firstOrNull { it.id == commandId }
        ?: return ToolResult(
            false,
            "Plugin command not found: $pluginId/$commandId",
            errorCode = "PLUGIN_COMMAND_NOT_FOUND"
        )
    if (spec.isMutating) {
        requirePluginConfirmation(
            context,
            ctx,
            "run plugin command $pluginId/$commandId",
            spec.title + "\n" + spec.description,
            ToolConfirmationCategory.PLUGIN
        )?.let { return it }
    }
    val result: PluginCommandResult = provider.invokeCommandAsync(
        PluginCommandCall(
            commandId,
            args.optJSONObject("arguments")?.toString() ?: "{}",
            ctx.sessionId,
            ctx.spaceId
        )
    ).get(spec.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
    return ToolResult(
        result.isSuccessful,
        JSONObject()
            .put("message", result.message)
            .put("data", result.dataJson)
            .toString(),
        errorCode = result.errorCode.takeIf { it.isNotBlank() }
            ?: if (result.isSuccessful) null else "PLUGIN_COMMAND_ERROR"
    )
}

private fun pluginCatalogJson(context: Context): JSONObject {
    val all = linkedMapOf<String, JSONObject>()
    PluginSourceRegistry.manifests().forEach {
        all[it.id] = pluginManifestJson(context, it, "source")
    }
    PluginPackageInstaller.get(context).installedManifests().forEach {
        all[it.id] = pluginManifestJson(context, it, "external")
    }
    PluginCenterStore.builtIns(context).forEach { builtIn ->
        val descriptor = builtIn.descriptor
        all[descriptor.id] = JSONObject()
            .put("id", descriptor.id)
            .put("name", descriptor.name)
            .put("version", descriptor.version)
            .put("source", "builtin")
            .put("enabled", builtIn.enabled)
            .put("toggle_supported", true)
            .put("description", builtIn.description)
            .put(
                "capabilities",
                JSONArray(descriptor.capabilities.map { it.name })
            )
    }
    return JSONObject().put("plugins", JSONArray(all.values))
}

private fun pluginManifestJson(
    context: Context,
    manifest: PluginManifest,
    source: String
): JSONObject = JSONObject()
    .put("id", manifest.id)
    .put("name", PluginCenterStore.displayName(
        context,
        manifest.id,
        manifest.name
    ))
    .put("version", manifest.version)
    .put("publisher", manifest.publisher)
    .put("description", manifest.description)
    .put("entry_class", manifest.entryClass)
    .put("source", source)
    .put("enabled", true)
    .put("toggle_supported", false)
    .put(
        "capabilities",
        JSONArray(manifest.capabilities.map { it.name })
    )
    .put("has_html_page", manifest.htmlPage.isNotBlank())
    .put("has_settings_schema", manifest.contributions.settingsPath.isNotBlank())

private fun findPluginManifest(
    context: Context,
    pluginId: String
): PluginManifest? =
    PluginSourceRegistry.manifests().firstOrNull { it.id == pluginId }
        ?: PluginPackageInstaller.get(context)
            .installedManifests()
            .firstOrNull { it.id == pluginId }

private fun openPluginSource(context: Context, source: String): InputStream {
    val uri = Uri.parse(source)
    return when (uri.scheme?.lowercase()) {
        "http", "https" -> {
            val connection = URL(source).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.setRequestProperty("User-Agent", "NestSpace-MCP")
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                error("Plugin download failed with HTTP $code")
            }
            object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        }
        "content" -> context.contentResolver.openInputStream(uri)
            ?: error("Could not open plugin URI")
        "file" -> FileInputStream(uri.path ?: error("Invalid file URI"))
        null -> FileInputStream(File(source))
        else -> error("Unsupported plugin source scheme: ${uri.scheme}")
    }
}

private fun inferSettingType(value: String): String = when {
    value.equals("true", true) || value.equals("false", true) -> "boolean"
    value.toLongOrNull() != null -> "long"
    else -> "string"
}

private fun missingPluginId(): ToolResult = ToolResult(
    false,
    "plugin_id is required",
    errorCode = "INVALID_ARGUMENT"
)

private fun pluginNotFound(pluginId: String): ToolResult = ToolResult(
    false,
    "Plugin not found: $pluginId",
    errorCode = "PLUGIN_NOT_FOUND"
)

private fun invalidPluginAction(action: String): ToolResult = ToolResult(
    false,
    "Unsupported plugin action: $action",
    errorCode = "INVALID_ARGUMENT"
)

private suspend fun requirePluginConfirmation(
    context: Context,
    ctx: ToolContext,
    action: String,
    detail: String,
    category: ToolConfirmationCategory
): ToolResult? {
    if (ctx.caller == Caller.USER) return null
    if (!ToolPermissionSettings.shouldConfirm(context, category)) return null
    val allowed = UserGateManager.askConfirmation(
        title = AppLanguage.tr("需要确认", "Confirmation needed"),
        detail = detail
    )
    return if (allowed) {
        null
    } else {
        ToolResult(
            false,
            AppLanguage.tr(
                "用户拒绝了这次操作：$action",
                "The user denied this action: $action"
            ),
            errorCode = "USER_DENIED"
        )
    }
}
