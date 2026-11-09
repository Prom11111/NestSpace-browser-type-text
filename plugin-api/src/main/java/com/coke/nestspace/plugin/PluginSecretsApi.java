package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Android Keystore-backed secret storage. Values are encrypted at rest.
 */
public interface PluginSecretsApi {

    int MAX_KEY_CHARS = 128;
    int MAX_SECRET_CHARS = 64 * 1024;

    CompletableFuture<Void> put(String key, String value);

    CompletableFuture<String> get(String key, String fallback);

    CompletableFuture<Boolean> contains(String key);

    CompletableFuture<Void> remove(String key);

    CompletableFuture<List<String>> keys();
}
