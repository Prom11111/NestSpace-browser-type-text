package com.coke.nestspace.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class BrowserProxySettingsTest {

    @Test
    fun parseTypeAcceptsHttpAndSocksAliases() {
        assertEquals(BrowserProxySettings.TYPE_HTTP, BrowserProxySettings.parseType(" HTTP "))
        assertEquals(BrowserProxySettings.TYPE_SOCKS, BrowserProxySettings.parseType("socks"))
        assertEquals(BrowserProxySettings.TYPE_SOCKS, BrowserProxySettings.parseType("SOCKS5"))
        assertNull(BrowserProxySettings.parseType("ftp"))
    }

    @Test
    fun disabledConfigNeedsNoHostOrPort() {
        val config = BrowserProxySettings.Config(enabled = false)
        assertNull(BrowserProxySettings.validate(config))
    }

    @Test
    fun enabledConfigRequiresHostAndValidPort() {
        val missingHost = BrowserProxySettings.Config(enabled = true, port = 8080)
        assertEquals(
            BrowserProxySettings.ValidationError.HOST_REQUIRED,
            BrowserProxySettings.validate(missingHost)
        )

        val badHost = BrowserProxySettings.Config(
            enabled = true,
            host = "http://proxy.example.com:8080",
            port = 8080
        )
        assertEquals(
            BrowserProxySettings.ValidationError.INVALID_HOST,
            BrowserProxySettings.validate(badHost)
        )

        val badPort = BrowserProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 70000
        )
        assertEquals(
            BrowserProxySettings.ValidationError.INVALID_PORT,
            BrowserProxySettings.validate(badPort)
        )

        val valid = BrowserProxySettings.Config(
            enabled = true,
            type = BrowserProxySettings.TYPE_SOCKS,
            host = "10.0.2.2",
            port = 1080
        )
        assertNull(BrowserProxySettings.validate(valid))
    }

    @Test
    fun buildsProxyRulesAndSplitsBypass() {
        val config = BrowserProxySettings.Config(
            enabled = true,
            type = BrowserProxySettings.TYPE_SOCKS,
            host = " 10.0.2.2 ",
            port = 1080,
            bypass = "localhost, 127.0.0.1\n*.example.com;10.0.2.*"
        )
        assertEquals("socks://10.0.2.2:1080", BrowserProxySettings.proxyRule(config))
        assertEquals(
            listOf("localhost", "127.0.0.1", "*.example.com", "10.0.2.*"),
            BrowserProxySettings.bypassRules(config)
        )
    }

    @Test
    fun bypassMatchesHostsSubdomainsAndWildcards() {
        assertEquals(true, BrowserProxySettings.isBypassed("example.com", listOf("example.com")))
        assertEquals(
            true,
            BrowserProxySettings.isBypassed("www.example.com", listOf("example.com"))
        )
        assertEquals(
            true,
            BrowserProxySettings.isBypassed("cdn.example.com", listOf("*.example.com"))
        )
        assertEquals(
            false,
            BrowserProxySettings.isBypassed("example.org", listOf("example.com"))
        )
        assertEquals(true, BrowserProxySettings.isBypassed("localhost", listOf("<local>")))
        assertEquals(true, BrowserProxySettings.isBypassed("printer", listOf("<local>")))
        assertEquals(true, BrowserProxySettings.isBypassed("10.0.2.5", listOf("10.0.2.*")))
    }

    @Test
    fun downloadProxyUsesHttpOrSocksAndHonorsBypass() {
        val base = BrowserProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 8080
        )
        val http = BrowserProxySettings.connectionProxy(base, "https://example.org/file.apk")
        assertEquals(Proxy.Type.HTTP, http?.type())
        assertEquals(
            InetSocketAddress("proxy.example.com", 8080),
            http?.address()
        )

        val socks = BrowserProxySettings.connectionProxy(
            base.copy(type = BrowserProxySettings.TYPE_SOCKS),
            "https://example.org/file.apk"
        )
        assertEquals(Proxy.Type.SOCKS, socks?.type())

        assertNull(
            BrowserProxySettings.connectionProxy(
                base.copy(bypass = "example.org"),
                "https://example.org/file.apk"
            )
        )
        assertNull(
            BrowserProxySettings.connectionProxy(
                base.copy(enabled = false),
                "https://example.org/file.apk"
            )
        )
    }

    @Test
    fun signatureChangesWithConfig() {
        val disabled = BrowserProxySettings.Config(enabled = false)
        assertEquals("off", BrowserProxySettings.signature(disabled))

        val first = BrowserProxySettings.Config(
            enabled = true,
            host = "proxy.example.com",
            port = 8080,
            bypass = "localhost"
        )
        val second = first.copy(port = 8081)
        assertEquals(
            "on|http|proxy.example.com|8080|localhost",
            BrowserProxySettings.signature(first)
        )
        assertEquals(
            "on|http|proxy.example.com|8081|localhost",
            BrowserProxySettings.signature(second)
        )
    }
}
