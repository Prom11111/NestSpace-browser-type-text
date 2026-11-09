package com.coke.nestspace.plugin;

import java.util.Objects;

public final class PluginShareRequest {

    public static final int MAX_TEXT_CHARS = 100_000;
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final String title;
    private final String text;
    private final String fileName;
    private final String mimeType;
    private final byte[] data;

    public PluginShareRequest(
        String title,
        String text,
        String fileName,
        String mimeType,
        byte[] data
    ) {
        this.title = limit(title, 120);
        this.text = limit(text, MAX_TEXT_CHARS);
        this.fileName = limit(fileName, 180);
        this.mimeType = mimeType == null || mimeType.isBlank()
            ? "application/octet-stream"
            : mimeType.trim().toLowerCase();
        this.data = data == null ? new byte[0] : data.clone();
        if (this.text.isEmpty() && this.data.length == 0) {
            throw new IllegalArgumentException(
                "share text or file data is required"
            );
        }
        if (this.data.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("shared file exceeds 10 MB");
        }
        if (this.data.length > 0 && this.fileName.isEmpty()) {
            throw new IllegalArgumentException(
                "fileName is required when sharing bytes"
            );
        }
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public byte[] getData() {
        return data.clone();
    }

    public boolean hasFile() {
        return data.length > 0;
    }

    private static String limit(String value, int maxChars) {
        String text = (value == null ? "" : value).trim();
        return text.length() <= maxChars
            ? text
            : text.substring(0, maxChars);
    }
}
