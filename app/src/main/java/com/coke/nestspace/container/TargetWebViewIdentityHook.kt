package com.coke.nestspace.container

import android.content.Context
import android.util.Log
import android.webkit.WebSettings

/**
 * Seeds the process HTTP agent before target SDKs snapshot it.
 *
 * A normal Android app process starts with a Dalvik HTTP agent. WebView replaces
 * that with the installed Chromium user agent once its provider initializes.
 * Virtual processes skip the normal WebView startup, so device registration can
 * otherwise persist the Dalvik fallback as a permanent device fact.
 */
internal object TargetWebViewIdentityHook {
    private const val TAG = "TargetWebViewIdentityHook"

    fun install(context: Context): Boolean =
        runCatching {
            val userAgent = WebSettings.getDefaultUserAgent(context)
            if (userAgent.isBlank() || userAgent.startsWith("Dalvik/")) {
                return@runCatching false
            }
            System.setProperty("http.agent", userAgent)
            System.getProperty("http.agent") == userAgent
        }.onSuccess { installed ->
            Log.d(TAG, "web environment seeded=$installed")
        }.onFailure { error ->
            Log.w(TAG, "web environment seed failed", error)
        }.getOrDefault(false)
}
