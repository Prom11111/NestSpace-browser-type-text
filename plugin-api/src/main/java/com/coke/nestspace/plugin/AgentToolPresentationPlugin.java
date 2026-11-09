package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface AgentToolPresentationPlugin {

    PluginDescriptor getDescriptor();

    default CompletableFuture<AgentToolPresentation> presentAsync(
        AgentToolPresentationContext context
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
