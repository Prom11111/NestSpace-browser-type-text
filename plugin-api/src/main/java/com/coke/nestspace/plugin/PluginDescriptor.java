package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Stable plugin metadata shared with Java and Kotlin plugins.
 */
public final class PluginDescriptor {

    public static final int API_VERSION = 2;

    private final String id;
    private final String name;
    private final String version;
    private final int apiVersion;
    private final int priority;
    private final Set<PluginCapability> capabilities;

    public PluginDescriptor(
        String id,
        String name,
        String version,
        int apiVersion
    ) {
        this(id, name, version, apiVersion, 0, Collections.emptySet());
    }

    public PluginDescriptor(
        String id,
        String name,
        String version,
        int apiVersion,
        int priority,
        Set<PluginCapability> capabilities
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.name = Objects.requireNonNull(name, "name").trim();
        this.version = Objects.requireNonNull(version, "version").trim();
        this.apiVersion = apiVersion;
        this.priority = priority;
        this.capabilities = capabilities == null || capabilities.isEmpty()
            ? Collections.emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public int getApiVersion() {
        return apiVersion;
    }

    public int getPriority() {
        return priority;
    }

    public Set<PluginCapability> getCapabilities() {
        return capabilities;
    }
}
