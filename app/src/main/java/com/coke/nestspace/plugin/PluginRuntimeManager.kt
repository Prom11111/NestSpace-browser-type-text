package com.coke.nestspace.plugin

import com.coke.nestspace.speech.SpeechGateway

/**
 * Attaches one instantiated plugin to every Hook registry it implements.
 *
 * This is the only host-side wiring point. Adding a new Provider interface does
 * not require changes to the Agent, UI or container internals.
 */
internal object PluginRuntimeManager {

    fun attach(plugin: Any, context: PluginContext) {
        val descriptor = if (plugin is PluginLifecycle) {
            context.descriptor
        } else {
            descriptorOf(plugin)
        }
        try {
            if (plugin is PluginLifecycle) plugin.onAttach(context)
            if (plugin is RuntimeProxyPlugin) {
                RuntimeProxyPlugins.install(plugin)
            }
            if (plugin is RuntimeProxyExtensionProvider) {
                RuntimeProxyExtensions.install(plugin)
            }
            if (plugin is AgentHookPlugin) {
                AgentPlugins.install(plugin)
            }
            if (plugin is AgentModelHookPlugin) {
                AgentModelPlugins.install(plugin)
            }
            if (plugin is AgentToolProvider) {
                AgentToolPlugins.install(plugin)
            }
            if (plugin is AgentToolHookPlugin) {
                AgentToolHookPlugins.install(plugin)
            }
            if (plugin is AgentToolPresentationPlugin) {
                AgentToolPresentationPlugins.install(plugin)
            }
            if (plugin is PluginCommandProvider) {
                PluginCommandPlugins.install(plugin)
            }
            if (plugin is PluginUiProvider) {
                PluginUiPlugins.install(plugin)
            }
            if (plugin is PluginLlmProvider) {
                PluginLlmProviders.install(plugin)
            }
            if (plugin is PluginSpeechProvider) {
                PluginSpeechProviders.install(plugin)
            }
            if (plugin is PluginSettingsViewProvider) {
                PluginSettingsViewPlugins.install(plugin, context)
            }
        } catch (error: Throwable) {
            detach(plugin, descriptor, context)
            throw error
        }
    }

    fun detach(plugin: Any, context: PluginContext) {
        detach(plugin, context.descriptor, context)
    }

    private fun detach(
        plugin: Any,
        descriptor: PluginDescriptor,
        context: PluginContext
    ) {
        val id = descriptor.id
        RuntimeProxyPlugins.uninstall(id)
        RuntimeProxyExtensions.uninstall(id)
        AgentPlugins.uninstall(id)
        AgentModelPlugins.uninstall(id)
        AgentToolPlugins.uninstall(id)
        AgentToolHookPlugins.uninstall(id)
        AgentToolPresentationPlugins.uninstall(id)
        PluginCommandPlugins.uninstall(id)
        PluginUiPlugins.uninstall(id)
        PluginSettingsViewPlugins.uninstall(id)
        if (plugin is PluginLlmProvider) {
            PluginLlmProviders.uninstallProvider(plugin.providerId)
        }
        if (plugin is PluginSpeechProvider) {
            PluginSpeechProviders.uninstall(plugin.providerId)
            SpeechGateway.stopProvider(plugin.providerId)
        }
        if (plugin is PluginLifecycle) {
            runCatching(plugin::onDetach)
        }
        if (context is AutoCloseable) {
            runCatching(context::close)
        }
    }

    private fun descriptorOf(plugin: Any): PluginDescriptor = when (plugin) {
        is RuntimeProxyPlugin -> plugin.descriptor
        is RuntimeProxyExtensionProvider -> plugin.descriptor
        is AgentHookPlugin -> plugin.descriptor
        is AgentModelHookPlugin -> plugin.descriptor
        is AgentToolProvider -> plugin.descriptor
        is AgentToolHookPlugin -> plugin.descriptor
        is AgentToolPresentationPlugin -> plugin.descriptor
        is PluginCommandProvider -> plugin.descriptor
        is PluginUiProvider -> plugin.descriptor
        is PluginLlmProvider -> plugin.descriptor
        is PluginSpeechProvider -> plugin.descriptor
        is PluginSettingsViewProvider -> plugin.descriptor
        else -> error("plugin does not expose a descriptor")
    }
}
