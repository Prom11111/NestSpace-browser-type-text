package com.coke.nestspace.plugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local registry for plugin-owned Settings Views.
 */
public final class PluginSettingsViewPlugins {

    private static final class Entry {
        private final PluginSettingsViewProvider provider;
        private final PluginContext host;

        private Entry(
            PluginSettingsViewProvider provider,
            PluginContext host
        ) {
            this.provider = provider;
            this.host = host;
        }
    }

    private static final Map<String, Entry> PLUGINS =
        new ConcurrentHashMap<>();

    private PluginSettingsViewPlugins() {
    }

    public static void install(
        PluginSettingsViewProvider provider,
        PluginContext host
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(host, "host");
        PluginDescriptor descriptor = Objects.requireNonNull(
            provider.getDescriptor(),
            "descriptor"
        );
        if (!descriptor.getCapabilities().contains(
            PluginCapability.SETTINGS
        )) {
            throw new IllegalArgumentException(
                "PluginSettingsViewProvider requires SETTINGS"
            );
        }
        if (!descriptor.getId().equals(host.getPluginId())) {
            throw new IllegalArgumentException(
                "Settings provider does not match its plugin context"
            );
        }
        PLUGINS.put(
            descriptor.getId(),
            new Entry(provider, host)
        );
    }

    public static void uninstall(String pluginId) {
        PLUGINS.remove(pluginId);
    }

    public static PluginSettingsViewProvider find(String pluginId) {
        Entry entry = PLUGINS.get(pluginId);
        return entry == null ? null : entry.provider;
    }

    public static PluginContext findHost(String pluginId) {
        Entry entry = PLUGINS.get(pluginId);
        return entry == null ? null : entry.host;
    }
}
