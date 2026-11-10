package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;

/**
 * Chrome-style declarative contribution points from manifest.json.
 */
public final class PluginContributions {

    private final String agentToolsPath;
    private final String commandsPath;
    private final String settingsPath;
    private final String llmProvidersPath;
    private final String speechProvidersPath;
    private final List<PluginPageSpec> pages;
    private final List<PluginRendererSpec> renderers;

    PluginContributions(
        String agentToolsPath,
        String commandsPath,
        String settingsPath,
        String llmProvidersPath,
        String speechProvidersPath,
        List<PluginPageSpec> pages,
        List<PluginRendererSpec> renderers
    ) {
        this.agentToolsPath = agentToolsPath;
        this.commandsPath = commandsPath;
        this.settingsPath = settingsPath;
        this.llmProvidersPath = llmProvidersPath;
        this.speechProvidersPath = speechProvidersPath;
        this.pages = pages == null || pages.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(pages);
        this.renderers = renderers == null || renderers.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(renderers);
    }

    public String getAgentToolsPath() {
        return agentToolsPath;
    }

    public String getCommandsPath() {
        return commandsPath;
    }

    public String getSettingsPath() {
        return settingsPath;
    }

    public String getLlmProvidersPath() {
        return llmProvidersPath;
    }

    public String getSpeechProvidersPath() {
        return speechProvidersPath;
    }

    public List<PluginPageSpec> getPages() {
        return pages;
    }

    public List<PluginRendererSpec> getRenderers() {
        return renderers;
    }

    public boolean isEmpty() {
        return agentToolsPath.isEmpty() &&
            commandsPath.isEmpty() &&
            settingsPath.isEmpty() &&
            llmProvidersPath.isEmpty() &&
            speechProvidersPath.isEmpty() &&
            pages.isEmpty() &&
            renderers.isEmpty();
    }
}
