package com.coke.nestspace.plugin;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Optional Java extension point for prompt context and direct Q&A.
 */
public interface AgentHookPlugin extends PluginMetadata {

    default List<AgentPromptContribution> promptContributions(
        AgentPromptContext context
    ) {
        return Collections.emptyList();
    }

    default CompletableFuture<AgentQuestionResult> answerAsync(
        AgentQuestionContext context
    ) {
        return CompletableFuture.completedFuture(
            AgentQuestionResult.notHandled()
        );
    }
}
