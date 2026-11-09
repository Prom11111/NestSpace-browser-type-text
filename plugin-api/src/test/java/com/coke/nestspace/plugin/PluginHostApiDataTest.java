package com.coke.nestspace.plugin;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PluginHostApiDataTest {

    @Test
    public void notificationValuesAreLengthLimited() {
        PluginNotification notification = new PluginNotification(
            repeat("i", 120),
            repeat("t", 150),
            repeat("b", 1_100)
        );
        assertEquals(100, notification.getId().length());
        assertEquals(120, notification.getTitle().length());
        assertEquals(1_000, notification.getBody().length());
    }

    @Test
    public void httpTimeoutIsClamped() {
        PluginHttpRequest request = new PluginHttpRequest(
            PluginHttpMethod.GET,
            "https://example.invalid",
            Collections.emptyMap(),
            "",
            100_000
        );
        assertEquals(
            PluginHttpRequest.MAX_TIMEOUT_MS,
            request.getTimeoutMs()
        );
    }

    @Test
    public void oversizedRequestBodyIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            new PluginHttpRequest(
                PluginHttpMethod.POST,
                "https://example.invalid",
                Collections.emptyMap(),
                repeat("x", PluginHttpRequest.MAX_BODY_BYTES + 1),
                5_000
            )
        );
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
