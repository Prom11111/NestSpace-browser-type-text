package com.coke.nestspace.plugin;

/**
 * Opens WebSocket sessions owned by the current plugin.
 */
public interface PluginWebSocketApi {

    PluginWebSocket connect(
        PluginWebSocketRequest request,
        PluginWebSocketListener listener
    );

    default PluginWebSocket connect(
        String url,
        PluginWebSocketListener listener
    ) {
        return connect(new PluginWebSocketRequest(url), listener);
    }
}
