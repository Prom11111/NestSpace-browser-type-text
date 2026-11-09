package com.coke.nestspace.plugin;

/**
 * Receives WebSocket lifecycle and message events. A failed connection or
 * malformed or oversized server message is reported through
 * {@link #onFailure(PluginWebSocket, Throwable)}.
 */
public interface PluginWebSocketListener {

    default void onOpen(PluginWebSocket socket) {
    }

    default void onText(PluginWebSocket socket, String text) {
    }

    default void onBinary(PluginWebSocket socket, byte[] data) {
    }

    default void onClosing(
        PluginWebSocket socket,
        int code,
        String reason
    ) {
    }

    default void onClosed(
        PluginWebSocket socket,
        int code,
        String reason
    ) {
    }

    default void onFailure(PluginWebSocket socket, Throwable error) {
    }
}
