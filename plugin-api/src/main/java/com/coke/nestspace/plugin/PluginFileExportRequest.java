package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Requests a user-visible file export through the system document picker.
 */
public final class PluginFileExportRequest {

    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private final String suggestedName;
    private final String mimeType;
    private final byte[] data;

    public PluginFileExportRequest(
        String suggestedName,
        String mimeType,
        byte[] data
    ) {
        this.suggestedName = sanitizeName(suggestedName);
        this.mimeType = mimeType == null || mimeType.isBlank()
            ? "application/octet-stream"
            : mimeType.trim().toLowerCase();
        this.data = Objects.requireNonNull(data, "data").clone();
        if (!this.suggestedName.isEmpty() && this.suggestedName.length() > 180) {
            throw new IllegalArgumentException("file name is too long");
        }
        if (this.data.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("export file exceeds 32 MB");
        }
    }

    public String getSuggestedName() {
        return suggestedName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public byte[] getData() {
        return data.clone();
    }

    private static String sanitizeName(String value) {
        String name = value == null ? "" : value.trim();
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replace("\u0000", "");
        return name;
    }
}
