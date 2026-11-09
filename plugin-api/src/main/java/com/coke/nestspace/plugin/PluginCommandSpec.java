package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * A command that can be invoked by the Agent, UI or another plugin.
 */
public final class PluginCommandSpec {

    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    public static final int MAX_TIMEOUT_MS = 120_000;

    private final String id;
    private final String title;
    private final String description;
    private final String argumentsSchemaJson;
    private final boolean mutating;
    private final int timeoutMs;

    public PluginCommandSpec(
        String id,
        String title,
        String description,
        String argumentsSchemaJson
    ) {
        this(
            id,
            title,
            description,
            argumentsSchemaJson,
            true,
            DEFAULT_TIMEOUT_MS
        );
    }

    public PluginCommandSpec(
        String id,
        String title,
        String description,
        String argumentsSchemaJson,
        boolean mutating,
        int timeoutMs
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = limit(title, 100);
        this.description = limit(description, 1_000);
        this.argumentsSchemaJson = argumentsSchemaJson == null ||
            argumentsSchemaJson.isBlank()
            ? "{\"type\":\"object\",\"properties\":{}}"
            : argumentsSchemaJson.trim();
        this.mutating = mutating;
        this.timeoutMs = Math.max(1_000, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        if (!this.id.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid command id");
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException("command title is required");
        }
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

    public int getTimeoutMs() {
        return timeoutMs;
    }

    private static String limit(String value, int maxChars) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= maxChars
            ? trimmed
            : trimmed.substring(0, maxChars);
    }
}
