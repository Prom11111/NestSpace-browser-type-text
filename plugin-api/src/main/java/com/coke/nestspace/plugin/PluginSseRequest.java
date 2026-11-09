package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Options for a streaming Server-Sent Events request.
 */
public final class PluginSseRequest {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    public static final int MAX_CONNECT_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
    public static final int MAX_READ_TIMEOUT_MS = 300_000;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_HEADERS = 32;

    private final PluginHttpMethod method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PluginSseRequest(String url) {
        this(
            PluginHttpMethod.GET,
            url,
            Collections.emptyMap(),
            "",
            DEFAULT_CONNECT_TIMEOUT_MS,
            DEFAULT_READ_TIMEOUT_MS
        );
    }

    public PluginSseRequest(
        PluginHttpMethod method,
        String url,
        Map<String, String> headers,
        String body,
        int connectTimeoutMs,
        int readTimeoutMs
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.url = Objects.requireNonNull(url, "url").trim();
        this.headers = headers == null || headers.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
        this.connectTimeoutMs = Math.max(
            1_000,
            Math.min(connectTimeoutMs, MAX_CONNECT_TIMEOUT_MS)
        );
        this.readTimeoutMs = readTimeoutMs <= 0
            ? 0
            : Math.max(5_000, Math.min(readTimeoutMs, MAX_READ_TIMEOUT_MS));
        if (this.url.isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        if (this.headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("too many SSE headers");
        }
        if (this.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("SSE body exceeds 64 KB");
        }
        if (!this.body.isEmpty() &&
            (method == PluginHttpMethod.GET || method == PluginHttpMethod.DELETE)
        ) {
            throw new IllegalArgumentException(
                "GET and DELETE cannot include an SSE request body"
            );
        }
    }

    public PluginHttpMethod getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }
}
