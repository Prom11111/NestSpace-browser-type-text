package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PluginHttpRequest {

    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_TIMEOUT_MS = 30_000;

    private final PluginHttpMethod method;
    private final String url;
    private final Map<String, String> headers;
    private final String body;
    private final int timeoutMs;

    public PluginHttpRequest(
        PluginHttpMethod method,
        String url,
        Map<String, String> headers,
        String body,
        int timeoutMs
    ) {
        this.method = Objects.requireNonNull(method, "method");
        this.url = Objects.requireNonNull(url, "url").trim();
        this.headers = headers == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? "" : body;
        this.timeoutMs = Math.max(1_000, Math.min(timeoutMs, MAX_TIMEOUT_MS));
        if (this.url.isEmpty()) {
            throw new IllegalArgumentException("url is required");
        }
        if (this.body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("request body exceeds 64 KB");
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

    public int getTimeoutMs() {
        return timeoutMs;
    }
}
