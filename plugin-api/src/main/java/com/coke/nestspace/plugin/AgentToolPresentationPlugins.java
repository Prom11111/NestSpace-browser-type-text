package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentToolPresentationPlugins {

    private static final Object LOCK = new Object();
    private static final Map<String, AgentToolPresentationPlugin> PLUGINS =
        new LinkedHashMap<>();

    private AgentToolPresentationPlugins() {
    }

    public static void install(AgentToolPresentationPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor =
            Objects.requireNonNull(plugin.getDescriptor(), "descriptor");
        if (descriptor.getApiVersion() != PluginDescriptor.API_VERSION) {
            throw new IllegalArgumentException(
                "unsupported tool presentation API "
                    + descriptor.getApiVersion()
            );
        }
        synchronized (LOCK) {
            PLUGINS.put(descriptor.getId(), plugin);
        }
    }

    public static void uninstall(String pluginId) {
        synchronized (LOCK) {
            PLUGINS.remove(pluginId);
        }
    }

    public static List<AgentToolPresentationPlugin> snapshot() {
        List<AgentToolPresentationPlugin> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(PLUGINS.values());
        }
        ordered.sort(
            Comparator
                .comparingInt(
                    (AgentToolPresentationPlugin plugin) ->
                        plugin.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    plugin -> plugin.getDescriptor().getId()
                )
        );
        return Collections.unmodifiableList(ordered);
    }
}
