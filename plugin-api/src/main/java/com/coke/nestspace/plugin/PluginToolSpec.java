package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One tool contributed to the Agent by a plugin.
 */
public final class PluginToolSpec {

    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    public static final int MAX_TIMEOUT_MS = 120_000;
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;

    private static final Pattern ID =
        Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    private final String id;
    private final String title;
    private final String description;
    private final String inputSchemaJson;
    private final boolean mutating;
    private final int timeoutMs;

    public PluginToolSpec(
        String id,
        String title,
        String description,
        String inputSchemaJson,
        boolean mutating
    ) {
        this(
            id,
            title,
            description,
            inputSchemaJson,
            mutating,
            DEFAULT_TIMEOUT_MS
        );
    }

    public PluginToolSpec(
        String id,
        String title,
        String description,
        String inputSchemaJson,
        boolean mutating,
        int timeoutMs
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.title = limit(title, 100);
        this.description = limit(description, 2_000);
        this.inputSchemaJson = Objects.requireNonNull(
            inputSchemaJson,
            "inputSchemaJson"
        ).trim();
        this.mutating = mutating;
        this.timeoutMs = Math.max(1_000, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        if (!ID.matcher(this.id).matches()) {
            throw new IllegalArgumentException(
                "tool id must contain 1-64 letters, digits, '.', '_' or '-'"
            );
        }
        if (this.title.isEmpty()) {
            throw new IllegalArgumentException("tool title is required");
        }
        if (this.inputSchemaJson.isEmpty()) {
            throw new IllegalArgumentException("tool input schema is required");
        }
        if (this.inputSchemaJson.getBytes(StandardCharsets.UTF_8).length >
            MAX_SCHEMA_BYTES) {
            throw new IllegalArgumentException("tool input schema exceeds 64 KB");
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

    public String getInputSchemaJson() {
        return inputSchemaJson;
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
