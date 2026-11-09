package com.coke.nestspace.plugin;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginWebSocketRequestTest {

    @Test
    public void timeoutIsClampedToSupportedRange() {
        PluginWebSocketRequest request = new PluginWebSocketRequest(
            "ws://example.invalid/socket",
            Collections.emptyMap(),
            100_000
        );
        assertEquals(
            PluginWebSocketRequest.MAX_TIMEOUT_MS,
            request.getTimeoutMs()
        );
    }

    @Test
    public void headerCountAndSizeAreLimited() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 0;
             index <= PluginWebSocketRequest.MAX_HEADERS;
             index++) {
            headers.put("X-Test-" + index, "value");
        }
        assertThrows(IllegalArgumentException.class, () ->
            new PluginWebSocketRequest(
                "wss://example.invalid/socket",
                headers,
                PluginWebSocketRequest.DEFAULT_TIMEOUT_MS
            )
        );
    }

    @Test
    public void emptyUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new PluginWebSocketRequest(" ")
        );
    }
}
