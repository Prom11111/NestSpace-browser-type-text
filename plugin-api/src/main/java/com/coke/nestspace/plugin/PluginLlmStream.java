package com.coke.nestspace.plugin;

public interface PluginLlmStream extends AutoCloseable {

    boolean isClosed();

    @Override
    void close();
}
