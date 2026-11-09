package com.coke.nestspace.plugin;

/**
 * Receives streaming Server-Sent Events on a background network thread.
 */
public interface PluginSseListener {

    default void onOpen(
        PluginSseConnection connection,
        int statusCode,
        String contentType
    ) {
    }

    default void onEvent(
        PluginSseConnection connection,
        PluginSseEvent event
    ) {
    }

    default void onClosed(PluginSseConnection connection) {
    }

    default void onFailure(
        PluginSseConnection connection,
        Throwable error
    ) {
    }
}
