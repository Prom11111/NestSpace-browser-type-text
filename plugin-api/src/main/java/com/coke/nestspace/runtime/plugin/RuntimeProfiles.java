package com.coke.nestspace.runtime.plugin;

import com.coke.nestspace.plugin.PluginCapability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local registry for target runtime profiles.
 */
public final class RuntimeProfiles {

    private static final Object LOCK = new Object();
    private static final Map<String, RuntimeProfileProvider> RESOLVERS =
        new LinkedHashMap<>();

    private RuntimeProfiles() {
    }

    public static void install(RuntimeProfileProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("profile provider is required");
        }
        String id = provider.getDescriptor() == null
            ? ""
            : provider.getDescriptor().getId().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("plugin id is required");
        }
        if (!provider.getDescriptor().getCapabilities().contains(
            PluginCapability.RUNTIME_HOOKS
        )) {
            throw new IllegalArgumentException(
                "RuntimeProfileProvider requires RUNTIME_HOOKS"
            );
        }
        synchronized (LOCK) {
            RESOLVERS.put(id, provider);
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
        List<RuntimeProfileProvider> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(RESOLVERS.values());
        }
        ordered.sort(
            Comparator
                .comparingInt(
                    (RuntimeProfileProvider provider) ->
                        provider.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    provider -> provider.getDescriptor().getId()
                )
        );
        for (RuntimeProfileProvider provider : ordered) {
            RuntimeProfile profile = provider.resolve(spec, loader);
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
