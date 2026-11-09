package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Contributes executable tools to the local Agent.
 */
public interface AgentToolProvider {

    PluginDescriptor getDescriptor();

    CompletableFuture<List<PluginToolSpec>> listToolsAsync();

    CompletableFuture<PluginToolResult> invokeAsync(PluginToolCall call);
}
