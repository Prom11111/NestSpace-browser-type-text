package com.coke.nestspace.plugin

import android.content.Context
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.i18n.AppLanguage
import java.util.EnumSet

internal data class BuiltInPluginEntry(
    val descriptor: PluginDescriptor,
    val description: String,
    val restartRequired: Boolean,
    val enabled: Boolean
)

/**
 * Persistent state and activation for host-owned feature plugins.
 */
internal object PluginCenterStore {

    const val INSTALLATION_GUIDE = "builtin.agent.installation-guide"
    const val BROWSER_TOOLS = "builtin.agent.browser-tools"
    const val MEMORY_SKILLS = "builtin.agent.memory-skills"
    const val SCHEDULED_TASKS = "builtin.agent.scheduled-tasks"
    const val MCP_TOOL_BRIDGE = "builtin.mcp.tool-bridge"

    private const val PREFS = "plugin_center"
    private const val KEY_ENABLED = "enabled:"
    private const val KEY_DISPLAY_NAME = "display_name:"

    fun builtIns(context: Context): List<BuiltInPluginEntry> {
        val chinese = AppLanguage.isChinese()
        return listOf(
            entry(
                context,
                PluginDescriptor(
                    INSTALLATION_GUIDE,
                    AppLanguage.tr("安装引导", "Installation guide"),
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    900,
                    EnumSet.of(
                        PluginCapability.AGENT_PROMPT,
                        PluginCapability.AGENT_QUESTION
                    )
                ),
                AppLanguage.tr(
                    "在 Agent 中回答 APK、导入应用和 Split APK 安装问题。",
                    "Answers APK import and split-install questions inside the Agent."
                ),
                restartRequired = false
            ),
            entry(
                context,
                PluginDescriptor(
                    BROWSER_TOOLS,
                    AppLanguage.tr("浏览器工具", "Browser tools"),
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    800,
                    EnumSet.of(PluginCapability.AGENT_TOOLS)
                ),
                AppLanguage.tr(
                    "向 Agent 和 MCP 提供网页打开、读取、点击、下载与上传工具。",
                    "Provides page open, read, click, download and upload tools to " +
                        "the Agent and MCP."
                ),
                restartRequired = false
            ),
            entry(
                context,
                PluginDescriptor(
                    MEMORY_SKILLS,
                    AppLanguage.tr("记忆与技能", "Memory & skills"),
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    700,
                    EnumSet.of(
                        PluginCapability.AGENT_TOOLS,
                        PluginCapability.STORAGE
                    )
                ),
                AppLanguage.tr(
                    "提供记忆搜索/保存/删除和可复用技能管理工具。",
                    "Provides memory search/save/delete and reusable skill tools."
                ),
                restartRequired = false
            ),
            entry(
                context,
                PluginDescriptor(
                    SCHEDULED_TASKS,
                    AppLanguage.tr("定时任务", "Scheduled tasks"),
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    600,
                    EnumSet.of(
                        PluginCapability.AGENT_TOOLS,
                        PluginCapability.TASKS
                    )
                ),
                AppLanguage.tr(
                    "让 Agent 创建、暂停、恢复和执行定时任务。",
                    "Lets the Agent create, pause, resume and run scheduled tasks."
                ),
                restartRequired = false
            ),
            entry(
                context,
                PluginDescriptor(
                    MCP_TOOL_BRIDGE,
                    AppLanguage.tr("MCP 工具桥接", "MCP tool bridge"),
                    "1.0.0",
                    PluginDescriptor.API_VERSION,
                    500,
                    EnumSet.of(
                        PluginCapability.AGENT_TOOLS,
                        PluginCapability.NETWORK
                    )
                ),
                AppLanguage.tr(
                    "把用户配置的 MCP Server 工具注入本地 Agent。",
                    "Adds tools from user-configured MCP servers to the local Agent."
                ),
                restartRequired = false
            )
        )
    }

    fun isEnabled(context: Context, pluginId: String): Boolean =
        preferences(context).getBoolean(KEY_ENABLED + pluginId, true)

    fun setEnabled(context: Context, pluginId: String, enabled: Boolean) {
        val builtIn = builtIns(context)
            .any { it.descriptor.id == pluginId }
        val sourcePlugin = PluginSourceRegistry.contains(pluginId)
        val packagePlugin = PluginPackageInstaller.get(context)
            .contains(pluginId)
        require(builtIn || sourcePlugin || packagePlugin) {
            "Plugin cannot be enabled or disabled: $pluginId"
        }
        val previous = isEnabled(context, pluginId)
        check(
            preferences(context).edit()
                .putBoolean(KEY_ENABLED + pluginId, enabled)
                .commit()
        ) { "plugin state could not be saved" }
        runCatching {
            when {
                sourcePlugin ->
                    PluginSourceLoader.setEnabled(context, pluginId, enabled)
                packagePlugin ->
                    PluginPackageInstaller.get(context)
                        .setEnabled(pluginId, enabled)
                pluginId == INSTALLATION_GUIDE -> if (enabled) {
                    AgentPlugins.install(InstallationHelpPlugin)
                } else {
                    AgentPlugins.uninstall(INSTALLATION_GUIDE)
                }
            }
        }.onFailure {
            preferences(context).edit()
                .putBoolean(KEY_ENABLED + pluginId, previous)
                .commit()
            throw it
        }
        AppSettingsEvents.notifyChanged()
    }

    fun displayName(
        context: Context,
        pluginId: String,
        fallback: String
    ): String = preferences(context)
        .getString(KEY_DISPLAY_NAME + pluginId, null)
        ?.takeIf { it.isNotBlank() }
        ?: fallback

    fun setDisplayName(
        context: Context,
        pluginId: String,
        name: String
    ) {
        val normalized = name.trim()
        require(normalized.length <= 80) {
            "plugin display name must be at most 80 characters"
        }
        val editor = preferences(context).edit()
        if (normalized.isEmpty()) {
            editor.remove(KEY_DISPLAY_NAME + pluginId)
        } else {
            editor.putString(KEY_DISPLAY_NAME + pluginId, normalized)
        }
        check(editor.commit()) { "plugin display name could not be saved" }
        AppSettingsEvents.notifyChanged()
    }

    fun toolModuleEnabled(context: Context, toolName: String): Boolean = when {
        toolName.startsWith("browser_") ->
            isEnabled(context, BROWSER_TOOLS)
        toolName.startsWith("memory_") || toolName.startsWith("skill_") ->
            isEnabled(context, MEMORY_SKILLS)
        toolName.startsWith("schedule_") ->
            isEnabled(context, SCHEDULED_TASKS)
        else -> true
    }

    private fun entry(
        context: Context,
        descriptor: PluginDescriptor,
        description: String,
        restartRequired: Boolean
    ) = BuiltInPluginEntry(
        descriptor,
        description,
        restartRequired,
        isEnabled(context, descriptor.id)
    )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
}
