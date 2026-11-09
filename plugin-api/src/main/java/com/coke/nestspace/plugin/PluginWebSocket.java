package com.coke.nestspace.plugin;

/**
 * An asynchronous WebSocket session. Listener callbacks run on network
 * threads and must not block for long periods.
 */
public interface PluginWebSocket extends AutoCloseable {

    int MAX_MESSAGE_BYTES = 1024 * 1024;
    int MAX_CLOSE_REASON_BYTES = 123;

    String getUrl();

    boolean isOpen();

    boolean sendText(String text);

    boolean sendBinary(byte[] data);

    boolean close(int code, String reason);

    @Override
    default void close() {
        close(1000, "");
    }
}
