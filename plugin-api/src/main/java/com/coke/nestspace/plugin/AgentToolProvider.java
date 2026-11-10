package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Contributes executable tools to the local Agent.
 */
public interface AgentToolProvider extends PluginMetadata {

    CompletableFuture<List<PluginToolSpec>> listToolsAsync();

    CompletableFuture<PluginToolResult> invokeAsync(PluginToolCall call);
}
