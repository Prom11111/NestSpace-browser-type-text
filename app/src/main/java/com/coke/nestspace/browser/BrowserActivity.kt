package com.coke.nestspace.browser

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coke.nestspace.assistant.FloatingDotOverlay
import com.coke.nestspace.ui.theme.VirtualTheme

/**
 * Chrome-style in-app browser. Tabs live in BrowserSession so the agent and
 * the visible UI share the same list; the activity only renders the selected
 * WebView and the tab strip.
 */
class BrowserActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearContainerSurface = Runnable {
        FloatingDotOverlay.setContainerSurfaceVisible(this, CONTAINER_SURFACE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Re-apply before the first WebView loads so a saved proxy is never
        // skipped during a warm start.
        BrowserProxyManager.apply(this)
        BrowserSession.attachActivity(this)
        val initialUrl = intent.getStringExtra(EXTRA_URL)
        val initialTabId = intent.getStringExtra(EXTRA_TAB_ID)
        setContent {
            VirtualTheme {
                BrowserScreen(
                    initialUrl = initialUrl,
                    initialTabId = initialTabId,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tabId = intent.getStringExtra(EXTRA_TAB_ID)
        if (tabId != null) {
            BrowserSession.selectTab(tabId)
        } else {
            intent.getStringExtra(EXTRA_URL)?.let { url ->
                BrowserSession.navigate(url, BrowserSession.selectedTabId())
            }
        }
    }

    override fun startActivity(intent: Intent) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivity(intent, options)
    }

    @Deprecated("Deprecated in Android")
    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivityForResult(intent, requestCode)
    }

    @Deprecated("Deprecated in Android")
    override fun startActivityForResult(
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ) {
        if (routeWebIntentInside(this, intent)) return
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(clearContainerSurface)
        FloatingDotOverlay.onHostActivityResumed(this)
        // The built-in browser is part of the container experience, so the
        // floating dot keeps the container color while it is on screen.
        FloatingDotOverlay.setContainerSurfaceVisible(this, CONTAINER_SURFACE, true)
        BrowserSession.resumeAll()
    }

    override fun onPause() {
        BrowserSession.pauseAll()
        // Delay the color reset so switching to another container surface
        // does not flash the host color first.
        mainHandler.removeCallbacks(clearContainerSurface)
        mainHandler.postDelayed(clearContainerSurface, 350L)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clearContainerSurface)
        FloatingDotOverlay.setContainerSurfaceVisible(this, CONTAINER_SURFACE, false)
        BrowserSession.detachActivity(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "browser_url"
        const val EXTRA_TAB_ID = "browser_tab_id"
        private const val CONTAINER_SURFACE = "browser"
    }
}
