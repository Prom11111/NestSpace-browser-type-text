package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin-scoped file storage. Paths are always relative to a private root
 * directory owned by the host.
 */
public interface PluginStorageApi {

    /** Maximum size of one file written by a plugin. */
    int MAX_FILE_BYTES = 10 * 1024 * 1024;

    /** Maximum number of bytes returned by one read. */
    int MAX_READ_BYTES = 10 * 1024 * 1024;

    /** Maximum number of files returned by one recursive list operation. */
    int MAX_LIST_ENTRIES = 1_000;

    /**
     * Atomically writes or replaces a file under the plugin's private root.
     */
    CompletableFuture<PluginStoredFile> writeBytes(
        String path,
        byte[] data
    );

    /**
     * Reads a file without returning more than {@code maxBytes}.
     */
    CompletableFuture<byte[]> readBytes(String path, int maxBytes);

    /**
     * Deletes a regular file under the plugin's private root.
     */
    CompletableFuture<Void> delete(String path);

    /**
     * Recursively lists regular files below {@code directory}. An empty
     * directory lists from the plugin root.
     */
    CompletableFuture<List<PluginStoredFile>> list(String directory);

    /**
     * UTF-8 convenience wrapper around {@link #writeBytes(String, byte[])}.
     */
    default CompletableFuture<PluginStoredFile> writeText(
        String path,
        String text
    ) {
        return writeBytes(
            path,
            (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * UTF-8 convenience wrapper around {@link #readBytes(String, int)}.
     */
    default CompletableFuture<String> readText(String path, int maxBytes) {
        return readBytes(path, maxBytes).thenApply(
            bytes -> new String(bytes, StandardCharsets.UTF_8)
        );
    }
}
