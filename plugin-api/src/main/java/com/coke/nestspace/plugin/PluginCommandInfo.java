package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Qualified command exposed by the plugin host.
 */
public final class PluginCommandInfo {

    private final String pluginId;
    private final String id;
    private final String title;
    private final String description;
    private final String argumentsSchemaJson;
    private final boolean mutating;

    public PluginCommandInfo(
        String pluginId,
        PluginCommandSpec spec
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.id = pluginId + "/" + spec.getId();
        this.title = spec.getTitle();
        this.description = spec.getDescription();
        this.argumentsSchemaJson = spec.getArgumentsSchemaJson();
        this.mutating = spec.isMutating();
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getArgumentsSchemaJson() {
        return argumentsSchemaJson;
    }

    public boolean isMutating() {
        return mutating;
    }
}
