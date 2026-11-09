package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class PluginUiPlugins {

    private static final ConcurrentHashMap<String, PluginUiProvider> PLUGINS =
        new ConcurrentHashMap<>();

    private PluginUiPlugins() {
    }

    public static void install(PluginUiProvider plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        if (!descriptor.getCapabilities().contains(
            PluginCapability.UI_PAGES
        ) && !descriptor.getCapabilities().contains(
            PluginCapability.UI_RENDERERS
        )) {
            throw new IllegalArgumentException(
                "PluginUiProvider requires UI_PAGES or UI_RENDERERS"
            );
        }
        PluginUiProvider previous = PLUGINS.putIfAbsent(
            descriptor.getId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "UI provider already installed: " + descriptor.getId()
            );
        }
    }

    public static void uninstall(String pluginId) {
        PLUGINS.remove(pluginId);
    }

    public static List<PluginUiProvider> snapshot() {
        List<PluginUiProvider> result = new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (PluginUiProvider value) ->
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
