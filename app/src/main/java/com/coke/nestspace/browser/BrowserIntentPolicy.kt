package com.coke.nestspace.browser

import android.content.Intent
import android.net.Uri

/**
 * Resolve an app navigation intent to a URL that can be rendered by the
 * in-app browser. Android intent:// links commonly carry a web fallback.
 */
internal fun webUrlFromViewIntent(intent: Intent): String? {
    if (intent.action != Intent.ACTION_VIEW) return null
    return webUrlFromUri(intent.data)
}

internal fun webUrlFromUri(uri: Uri?): String? {
    val value = uri ?: return null
    val scheme = value.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") return value.toString()
    if (scheme != "intent") return null
    val fallback = runCatching {
        value.getQueryParameter("S.browser_fallback_url")
            ?: value.getQueryParameter("browser_fallback_url")
    }.getOrNull() ?: return null
    val fallbackUri = runCatching { Uri.parse(fallback) }.getOrNull() ?: return null
    return fallbackUri.toString().takeIf {
        fallbackUri.scheme.equals("http", true) ||
            fallbackUri.scheme.equals("https", true)
    }
}
