package com.coke.nestspace.plugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin-private TTL cache. Values are isolated by plugin id and may be
 * removed by the host at any time.
 */
public interface PluginCacheApi {

    int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    int MAX_KEY_CHARS = 128;

    CompletableFuture<Void> put(
        String key,
        byte[] value,
        long ttlSeconds
    );

    CompletableFuture<byte[]> get(String key);

    CompletableFuture<Boolean> contains(String key);

    CompletableFuture<Void> remove(String key);

    CompletableFuture<Void> clear();

    CompletableFuture<Long> sizeBytes();

    default CompletableFuture<Void> putText(
        String key,
        String value,
        long ttlSeconds
    ) {
        return put(
            key,
            (value == null ? "" : value).getBytes(StandardCharsets.UTF_8),
            ttlSeconds
        );
    }

    default CompletableFuture<String> getText(String key) {
        return get(key).thenApply(
            bytes -> new String(bytes, StandardCharsets.UTF_8)
        );
    }
}
