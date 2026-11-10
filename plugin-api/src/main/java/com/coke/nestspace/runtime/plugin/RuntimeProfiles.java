package com.coke.nestspace.runtime.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local registry for target runtime profiles.
 */
public final class RuntimeProfiles {

    private static final Object LOCK = new Object();
    private static final Map<String, RuntimeProfileResolver> RESOLVERS =
        new LinkedHashMap<>();

    private RuntimeProfiles() {
    }

    public static void register(
        String pluginId,
        RuntimeProfileResolver resolver
    ) {
        String id = pluginId == null ? "" : pluginId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("pluginId is required");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("resolver is required");
        }
        synchronized (LOCK) {
            RESOLVERS.put(id, resolver);
        }
    }

    public static void unregister(String pluginId) {
        synchronized (LOCK) {
            RESOLVERS.remove(pluginId);
        }
    }

    public static RuntimeProfile resolve(
        RuntimeLaunchSpec spec,
        ClassLoader loader
    ) {
        List<RuntimeProfileResolver> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(RESOLVERS.values());
        }
        for (RuntimeProfileResolver resolver : ordered) {
            RuntimeProfile profile = resolver.resolve(spec, loader);
            if (profile != null) return profile;
        }
        return null;
    }

    public static void clear() {
        synchronized (LOCK) {
            RESOLVERS.clear();
        }
    }
}
