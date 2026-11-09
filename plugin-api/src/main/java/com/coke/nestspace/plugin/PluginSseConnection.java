package com.coke.nestspace.plugin;

/**
 * A live Server-Sent Events connection.
 */
public interface PluginSseConnection extends AutoCloseable {

    String getUrl();

    boolean isOpen();

    @Override
    void close();
}
