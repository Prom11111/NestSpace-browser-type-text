package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentModelPlugins {

    public static final int MAX_SYSTEM_PROMPT_CHARS = 100_000;
    public static final int MAX_ANSWER_CHARS = 8_000;
    public static final int MAX_BLOCK_MESSAGE_CHARS = 1_000;

    private static final Object LOCK = new Object();
    private static final Map<String, AgentModelHookPlugin> PLUGINS =
        new LinkedHashMap<>();

    private AgentModelPlugins() {
    }

    public static void install(AgentModelHookPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor =
            Objects.requireNonNull(plugin.getDescriptor(), "descriptor");
        if (descriptor.getApiVersion() != PluginDescriptor.API_VERSION) {
            throw new IllegalArgumentException(
                "unsupported Agent model plugin API "
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

    public static List<AgentModelHookPlugin> snapshot() {
        List<AgentModelHookPlugin> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(PLUGINS.values());
        }
        ordered.sort(
            Comparator
                .comparingInt(
                    (AgentModelHookPlugin plugin) ->
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
