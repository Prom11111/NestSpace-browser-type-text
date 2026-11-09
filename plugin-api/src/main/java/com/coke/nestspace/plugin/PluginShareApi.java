package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface PluginShareApi {

    CompletableFuture<Void> share(PluginShareRequest request);
}
