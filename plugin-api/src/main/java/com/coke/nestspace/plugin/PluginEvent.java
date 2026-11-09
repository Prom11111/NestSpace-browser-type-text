package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginEvent {

    private final String type;
    private final String source;
    private final String payloadJson;
    private final long timestampMs;

    public PluginEvent(
        String type,
        String source,
        String payloadJson,
        long timestampMs
    ) {
        this.type = Objects.requireNonNull(type, "type").trim();
        this.source = source == null ? "" : source;
        this.payloadJson = payloadJson == null || payloadJson.isBlank()
            ? "{}"
            : payloadJson;
        this.timestampMs = timestampMs <= 0
            ? System.currentTimeMillis()
            : timestampMs;
        if (this.type.isEmpty() || this.type.length() > 160) {
            throw new IllegalArgumentException("invalid plugin event type");
        }
        if (this.payloadJson.length() > 1_000_000) {
            throw new IllegalArgumentException("plugin event exceeds 1 MB");
        }
    }

    public String getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public long getTimestampMs() {
        return timestampMs;
    }
}
