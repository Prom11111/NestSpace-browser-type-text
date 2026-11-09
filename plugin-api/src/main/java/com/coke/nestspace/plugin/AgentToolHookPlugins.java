package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for before/after Agent tool Hooks.
 */
public final class AgentToolHookPlugins {

    private static final ConcurrentHashMap<String, AgentToolHookPlugin> PLUGINS =
        new ConcurrentHashMap<>();

    private AgentToolHookPlugins() {
    }

    public static void install(AgentToolHookPlugin plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        if (!descriptor.getCapabilities().contains(
            PluginCapability.AGENT_TOOL_HOOKS
        )) {
            throw new IllegalArgumentException(
                "AgentToolHookPlugin requires AGENT_TOOL_HOOKS"
            );
        }
        AgentToolHookPlugin previous = PLUGINS.putIfAbsent(
            descriptor.getId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "tool Hook already installed: " + descriptor.getId()
            );
        }
    }

    public static void uninstall(String pluginId) {
        PLUGINS.remove(pluginId);
    }

    public static List<AgentToolHookPlugin> snapshot() {
        List<AgentToolHookPlugin> result = new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (AgentToolHookPlugin value) ->
                        value.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    value -> value.getDescriptor().getId()
                )
        );
        return Collections.unmodifiableList(result);
    }
}
