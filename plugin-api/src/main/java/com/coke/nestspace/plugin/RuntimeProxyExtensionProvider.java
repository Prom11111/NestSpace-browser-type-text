package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface RuntimeProxyExtensionProvider {

    PluginDescriptor getDescriptor();

    CompletableFuture<RuntimeProxyContribution> resolveAsync(
        RuntimeProxyContext context
    );
}
