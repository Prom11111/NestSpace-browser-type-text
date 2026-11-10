package com.coke.nestspace.browser

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.util.Log

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

/**
 * Last-resort guard for WebView/provider code that bypasses WebViewClient and
 * launches an external ACTION_VIEW intent from an Application or Activity
 * context. Route web URLs back into the built-in browser.
 */
internal fun routeWebIntentInside(context: Context, intent: Intent): Boolean {
    if (intent.component?.className == BrowserActivity::class.java.name) return false
    val url = webUrlFromViewIntent(intent) ?: return false
    Log.d("BrowserIntent", "redirected external web ACTION_VIEW into built-in browser")
    context.startActivity(
        Intent(context, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    return true
}
