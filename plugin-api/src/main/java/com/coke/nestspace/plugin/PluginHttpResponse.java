package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginHttpResponse {

    private final int statusCode;
    private final String contentType;
    private final String body;

    public PluginHttpResponse(int statusCode, String contentType, String body) {
        this.statusCode = statusCode;
        this.contentType = contentType == null ? "" : contentType;
        this.body = Objects.requireNonNull(body, "body");
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }
}
