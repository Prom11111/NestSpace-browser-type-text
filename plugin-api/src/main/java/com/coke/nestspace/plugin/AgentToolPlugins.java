package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe registry for Agent tool providers.
 */
public final class AgentToolPlugins {

    private static final ConcurrentHashMap<String, AgentToolProvider> PLUGINS =
        new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private AgentToolPlugins() {
    }

    public static void install(AgentToolProvider plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        requireCapability(descriptor);
        AgentToolProvider previous = PLUGINS.putIfAbsent(
            descriptor.getId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "tool provider already installed: " + descriptor.getId()
            );
        }
        if (previous == null) REVISION.incrementAndGet();
    }

    public static void uninstall(String pluginId) {
        if (PLUGINS.remove(pluginId) != null) REVISION.incrementAndGet();
    }

    public static long revision() {
        return REVISION.get();
    }

    public static List<AgentToolProvider> snapshot() {
        List<AgentToolProvider> result = new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (AgentToolProvider value) ->
                        value.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    value -> value.getDescriptor().getId()
                )
        );
        return Collections.unmodifiableList(result);
    }

    /**
     * Creates the globally unique Agent tool name for a plugin-local tool id.
     */
    public static String qualify(String pluginId, String toolId) {
        String safePlugin = pluginId.replaceAll("[^A-Za-z0-9]", "_");
        String safeTool = toolId.replaceAll("[^A-Za-z0-9]", "_");
        String prefix = "plugin_" + safePlugin + "_" + safeTool;
        prefix = prefix.toLowerCase(Locale.ROOT);
        if (prefix.length() > 80) prefix = prefix.substring(0, 80);
        return prefix + "_" + digest(pluginId + "\n" + toolId).substring(0, 8);
    }

    private static void requireCapability(PluginDescriptor descriptor) {
        if (!descriptor.getCapabilities().contains(
            PluginCapability.AGENT_TOOLS
        )) {
            throw new IllegalArgumentException(
                "AgentToolProvider requires AGENT_TOOLS"
            );
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(
                    String.format(Locale.ROOT, "%02x", item & 0xff)
                );
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
