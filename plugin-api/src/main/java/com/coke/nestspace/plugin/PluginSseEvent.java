package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * One dispatched Server-Sent Event.
 */
public final class PluginSseEvent {

    private final String id;
    private final String event;
    private final String data;
    private final int retryMs;

    public PluginSseEvent(
        String id,
        String event,
        String data,
        int retryMs
    ) {
        this.id = id == null ? "" : id;
        this.event = event == null || event.isBlank()
            ? "message"
            : event;
        this.data = Objects.requireNonNull(data, "data");
        this.retryMs = Math.max(0, retryMs);
    }

    public String getId() {
        return id;
    }

    public String getEvent() {
        return event;
    }

    public String getData() {
        return data;
    }

    public int getRetryMs() {
        return retryMs;
    }
}
