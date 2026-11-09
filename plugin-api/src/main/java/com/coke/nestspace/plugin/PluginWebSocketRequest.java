package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket connection options. Both ws and wss URLs are accepted by the
 * host policy.
 */
public final class PluginWebSocketRequest {

    public static final int DEFAULT_TIMEOUT_MS = 15_000;
    public static final int MAX_TIMEOUT_MS = 30_000;
    public static final int MAX_HEADERS = 32;
    public static final int MAX_HEADER_NAME_CHARS = 128;
    public static final int MAX_HEADER_VALUE_CHARS = 2_048;
    public static final int MAX_URL_CHARS = 8_192;

    private final String url;
    private final Map<String, String> headers;
    private final int timeoutMs;

    public PluginWebSocketRequest(String url) {
        this(url, Collections.emptyMap(), DEFAULT_TIMEOUT_MS);
    }

    public PluginWebSocketRequest(
        String url,
        Map<String, String> headers,
        int timeoutMs
    ) {
        this.url = Objects.requireNonNull(url, "url").trim();
        this.headers = headers == null || headers.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.timeoutMs = Math.max(
            1_000,
            Math.min(timeoutMs, MAX_TIMEOUT_MS)
        );
        if (this.url.isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        if (this.url.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("WebSocket URL is too long");
        }
        if (this.headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("too many WebSocket headers");
        }
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "header name");
            String value = Objects.requireNonNull(
                entry.getValue(),
                "header value"
            );
            if (name.isBlank() || name.length() > MAX_HEADER_NAME_CHARS) {
                throw new IllegalArgumentException(
                    "invalid WebSocket header name"
                );
            }
            if (value.length() > MAX_HEADER_VALUE_CHARS) {
                throw new IllegalArgumentException(
                    "WebSocket header value is too long"
                );
            }
        }
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
