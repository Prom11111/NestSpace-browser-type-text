package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginCommandProvider extends PluginMetadata {

    CompletableFuture<List<PluginCommandSpec>> listCommandsAsync();

    CompletableFuture<PluginCommandResult> invokeCommandAsync(
        PluginCommandCall call
    );
}
