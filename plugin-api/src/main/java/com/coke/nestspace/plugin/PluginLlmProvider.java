package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface PluginLlmProvider extends PluginMetadata {

    String getProviderId();

    String getDisplayName();

    List<PluginLlmModel> getModels();

    /**
     * Starts one asynchronous model request.
     *
     * The returned future completes with the final response or fails with the
     * upstream error. If
     * {@link PluginLlmRequest#isStreamEnabled()} is true, text deltas should be
     * passed to {@code onTextDelta} before the future completes. Cancelling the
     * future with {@code cancel(true)} should abort the upstream request.
     */
    CompletableFuture<PluginLlmResponse> executeAsync(
        PluginLlmRequest request,
        Consumer<String> onTextDelta
    );
}
