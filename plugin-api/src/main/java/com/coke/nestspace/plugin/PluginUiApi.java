package com.coke.nestspace.plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PluginUiApi {

    CompletableFuture<List<PluginPageSpec>> listPagesAsync();

    CompletableFuture<List<PluginRendererSpec>> listRenderersAsync();

    CompletableFuture<String> readPageAsync(String pluginId, String pageId);
}
