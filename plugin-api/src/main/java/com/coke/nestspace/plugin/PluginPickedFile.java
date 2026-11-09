package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

/**
 * A user-selected read-only file. The plugin never receives a filesystem path.
 */
public interface PluginPickedFile {

    String getDisplayName();

    String getMimeType();

    long getSizeBytes();

    CompletableFuture<byte[]> readBytes(int maxBytes);
}
