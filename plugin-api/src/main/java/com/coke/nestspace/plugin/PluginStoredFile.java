package com.coke.nestspace.plugin;

import java.util.Objects;

/**
 * Metadata for a regular file in plugin-private storage.
 */
public final class PluginStoredFile {

    private final String path;
    private final long sizeBytes;
    private final long lastModifiedMs;

    /**
     * @param path path relative to the plugin storage root
     * @param sizeBytes file size in bytes
     * @param lastModifiedMs last modification time in epoch milliseconds
     */
    public PluginStoredFile(
        String path,
        long sizeBytes,
        long lastModifiedMs
    ) {
        this.path = Objects.requireNonNull(path, "path");
        this.sizeBytes = sizeBytes;
        this.lastModifiedMs = lastModifiedMs;
    }

    /** Returns the path relative to the plugin storage root. */
    public String getPath() {
        return path;
    }

    /** Returns the file size in bytes. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /** Returns the last modification time in epoch milliseconds. */
    public long getLastModifiedMs() {
        return lastModifiedMs;
    }
}
