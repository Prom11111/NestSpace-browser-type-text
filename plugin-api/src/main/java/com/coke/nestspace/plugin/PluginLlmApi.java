package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginLlmApi {

    CompletableFuture<List<PluginLlmModel>> listModelsAsync();
}
