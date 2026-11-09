package com.coke.nestspace.plugin;

import java.util.concurrent.CompletableFuture;

public interface PluginTaskHandle {

    String getId();

    boolean isDone();

    boolean isCancelled();

    int getProgressPercent();

    String getProgressMessage();

    boolean cancel();

    CompletableFuture<PluginTaskResult> getResult();
}
