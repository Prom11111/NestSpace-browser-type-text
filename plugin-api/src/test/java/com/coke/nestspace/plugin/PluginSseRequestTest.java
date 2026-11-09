package com.coke.nestspace.plugin;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginSseRequestTest {

    @Test
    public void timeoutsAreClamped() {
        PluginSseRequest request = new PluginSseRequest(
            PluginHttpMethod.GET,
            "http://example.invalid/events",
            Collections.emptyMap(),
            "",
            1,
            1_000_000
        );
        assertEquals(1_000, request.getConnectTimeoutMs());
        assertEquals(
            PluginSseRequest.MAX_READ_TIMEOUT_MS,
            request.getReadTimeoutMs()
        );
    }

    @Test
    public void getCannotIncludeABody() {
        assertThrows(IllegalArgumentException.class, () ->
            new PluginSseRequest(
                PluginHttpMethod.GET,
                "https://example.invalid/events",
                Collections.emptyMap(),
                "body",
                5_000,
                5_000
            )
        );
    }

    @Test
    public void zeroReadTimeoutDisablesTheIdleTimeout() {
        PluginSseRequest request = new PluginSseRequest(
            PluginHttpMethod.GET,
            "http://example.invalid/events",
            Collections.emptyMap(),
            "",
            5_000,
            0
        );
        assertEquals(0, request.getReadTimeoutMs());
    }
}
