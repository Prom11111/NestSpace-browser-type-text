package com.coke.nestspace.plugin;

public interface PluginSubscription extends AutoCloseable {

    @Override
    void close();
}
