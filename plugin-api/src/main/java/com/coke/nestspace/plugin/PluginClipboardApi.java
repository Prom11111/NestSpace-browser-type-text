package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface PluginClipboardApi {

    int MAX_TEXT_CHARS = 100_000;

    CompletableFuture<String> readText();

    CompletableFuture<Boolean> hasText();

    CompletableFuture<Void> writeText(String text, boolean sensitive);
}
