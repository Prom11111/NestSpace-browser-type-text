package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Result returned by a plugin tool to the Agent.
 */
public final class PluginToolResult {

    public static final int MAX_TEXT_CHARS = 1_000_000;
    public static final int MAX_IMAGE_BASE64_CHARS = 8 * 1024 * 1024;

    private final boolean successful;
    private final String text;
    private final String errorCode;
    private final String imageBase64;
    private final String imageMimeType;

    public PluginToolResult(
        boolean successful,
        String text,
        String errorCode,
        String imageBase64,
        String imageMimeType
    ) {
        this.successful = successful;
        this.text = limit(
            Objects.requireNonNull(text, "text"),
            MAX_TEXT_CHARS
        );
        this.errorCode = limit(errorCode == null ? "" : errorCode, 100);
        this.imageBase64 = limit(
            imageBase64 == null ? "" : imageBase64,
            MAX_IMAGE_BASE64_CHARS
        );
        this.imageMimeType = limit(
            imageMimeType == null || imageMimeType.isBlank()
                ? "image/png"
                : imageMimeType,
            100
        );
    }

    public static PluginToolResult success(String text) {
        return new PluginToolResult(true, text, "", "", "image/png");
    }

    public static PluginToolResult failure(String text, String errorCode) {
        return new PluginToolResult(false, text, errorCode, "", "image/png");
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getText() {
        return text;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public String getImageMimeType() {
        return imageMimeType;
    }

    private static String limit(String value, int maxChars) {
        return value.length() <= maxChars
            ? value
            : value.substring(0, maxChars);
    }
}
