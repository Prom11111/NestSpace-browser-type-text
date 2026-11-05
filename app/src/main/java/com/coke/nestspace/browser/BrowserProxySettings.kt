package com.coke.nestspace.browser

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Persistent proxy settings for the built-in browser.
 *
 * WebView proxies are app-wide (all tabs), so this is intentionally a single
 * configuration rather than a per-tab option.
 */
object BrowserProxySettings {

    const val TYPE_HTTP = "http"
    const val TYPE_SOCKS = "socks"
    const val NO_PORT = 0

    data class Config(
        val enabled: Boolean = false,
        val type: String = TYPE_HTTP,
        val host: String = "",
        val port: Int = NO_PORT,
        val bypass: String = ""
    )

    private const val PREFS = "browser_proxy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TYPE = "type"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_BYPASS = "bypass"

    fun load(context: Context): Config {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            type = parseType(prefs.getString(KEY_TYPE, TYPE_HTTP)) ?: TYPE_HTTP,
            host = prefs.getString(KEY_HOST, "").orEmpty(),
            port = prefs.getInt(KEY_PORT, NO_PORT).coerceIn(NO_PORT, 65535),
            bypass = prefs.getString(KEY_BYPASS, "").orEmpty()
        )
    }

    fun save(context: Context, config: Config) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_TYPE, parseType(config.type) ?: TYPE_HTTP)
            .putString(KEY_HOST, config.host.trim())
            .putInt(KEY_PORT, config.port.coerceIn(NO_PORT, 65535))
            .putString(KEY_BYPASS, config.bypass.trim())
            .apply()
    }

    fun parseType(value: String?): String? = when (value?.trim()?.lowercase()) {
        TYPE_HTTP -> TYPE_HTTP
        TYPE_SOCKS, "socks5", "socks4" -> TYPE_SOCKS
        else -> null
    }

    enum class ValidationError { INVALID_TYPE, HOST_REQUIRED, INVALID_HOST, INVALID_PORT }

    /** Returns a validation error, or null when the config is usable. */
    fun validate(config: Config): ValidationError? {
        if (!config.enabled) return null
        if (parseType(config.type) == null) return ValidationError.INVALID_TYPE
        val host = config.host.trim()
        if (host.isBlank()) return ValidationError.HOST_REQUIRED
        if (host.any { it.isWhitespace() } || host.contains('/') || host.contains(':')) {
            return ValidationError.INVALID_HOST
        }
        if (config.port !in 1..65535) return ValidationError.INVALID_PORT
        return null
    }

    fun proxyRule(config: Config): String {
        val type = parseType(config.type) ?: TYPE_HTTP
        return "$type://${config.host.trim()}:${config.port}"
    }

    fun bypassRules(config: Config): List<String> =
        config.bypass
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * A java.net proxy for non-WebView browser traffic such as downloads.
     * Returns null for a direct connection (disabled, invalid or bypassed).
     */
    fun connectionProxy(config: Config, url: String): Proxy? {
        if (!config.enabled || validate(config) != null) return null
        val host = runCatching { URI(url).host }.getOrNull()
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (host.isBlank()) return null
        if (isBypassed(host, bypassRules(config))) return null
        val type = if (parseType(config.type) == TYPE_SOCKS) {
            Proxy.Type.SOCKS
        } else {
            Proxy.Type.HTTP
        }
        return Proxy(type, InetSocketAddress(config.host.trim(), config.port))
    }

    fun isBypassed(host: String, rules: List<String>): Boolean {
        val target = host.trim().lowercase().removeSuffix(".")
        if (target.isBlank()) return false
        return rules.any { raw ->
            val rule = raw.trim().lowercase().removeSuffix(".")
            when {
                rule.isEmpty() -> false
                rule == "<local>" ->
                    !target.contains('.') || target == "localhost" || target == "127.0.0.1"
                rule.contains('*') -> wildcardMatch(rule, target)
                else -> target == rule || target.endsWith(".$rule")
            }
        }
    }

    private fun wildcardMatch(pattern: String, value: String): Boolean {
        val regex = buildString {
            append('^')
            pattern.forEach { char ->
                when (char) {
                    '*' -> append(".*")
                    '.' -> append("\\.")
                    else -> append(Regex.escape(char.toString()))
                }
            }
            append('$')
        }
        return runCatching { Regex(regex).matches(value) }.getOrDefault(false)
    }

    fun signature(config: Config): String = if (!config.enabled) {
        "off"
    } else {
        listOf(
            "on",
            parseType(config.type) ?: TYPE_HTTP,
            config.host.trim().lowercase(),
            config.port.toString(),
            bypassRules(config).joinToString(",")
        ).joinToString("|")
    }
}
