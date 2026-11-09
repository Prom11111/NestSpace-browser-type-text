package com.coke.nestspace.plugin;

public final class PluginExportedFile {

    private final String displayName;
    private final String mimeType;
    private final long sizeBytes;

    public PluginExportedFile(
        String displayName,
        String mimeType,
        long sizeBytes
    ) {
        this.displayName = displayName == null ? "" : displayName;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.sizeBytes = Math.max(0, sizeBytes);
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
