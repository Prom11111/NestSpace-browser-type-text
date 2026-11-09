package com.coke.nestspace.plugin;

public interface PluginTaskContext {

    String getTaskId();

    boolean isCancelled();

    void checkCancelled();

    void reportProgress(int percent, String message);
}
