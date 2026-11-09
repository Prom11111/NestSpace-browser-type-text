package com.coke.nestspace.plugin;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface PluginEventApi {

    PluginSubscription subscribe(
        Set<String> eventTypes,
        PluginEventListener listener
    );

    CompletableFuture<Void> publish(PluginEvent event);
}
