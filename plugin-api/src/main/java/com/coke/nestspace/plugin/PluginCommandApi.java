package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginCommandApi {

    CompletableFuture<List<PluginCommandInfo>> listAsync();

    CompletableFuture<PluginCommandResult> invokeAsync(
        String commandId,
        String argumentsJson
    );
}
