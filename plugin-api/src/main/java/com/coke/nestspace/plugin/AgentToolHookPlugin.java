package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Observes or guards every Agent tool invocation.
 */
public interface AgentToolHookPlugin {

    PluginDescriptor getDescriptor();

    default CompletableFuture<AgentToolCallDecision> beforeToolCallAsync(
        AgentToolCallContext context
    ) {
        return CompletableFuture.completedFuture(
            AgentToolCallDecision.proceed()
        );
    }

    default CompletableFuture<Void> afterToolCallAsync(
        AgentToolResultContext context
    ) {
        return CompletableFuture.completedFuture(null);
    }
}
