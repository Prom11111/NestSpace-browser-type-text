package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class PluginLlmProviders {

    private static final ConcurrentHashMap<String, PluginLlmProvider> PLUGINS =
        new ConcurrentHashMap<>();

    private PluginLlmProviders() {
    }

    public static void install(PluginLlmProvider plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        if (!descriptor.getCapabilities().contains(
            PluginCapability.LLM_PROVIDERS
        )) {
            throw new IllegalArgumentException(
                "PluginLlmProvider requires LLM_PROVIDERS"
            );
        }
        PluginLlmProvider previous = PLUGINS.putIfAbsent(
            plugin.getProviderId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "LLM provider already installed: " + plugin.getProviderId()
            );
        }
    }

    public static void uninstallProvider(String providerId) {
        PLUGINS.remove(providerId);
    }

    public static List<PluginLlmProvider> snapshot() {
        List<PluginLlmProvider> result =
            new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (PluginLlmProvider value) ->
                        value.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(PluginLlmProvider::getProviderId)
        );
        return Collections.unmodifiableList(result);
    }
}
