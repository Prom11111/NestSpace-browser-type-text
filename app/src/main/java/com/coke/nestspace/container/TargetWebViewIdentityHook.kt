package com.coke.nestspace.container

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import com.coke.nestspace.container.identity.HostIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Seeds the process HTTP agent before target SDKs snapshot it.
 *
 * The host lazily prepares the stable user-agent on the first container launch.
 * Slot processes only read and validate that cache, avoiding a complete
 * Chromium provider startup on every launch.
 */
internal object TargetWebViewIdentityHook {
    private const val TAG = "TargetWebViewIdentityHook"
    private const val PREFS = "container_runtime_cache"
    private const val KEY_USER_AGENT = "webview_user_agent"
    private const val KEY_PROVIDER_PACKAGE = "webview_provider_package"
    private const val KEY_PROVIDER_UPDATED_AT = "webview_provider_updated_at"
    private const val INVALID_UPDATED_AT = -1L
    private const val PREFETCH_WAIT_MS = 250L

    private val prefetchLock = Any()
    private val prefetchDone = CountDownLatch(1)
    @Volatile private var prefetchStarted = false

    /** Starts once, when the first virtual app is about to launch. */
    fun prefetch(context: Context) {
        synchronized(prefetchLock) {
            if (prefetchStarted) return
            prefetchStarted = true
        }
        val app = hostContext(context)
        val thread = HandlerThread("nestspace-webview-ua").apply { start() }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            Handler(thread.looper).post {
                try {
                    val cached = readValidCache(app)
                    if (cached != null) {
                        Log.d(
                            TAG,
                            "prefetched web environment already valid " +
                                "duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    } else {
                        val computed = computeAndStore(app)
                        Log.d(
                            TAG,
                            "prefetched web environment available=${computed != null} " +
                                "duration_ms=${SystemClock.elapsedRealtime() - startedAt}"
                        )
                    }
                } finally {
                    prefetchDone.countDown()
                    thread.quitSafely()
                }
            }
        } catch (error: Throwable) {
            prefetchDone.countDown()
            thread.quitSafely()
            throw error
        }
    }

    /** Bounded wait used to overlap preparation with the rest of launch setup. */
    fun awaitPrefetch(timeoutMs: Long = PREFETCH_WAIT_MS): Boolean =
        prefetchStarted && prefetchDone.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun install(context: Context): Boolean = runCatching {
        val app = hostContext(context)
        val userAgent = readValidCache(app) ?: computeAndStore(app)
            ?: return@runCatching false
        System.setProperty("http.agent", userAgent)
        System.getProperty("http.agent") == userAgent
    }.onSuccess { installed ->
        Log.d(TAG, "web environment seeded=$installed")
    }.onFailure { error ->
        Log.w(TAG, "web environment seed failed", error)
    }.getOrDefault(false)

    private fun readValidCache(context: Context): String? {
        val prefs = prefs(context)
        val userAgent = prefs.getString(KEY_USER_AGENT, null)
            ?.takeIf(::isUsable)
            ?: return null
        val providerPackage = prefs.getString(KEY_PROVIDER_PACKAGE, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val updatedAt = prefs.getLong(KEY_PROVIDER_UPDATED_AT, INVALID_UPDATED_AT)
        if (updatedAt < 0L) return null
        val currentUpdatedAt = providerUpdatedAt(context, providerPackage) ?: return null
        return userAgent.takeIf { currentUpdatedAt == updatedAt }
    }

    private fun computeAndStore(context: Context): String? {
        val userAgent = WebSettings.getDefaultUserAgent(context)
        if (!isUsable(userAgent)) return null
        val provider = currentProvider(context) ?: return userAgent
        val updatedAt = providerUpdatedAt(context, provider.first) ?: return userAgent
        prefs(context).edit()
            .putString(KEY_USER_AGENT, userAgent)
            .putString(KEY_PROVIDER_PACKAGE, provider.first)
            .putLong(KEY_PROVIDER_UPDATED_AT, updatedAt)
            .commit()
        return userAgent
    }

    private fun currentProvider(context: Context): Pair<String, Long>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val info = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
            ?: return null
        return info.packageName to info.lastUpdateTime
    }

    private fun providerUpdatedAt(context: Context, packageName: String): Long? =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrNull()

    private fun isUsable(userAgent: String?): Boolean =
        !userAgent.isNullOrBlank() && !userAgent.startsWith("Dalvik/")

    private fun hostContext(context: Context): Context =
        HostIdentity.application?.applicationContext ?: context.applicationContext

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
