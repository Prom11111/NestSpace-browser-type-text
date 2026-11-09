package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin-scoped non-secret preferences.
 */
public interface PluginSettingsApi {

    int MAX_KEY_CHARS = 128;
    int MAX_STRING_CHARS = 64 * 1024;

    CompletableFuture<Boolean> contains(String key);

    CompletableFuture<String> getString(String key, String fallback);

    CompletableFuture<Boolean> getBoolean(String key, boolean fallback);

    CompletableFuture<Long> getLong(String key, long fallback);

    CompletableFuture<Void> putString(String key, String value);

    CompletableFuture<Void> putBoolean(String key, boolean value);

    CompletableFuture<Void> putLong(String key, long value);

    CompletableFuture<Void> remove(String key);

    CompletableFuture<List<String>> keys();
}
