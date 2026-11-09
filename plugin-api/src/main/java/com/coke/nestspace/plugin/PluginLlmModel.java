package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginLlmModel {

    private final String id;
    private final String displayName;
    private final int contextTokens;
    private final boolean supportsVision;

    public PluginLlmModel(
        String id,
        String displayName,
        int contextTokens,
        boolean supportsVision
    ) {
        this.id = Objects.requireNonNull(id, "id").trim();
        this.displayName = displayName == null ? "" : displayName.trim();
        this.contextTokens = Math.max(1_000, contextTokens);
        this.supportsVision = supportsVision;
        if (this.id.isEmpty() || this.id.length() > 160) {
            throw new IllegalArgumentException("invalid model id");
        }
        if (this.displayName.isEmpty()) {
            throw new IllegalArgumentException("model displayName is required");
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getContextTokens() {
        return contextTokens;
    }

    public boolean supportsVision() {
        return supportsVision;
    }
}
