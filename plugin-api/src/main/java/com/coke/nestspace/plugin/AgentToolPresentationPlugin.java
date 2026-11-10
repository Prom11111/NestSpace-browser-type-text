package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface AgentToolPresentationPlugin extends PluginMetadata {

    default CompletableFuture<AgentToolPresentation> presentAsync(
        AgentToolPresentationContext context
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
