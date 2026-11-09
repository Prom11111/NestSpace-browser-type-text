package com.coke.nestspace.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local catalog for runtime proxy plugins.
 *
 * The catalog is intentionally Java-only at its public boundary so external
 * plugins do not need Kotlin runtime defaults, data classes or coroutines.
 */
public final class RuntimeProxyPlugins {

    private static final Object LOCK = new Object();
    private static final Map<String, RuntimeProxyPlugin> PLUGINS =
        new LinkedHashMap<>();

    private RuntimeProxyPlugins() {
    }

    public static void install(RuntimeProxyPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        PluginDescriptor descriptor = validateDescriptor(plugin);
        List<RuntimeLaunchPolicy> policies = validatePolicies(plugin);

        synchronized (LOCK) {
            List<RuntimeLaunchPolicy> claimed = new ArrayList<>();
            for (RuntimeProxyPlugin current : PLUGINS.values()) {
                if (current.getDescriptor().getId().equals(descriptor.getId())) {
                    continue;
                }
                for (RuntimeLaunchPolicy policy : current.launchPolicies()) {
                    claimed.add(policy);
                }
            }
            for (RuntimeLaunchPolicy policy : policies) {
                if (claimed.stream().anyMatch(policy::overlaps)) {
                    throw new IllegalArgumentException(
                        "duplicate runtime target policy"
                    );
                }
                claimed.add(policy);
            }
            PLUGINS.put(descriptor.getId(), plugin);
        }
    }

    public static void uninstall(String pluginId) {
        synchronized (LOCK) {
            PLUGINS.remove(pluginId);
        }
    }

    public static List<PluginDescriptor> snapshot() {
        synchronized (LOCK) {
            List<PluginDescriptor> result = new ArrayList<>(PLUGINS.size());
            for (RuntimeProxyPlugin plugin : PLUGINS.values()) {
                result.add(plugin.getDescriptor());
            }
            return Collections.unmodifiableList(result);
        }
    }

    public static RuntimeProxyResolution resolve(RuntimeProxyContext context) {
        Objects.requireNonNull(context, "context");
        List<RuntimeProxyPlugin> ordered;
        synchronized (LOCK) {
            ordered = new ArrayList<>(PLUGINS.values());
        }
        Collections.sort(
            ordered,
            Comparator
                .comparingInt(
                    (RuntimeProxyPlugin plugin) ->
                        plugin.getDescriptor().getPriority()
                )
                .reversed()
                .thenComparing(
                    plugin -> plugin.getDescriptor().getId()
                )
        );
        for (RuntimeProxyPlugin plugin : ordered) {
            PluginDescriptor descriptor = plugin.getDescriptor();
            for (RuntimeLaunchPolicy policy : plugin.launchPolicies()) {
                if (policy.matches(context)) {
                    return new RuntimeProxyResolution(
                        descriptor.getId(),
                        descriptor.getVersion(),
                        policy.getId(),
                        policy.getPackageName(),
                        policy.getVersionMatcher(),
                        context.getVersionCode(),
                        policy.getMode(),
                        policy.getReason()
                    );
                }
            }
        }
        return null;
    }

    private static PluginDescriptor validateDescriptor(RuntimeProxyPlugin plugin) {
        PluginDescriptor descriptor =
            Objects.requireNonNull(plugin.getDescriptor(), "descriptor");
        if (descriptor.getId().isEmpty()) {
            throw new IllegalArgumentException("plugin id is required");
        }
        if (descriptor.getName().isEmpty()) {
            throw new IllegalArgumentException("plugin name is required");
        }
        if (descriptor.getVersion().isEmpty()) {
            throw new IllegalArgumentException("plugin version is required");
        }
        if (descriptor.getApiVersion() != PluginDescriptor.API_VERSION) {
            throw new IllegalArgumentException(
                "unsupported runtime proxy plugin API "
                    + descriptor.getApiVersion()
            );
        }
        return descriptor;
    }

    private static List<RuntimeLaunchPolicy> validatePolicies(
        RuntimeProxyPlugin plugin
    ) {
        List<RuntimeLaunchPolicy> policies =
            Objects.requireNonNull(plugin.launchPolicies(), "policies");
        for (RuntimeLaunchPolicy policy : policies) {
            Objects.requireNonNull(policy, "policy");
            if (policies.stream().anyMatch(other ->
                other != policy && policy.overlaps(other)
            )) {
                throw new IllegalArgumentException(
                    "plugin contains duplicate runtime target policies"
                );
            }
        }
        return policies;
    }
}
