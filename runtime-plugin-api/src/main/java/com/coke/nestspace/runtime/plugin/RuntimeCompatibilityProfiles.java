package com.coke.nestspace.runtime.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local registry for runtime compatibility profiles.
 */
public final class RuntimeCompatibilityProfiles {

    private static final Object LOCK = new Object();
    private static final Map<String, RuntimeCompatibilityProfileResolver> RESOLVERS =
        new LinkedHashMap<>();

    private RuntimeCompatibilityProfiles() {
    }

    public static void register(
        String pluginId,
        RuntimeCompatibilityProfileResolver resolver
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

    public static RuntimeCompatibilityProfile resolve(
        RuntimeCompatLaunchSpec spec,
        ClassLoader loader
    ) {
        List<RuntimeCompatibilityProfileResolver> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(RESOLVERS.values());
        }
        for (RuntimeCompatibilityProfileResolver resolver : ordered) {
            RuntimeCompatibilityProfile profile = resolver.resolve(spec, loader);
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
