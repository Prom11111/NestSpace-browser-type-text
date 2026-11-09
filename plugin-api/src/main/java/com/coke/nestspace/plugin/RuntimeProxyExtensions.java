package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class RuntimeProxyExtensions {

    private static final ConcurrentHashMap<String, RuntimeProxyExtensionProvider>
        PLUGINS = new ConcurrentHashMap<>();

    private RuntimeProxyExtensions() {
    }

    public static void install(RuntimeProxyExtensionProvider plugin) {
        PluginDescriptor descriptor = plugin.getDescriptor();
        if (!descriptor.getCapabilities().contains(
            PluginCapability.RUNTIME_PROXY
        )) {
            throw new IllegalArgumentException(
                "RuntimeProxyExtensionProvider requires RUNTIME_PROXY"
            );
        }
        RuntimeProxyExtensionProvider previous = PLUGINS.putIfAbsent(
            descriptor.getId(),
            plugin
        );
        if (previous != null && previous != plugin) {
            throw new IllegalArgumentException(
                "runtime proxy extension already installed: " +
                    descriptor.getId()
            );
        }
    }

    public static void uninstall(String pluginId) {
        PLUGINS.remove(pluginId);
    }

    public static List<RuntimeProxyExtensionProvider> snapshot() {
        List<RuntimeProxyExtensionProvider> result =
            new ArrayList<>(PLUGINS.values());
        result.sort(
            Comparator
                .comparingInt(
                    (RuntimeProxyExtensionProvider value) ->
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
     * Resolves the highest-priority matching contribution. A failing external
     * plugin is ignored so it cannot break normal container startup.
     */
    public static RuntimeProxyContribution resolve(
        RuntimeProxyContext context
    ) {
        for (RuntimeProxyExtensionProvider provider : snapshot()) {
            RuntimeProxyContribution contribution = null;
            try {
                contribution = provider.resolveAsync(context)
                    .get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // A broken runtime extension must not affect the host.
            }
            if (contribution != null &&
                contribution.getFingerprint().equals(
                    context.getFingerprint()
                )
            ) {
                return contribution;
            }
        }
        return null;
    }
}
