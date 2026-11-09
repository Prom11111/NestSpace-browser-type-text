package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PluginCommandPlugins {

    private static final ConcurrentHashMap<String, PluginCommandProvider> PLUGINS =
        new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private PluginCommandPlugins() {
    }

    public static void install(PluginCommandProvider plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        if (!descriptor.getCapabilities().contains(
            PluginCapability.COMMANDS
        )) {
            throw new IllegalArgumentException(
                "PluginCommandProvider requires COMMANDS"
            );
        }
        PluginCommandProvider previous = PLUGINS.putIfAbsent(
            descriptor.getId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "command provider already installed: " +
                    descriptor.getId()
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

    public static List<PluginCommandProvider> snapshot() {
        List<PluginCommandProvider> result =
            new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (PluginCommandProvider value) ->
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
