package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginLlmProvider {

    PluginDescriptor getDescriptor();

    String getProviderId();

    String getDisplayName();

    List<PluginLlmModel> getModels();

    CompletableFuture<PluginLlmResponse> completeAsync(
        PluginLlmRequest request
    );

    PluginLlmStream stream(
        PluginLlmRequest request,
        PluginLlmListener listener
    );
}
