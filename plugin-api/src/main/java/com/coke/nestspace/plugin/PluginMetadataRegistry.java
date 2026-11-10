package com.coke.nestspace.plugin;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Host-owned descriptor binding for one instantiated plugin.
 */
public final class PluginMetadataRegistry {

    private static final Map<Object, PluginDescriptor> DESCRIPTORS =
        new IdentityHashMap<>();

    private PluginMetadataRegistry() {
    }

    public static void bind(Object plugin, PluginDescriptor descriptor) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin is required");
        }
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        synchronized (DESCRIPTORS) {
            DESCRIPTORS.put(plugin, descriptor);
        }
    }

    public static PluginDescriptor require(Object plugin) {
        PluginDescriptor descriptor;
        synchronized (DESCRIPTORS) {
            descriptor = DESCRIPTORS.get(plugin);
        }
        if (descriptor == null) {
            throw new IllegalStateException(
                "plugin descriptor has not been bound from manifest.json"
            );
        }
        return descriptor;
    }

    public static void unbind(Object plugin) {
        synchronized (DESCRIPTORS) {
            DESCRIPTORS.remove(plugin);
        }
    }
}
