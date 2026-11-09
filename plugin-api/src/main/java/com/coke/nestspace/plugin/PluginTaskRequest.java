package com.coke.nestspace.plugin;

public final class PluginTaskRequest {

    public static final int DEFAULT_TIMEOUT_MS = 60_000;
    public static final int MAX_TIMEOUT_MS = 30 * 60_000;

    private final String name;
    private final int timeoutMs;

    public PluginTaskRequest(String name, int timeoutMs) {
        this.name = name == null ? "" : name.trim();
        this.timeoutMs = Math.max(
            1_000,
            Math.min(timeoutMs, MAX_TIMEOUT_MS)
        );
        if (this.name.isEmpty()) {
            throw new IllegalArgumentException("task name is required");
        }
    }

    public String getName() {
        return name;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
