package com.coke.nestspace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetworkPolicyTest {

    @Test
    fun httpApiAllowsHttpAndHttps() {
        assertEquals(
            "http",
            PluginUrlPolicy.requireHttpUrl("http://example.com/path").scheme
        )
        assertTrue(
            PluginUrlPolicy.requireHttpUrl("HTTPS://example.com/path")
                .scheme.equals("https", ignoreCase = true)
        )
    }

    @Test
    fun websocketAllowsWsAndWss() {
        assertEquals(
            "ws",
            PluginUrlPolicy.requireWebSocketUrl("ws://example.com/socket").scheme
        )
        assertEquals(
            "wss",
            PluginUrlPolicy.requireWebSocketUrl("wss://example.com/socket").scheme
        )
    }

    @Test
    fun unrelatedSchemesAndUserInfoAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginUrlPolicy.requireHttpUrl("file:///tmp/data")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginUrlPolicy.requireWebSocketUrl("https://example.com/socket")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginUrlPolicy.requireHttpUrl("https://user@example.com/path")
        }
    }

    @Test
    fun wildcardMatchesOnlyRealSubdomains() {
        PluginNetworkPolicy.requireHostAllowed(
            "api.example.com",
            setOf("*.example.com")
        )
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetworkPolicy.requireHostAllowed(
                "badexample.com",
                setOf("*.example.com")
            )
        }
    }
}
