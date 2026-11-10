package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface AgentModelHookPlugin extends PluginMetadata {

    default CompletableFuture<AgentModelRequestDecision> beforeRequestAsync(
        AgentModelRequestContext context
    ) {
        return CompletableFuture.completedFuture(
            AgentModelRequestDecision.proceed()
        );
    }
}
