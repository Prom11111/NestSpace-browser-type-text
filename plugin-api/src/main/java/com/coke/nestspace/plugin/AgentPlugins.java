package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local registry for Agent prompt and Q&A hooks.
 */
public final class AgentPlugins {

    public static final int MAX_PROMPT_CONTRIBUTION_CHARS = 2_000;
    public static final int MAX_TOTAL_PROMPT_CHARS = 8_000;
    public static final int MAX_ANSWER_CHARS = 4_000;

    private static final Object LOCK = new Object();
    private static final Map<String, AgentHookPlugin> PLUGINS =
        new LinkedHashMap<>();

    private AgentPlugins() {
    }

    public static void install(AgentHookPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor =
            Objects.requireNonNull(plugin.getDescriptor(), "descriptor");
        if (descriptor.getId().isEmpty()) {
            throw new IllegalArgumentException("plugin id is required");
        }
        if (descriptor.getApiVersion() != PluginDescriptor.API_VERSION) {
            throw new IllegalArgumentException(
                "unsupported Agent plugin API " + descriptor.getApiVersion()
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

    public static List<AgentHookPlugin> snapshot() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(
                new ArrayList<>(PLUGINS.values())
            );
        }
    }

    public static List<AgentPromptContribution> promptContributions(
        AgentPromptContext context
    ) {
        Objects.requireNonNull(context, "context");
        List<AgentPromptContribution> result = new ArrayList<>();
        int remaining = MAX_TOTAL_PROMPT_CHARS;
        for (AgentHookPlugin plugin : orderedPlugins()) {
            if (!plugin.getDescriptor().getCapabilities()
                .contains(PluginCapability.AGENT_PROMPT)) {
                continue;
            }
            List<AgentPromptContribution> contributions =
                plugin.promptContributions(context);
            if (contributions == null) continue;
            for (AgentPromptContribution contribution : contributions) {
                if (remaining <= 0) return result;
                String text = contribution.getText();
                if (text.length() > MAX_PROMPT_CONTRIBUTION_CHARS) {
                    text = text.substring(0, MAX_PROMPT_CONTRIBUTION_CHARS);
                }
                if (text.length() > remaining) {
                    text = text.substring(0, remaining);
                }
                result.add(
                    new AgentPromptContribution(
                        contribution.getId(),
                        contribution.getTitle(),
                        text,
                        contribution.getPriority()
                    )
                );
                remaining -= text.length();
            }
        }
        return result;
    }

    private static List<AgentHookPlugin> orderedPlugins() {
        List<AgentHookPlugin> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(PLUGINS.values());
        }
        ordered.sort(
            Comparator
                .comparingInt(
                    (AgentHookPlugin plugin) ->
                        plugin.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    plugin -> plugin.getDescriptor().getId()
                )
        );
        return ordered;
    }

    public static String pluginLabel(AgentHookPlugin plugin) {
        return plugin.getDescriptor().getName();
    }
}
