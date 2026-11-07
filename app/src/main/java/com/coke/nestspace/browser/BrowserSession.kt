package com.coke.nestspace.browser

import com.coke.nestspace.i18n.AppLanguage
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

/**
 * Multi-tab in-process browser used by both the visible BrowserActivity and
 * the agent tools.
 *
 * Tabs are independent WebViews. Agent calls default to headless tabs so they
 * can read/click several pages without disturbing the user's selected tab; the
 * visible browser exposes the same tab list as a Chrome-style strip. Selecting
 * a headless tab attaches its WebView to the activity instead of throwing its
 * state away. Only the selected tab is attached to the window at a time.
 */
object BrowserSession {

    const val HOME_URL = "https://www.bing.com"
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    const val MODE_VISIBLE = "visible"
    const val MODE_HEADLESS = "headless"
    const val MODE_CLOSED = "closed"
    const val MAX_TABS = 10
    private const val MAX_DOWNLOADS = 30

    private const val SEARCH_URL = "https://www.bing.com/search?q="

    /** Thrown by [open] for invalid tab operations; tools map [code]. */
    class BrowserException(val code: String, message: String) : IllegalStateException(message)

    data class Tab(
        val id: String,
        val url: String,
        val title: String,
        val loading: Boolean,
        val loaded: Boolean,
        val selected: Boolean,
        val active: Boolean,
        val canGoBack: Boolean,
        val canGoForward: Boolean
    )

    private class Entry(val id: String) {
        @Volatile var url: String = HOME_URL
        @Volatile var title: String = ""
        @Volatile var loading: Boolean = false
        @Volatile var loaded: Boolean = false
        @Volatile var canGoBack: Boolean = false
        @Volatile var canGoForward: Boolean = false
        var webView: WebView? = null
        /** True when the WebView was created with the Activity context. */
        var uiOwned: Boolean = false
        /** True while attached to the visible activity's container. */
        var visible: Boolean = false
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var nextTabNumber = 1

    @Volatile
    private var activeTabId: String? = null

    @Volatile
    private var selectedTabId: String? = null

    @Volatile
    private var activityRef: WeakReference<BrowserActivity>? = null
    private val headlessScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabsFlow: StateFlow<List<Tab>> = _tabs.asStateFlow()

    data class DownloadState(
        val id: String,
        val url: String,
        val name: String,
        val state: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val startedAt: Long = System.currentTimeMillis(),
        val path: String? = null,
        val uri: String? = null,
        val size: Long = 0L,
        val error: String? = null
    ) {
        val progress: Float?
            get() = totalBytes.takeIf { it > 0L }?.let {
                (downloadedBytes.toFloat() / it).coerceIn(0f, 1f)
            }
    }

    @Volatile
    private var downloadState: DownloadState? = null
    private val downloadLock = Any()
    private val _downloads = MutableStateFlow<List<DownloadState>>(emptyList())
    val downloadsFlow: StateFlow<List<DownloadState>> = _downloads.asStateFlow()

    private data class PendingFileChooser(
        val callback: ValueCallback<Array<Uri>>,
        val acceptTypes: Array<String>,
        val mode: Int
    )

    private val fileChooserLock = Any()
    private var pendingFileChooser: PendingFileChooser? = null

    // ---- lifecycle / UI attachment -------------------------------------

    fun attachActivity(activity: BrowserActivity) {
        activityRef = WeakReference(activity)
        if (selectedTabId == null || !entries.containsKey(selectedTabId)) {
            selectedTabId = activeTabId?.takeIf { entries.containsKey(it) }
                ?: entries.keys.firstOrNull()
        }
        activeTabId = activeTabId?.takeIf { entries.containsKey(it) } ?: selectedTabId
        publish()
    }

    fun detachActivity(activity: BrowserActivity) {
        if (activityRef?.get() !== activity) return
        activityRef = null
        val appContext = activity.applicationContext
        entries.values.forEach { entry ->
            entry.visible = false
            val view = entry.webView
            if (entry.uiOwned) {
                // Activity-context WebViews must not outlive their activity.
                // Recreate the tab as a headless WebView so closing the visible
                // browser does not leave a listed tab that tools cannot use.
                entry.webView = null
                entry.uiOwned = false
                entry.loaded = false
                entry.loading = true
                view?.let { destroyView(it) }
                val headless = createWebView(appContext, entry, forUi = false)
                entry.webView = headless
                headless.loadUrl(entry.url.ifBlank { HOME_URL })
            } else {
                // Headless tabs survive the visible browser; detach the view
                // from the dying container and resume it so agent
                // reading/clicking keeps working after the UI closes.
                view?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    runCatching { it.onResume() }
                }
            }
        }
        publish()
    }

    fun resumeAll() {
        entries.values.forEach { entry ->
            entry.webView?.let { runCatching { it.onResume() } }
        }
    }

    fun pauseAll() {
        entries.values.forEach { entry ->
            entry.webView?.let { runCatching { it.onPause() } }
        }
    }

    // ---- tab management -------------------------------------------------

    fun snapshot(): List<Tab> = _tabs.value

    fun activeTabId(): String? = activeTabId

    fun selectedTabId(): String? = selectedTabId

    fun activeTab(): Tab? = _tabs.value.firstOrNull { it.active }

    fun tabCount(): Int = _tabs.value.size

    fun isOpen(): Boolean = _tabs.value.isNotEmpty()

    fun currentMode(): String = when {
        activityRef?.get() != null -> MODE_VISIBLE
        entries.isNotEmpty() -> MODE_HEADLESS
        else -> MODE_CLOSED
    }

    fun modeFor(tabId: String?): String {
        val id = tabId ?: activeTabId ?: return MODE_CLOSED
        return if (activityRef?.get() != null && selectedTabId == id) {
            MODE_VISIBLE
        } else {
            MODE_HEADLESS
        }
    }

    /**
     * Opens a URL in a tab and returns its id.
     *
     * @param headless true creates a background WebView (agent default);
     * false creates/uses the visible browser.
     * @param newTab create a new tab instead of reusing the active one.
     * @param tabId navigate this existing tab instead.
     * @param showActivity used by BrowserActivity itself to avoid re-launching.
     */
    fun open(
        context: Context,
        url: String?,
        headless: Boolean = false,
        newTab: Boolean = true,
        tabId: String? = null,
        showActivity: Boolean = true
    ): String {
        val target = normalizeInput(url)
        val entry: Entry

        if (tabId != null) {
            entry = entries[tabId]
                ?: throw BrowserException(
                    "BROWSER_TAB_NOT_FOUND",
                    "tab not found: $tabId"
                )
        } else if (!newTab) {
            entry = activeTabId?.let { entries[it] }
                ?: createEntry(target)
            entry.url = target
        } else {
            entry = createEntry(target)
        }

        if (headless) {
            entry.url = target
            entry.loaded = false
            entry.loading = true
            entry.webView?.let { it.loadUrl(target) }
                ?: run {
                    val view = createWebView(context.applicationContext, entry, forUi = false)
                    entry.webView = view
                    entry.uiOwned = false
                    view.loadUrl(target)
                }
            activeTabId = entry.id
            // Do not steal the user's selected visible tab for headless work.
            if (selectedTabId == null || !entries.containsKey(selectedTabId)) {
                selectedTabId = entry.id
            }
            publish()
            return entry.id
        }

        entry.url = target
        entry.loaded = false
        entry.loading = true
        activeTabId = entry.id
        selectedTabId = entry.id
        publish()
        entry.webView?.loadUrl(target)

        if (showActivity) {
            val intent = Intent(context, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, target)
                .putExtra(BrowserActivity.EXTRA_TAB_ID, entry.id)
            val activity = activityRef?.get()
            if (activity != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                runCatching { activity.startActivity(intent) }
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
        return entry.id
    }

    /**
     * Open a content:// document produced by the download manager. This path
     * bypasses URL normalization and never delegates HTML/SVG files to the
     * system browser.
     */
    fun openLocalContent(context: Context, uri: Uri): String {
        val target = uri.toString()
        val entry = createEntry(target)
        entry.url = target
        entry.loading = true
        val view = createWebView(context.applicationContext, entry, forUi = false)
        entry.webView = view
        entry.uiOwned = false
        activeTabId = entry.id
        selectedTabId = entry.id
        publish()
        view.loadUrl(target)

        val intent = Intent(context, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_TAB_ID, entry.id)
        val activity = activityRef?.get()
        if (activity != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { activity.startActivity(intent) }
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
        return entry.id
    }

    fun selectTab(tabId: String): Boolean {
        val entry = entries[tabId] ?: return false
        activeTabId = tabId
        selectedTabId = tabId
        publish()
        return true
    }

    /**
     * Select a tab and bring the visible browser to the foreground without
     * reloading it. BrowserActivity adopts the tab's existing WebView, so a
     * page the agent loaded headlessly keeps its state and can be handed to
     * the user for a captcha or other anti-bot check.
     */
    fun showTab(context: Context, tabId: String? = null): Boolean {
        val id = tabId ?: selectedTabId ?: activeTabId ?: return false
        if (!entries.containsKey(id)) return false
        activeTabId = id
        selectedTabId = id
        publish()
        val intent = Intent(context, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_TAB_ID, id)
        val activity = activityRef?.get()
        if (activity != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            return runCatching { activity.startActivity(intent); true }.getOrDefault(false)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun closeTab(tabId: String? = null): Boolean {
        val id = tabId ?: selectedTabId ?: activeTabId ?: return false
        val entry = entries.remove(id) ?: return false
        entry.webView?.let { destroyView(it) }
        entry.webView = null

        if (selectedTabId == id) {
            selectedTabId = nearestTabId(id) ?: entries.keys.firstOrNull()
        }
        if (activeTabId == id) {
            activeTabId = selectedTabId ?: entries.keys.firstOrNull()
        }
        if (entries.isEmpty()) {
            activeTabId = null
            selectedTabId = null
        }
        publish()
        return true
    }

    private fun nearestTabId(id: String): String? {
        // ids are tab-N; pick the highest remaining id as a stable neighbour.
        return entries.keys
            .filter { it != id }
            .maxByOrNull { it.removePrefix("tab-").toIntOrNull() ?: 0 }
    }

    private fun createEntry(url: String): Entry {
        if (entries.size >= MAX_TABS) {
            throw BrowserException(
                "BROWSER_TAB_LIMIT",
                "at most $MAX_TABS tabs; close one with browser_close_tab"
            )
        }
        val entry = Entry("tab-${nextTabNumber++}")
        entry.url = url
        entries[entry.id] = entry
        return entry
    }

    private fun publish() {
        val active = activeTabId
        val selected = selectedTabId
        _tabs.value = entries.values.map { entry ->
            Tab(
                id = entry.id,
                url = entry.url,
                title = entry.title,
                loading = entry.loading,
                loaded = entry.loaded,
                selected = entry.id == selected,
                active = entry.id == active,
                canGoBack = entry.canGoBack,
                canGoForward = entry.canGoForward
            )
        }
    }

    // ---- webview access / navigation ------------------------------------

    fun webView(tabId: String? = null): WebView? {
        val id = tabId ?: activeTabId ?: return null
        return entries[id]?.webView
    }

    /**
     * Returns the tab's WebView for attachment to the visible container,
     * creating it with the Activity context if needed. The selected headless
     * WebView is adopted instead of being recreated, so agent state is kept.
     */
    fun ensureVisibleWebView(context: Context, tabId: String): WebView? {
        val entry = entries[tabId] ?: return null
        var view = entry.webView
        if (view == null) {
            view = createWebView(context, entry, forUi = true)
            entry.webView = view
            entry.uiOwned = true
            view.loadUrl(entry.url)
        }
        entries.values.forEach { it.visible = it.id == tabId }
        entry.visible = true
        publish()
        return view
    }

    fun navigate(url: String, tabId: String? = null): Boolean {
        val entry = entryFor(tabId) ?: return false
        val view = entry.webView ?: return false
        entry.url = url
        entry.loaded = false
        entry.loading = true
        publish()
        view.loadUrl(url)
        return true
    }

    fun back(tabId: String? = null): Boolean {
        val entry = entryFor(tabId) ?: return false
        val view = entry.webView ?: return false
        if (!view.canGoBack()) return false
        entry.loaded = false
        entry.loading = true
        publish()
        view.goBack()
        return true
    }

    fun forward(tabId: String? = null): Boolean {
        val entry = entryFor(tabId) ?: return false
        val view = entry.webView ?: return false
        if (!view.canGoForward()) return false
        entry.loaded = false
        entry.loading = true
        publish()
        view.goForward()
        return true
    }

    fun reload(tabId: String? = null): Boolean {
        val entry = entryFor(tabId) ?: return false
        val view = entry.webView ?: return false
        entry.loaded = false
        entry.loading = true
        publish()
        view.reload()
        return true
    }

    fun isPageLoaded(tabId: String? = null): Boolean {
        val id = tabId ?: activeTabId ?: return false
        return _tabs.value.firstOrNull { it.id == id }?.loaded == true
    }

    suspend fun awaitPageLoad(tabId: String? = null, timeoutMs: Long = 15_000): Boolean {
        val id = tabId ?: activeTabId ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_tabs.value.firstOrNull { it.id == id }?.loaded == true) return true
            delay(100)
        }
        return _tabs.value.firstOrNull { it.id == id }?.loaded == true
    }

    private fun entryFor(tabId: String?): Entry? =
        tabId?.let { entries[it] } ?: activeTabId?.let { entries[it] }

    // ---- WebView construction and callbacks -----------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, entry: Entry, forUi: Boolean): WebView {
        val app = context.applicationContext
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            // File uploads hand WebView content:// URIs from the system picker.
            allowContentAccess = true
            // Download and handoff pages often use target=_blank/window.open.
            // Enable popup callbacks so WebChromeClient can capture the URL
            // and keep it inside NestSpace instead of letting WebView hand it
            // to the system browser.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(forUi)
            builtInZoomControls = forUi
            displayZoomControls = false
            userAgentString = MOBILE_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                onTabPageStarted(entry, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onTabPageFinished(entry, url, view?.title)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // Subresource failures (ads, iframes, images) do not mean the
                // page itself stopped loading.
                if (request == null || request.isForMainFrame) {
                    entry.loading = false
                    updateNavigation(entry)
                    publish()
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // The WebView renderer is gone; keeping the tab would leave a
                // zombie entry and letting the default handler run can kill
                // the app. Close this tab and report that we handled it.
                runCatching { closeTab(entry.id) }
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return keepNavigationInside(view, uri)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = url?.let(Uri::parse) ?: return true
                return keepNavigationInside(view, uri)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val source = view ?: return true
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return true
                val popup = WebView(app)
                var routed = false
                fun routePopup(url: String?): Boolean {
                    if (routed) return true
                    val uri = url?.let(Uri::parse) ?: return true
                    if (uri.scheme.equals("http", true) ||
                        uri.scheme.equals("https", true)
                    ) {
                        routed = true
                        source.post {
                            runCatching {
                                BrowserSession.open(
                                    context = app,
                                    url = uri.toString(),
                                    headless = !entry.visible,
                                    newTab = true,
                                    showActivity = false
                                )
                            }
                            popup.destroy()
                        }
                    }
                    return true
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = routePopup(request?.url?.toString())

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        url: String?
                    ): Boolean = routePopup(url)

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean
                    ) {
                        routePopup(url)
                    }
                }
                transport.webView = popup
                runCatching { resultMsg.sendToTarget() }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                return requestFileChooser(
                    app,
                    filePathCallback,
                    fileChooserParams?.acceptTypes ?: emptyArray(),
                    fileChooserParams?.mode ?: FileChooserParams.MODE_OPEN
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    entry.title = title
                    publish()
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                entry.loading = newProgress < 100
                if (newProgress >= 100) entry.loaded = true
                updateNavigation(entry)
                publish()
            }
        }
        view.setDownloadListener { url, _, _, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                headlessScope.launch {
                    runCatching { download(app, url) }
                        .onSuccess { result ->
                            if (forUi) {
                                android.widget.Toast.makeText(
                                    app,
                                    AppLanguage.tr(
                                        "已下载 ${result.name}",
                                        "Downloaded ${result.name}"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .onFailure { error ->
                            if (forUi) {
                                android.widget.Toast.makeText(
                                    app,
                                    AppLanguage.tr(
                                        "下载失败：${error.message ?: "未知错误"}",
                                        "Download failed: ${error.message ?: "unknown error"}"
                                    ),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                }
            }
        }
        if (!forUi) {
            // Give the renderer a viewport even though no window is attached.
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    1080,
                    android.view.View.MeasureSpec.EXACTLY
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    1920,
                    android.view.View.MeasureSpec.EXACTLY
                )
            )
            view.layout(0, 0, 1080, 1920)
        }
        return view
    }

    /**
     * HTTP(S) remains in this tab. Non-web schemes such as intent:, market:,
     * tel: and app-specific links are consumed instead of being promoted to
     * Android; otherwise a download button can replace NestSpace with Chrome.
     */
    private fun keepNavigationInside(view: WebView?, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return false
        if (scheme == "intent") {
            webUrlFromUri(uri)?.let { fallback ->
                view?.loadUrl(fallback)
            }
        }
        return true
    }

    private fun onTabPageStarted(entry: Entry, url: String?) {
        entry.loading = true
        entry.loaded = false
        if (!url.isNullOrBlank()) entry.url = url
        updateNavigation(entry)
        publish()
    }

    private fun onTabPageFinished(entry: Entry, url: String?, title: String?) {
        entry.loading = false
        entry.loaded = true
        if (!url.isNullOrBlank()) entry.url = url
        if (!title.isNullOrBlank()) entry.title = title
        updateNavigation(entry)
        publish()
    }

    private fun updateNavigation(entry: Entry) {
        entry.canGoBack = runCatching { entry.webView?.canGoBack() == true }.getOrDefault(false)
        entry.canGoForward = runCatching { entry.webView?.canGoForward() == true }
            .getOrDefault(false)
    }

    private fun destroyView(view: WebView) {
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
    }

    // ---- file uploads ---------------------------------------------------

    /**
     * The page asked for a file. A transparent proxy Activity launches the
     * system document picker so this also works for headless tabs.
     */
    private fun requestFileChooser(
        context: Context,
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
        mode: Int
    ): Boolean {
        val pending = PendingFileChooser(callback, acceptTypes, mode)
        val previous = synchronized(fileChooserLock) {
            val old = pendingFileChooser
            pendingFileChooser = pending
            old
        }
        previous?.callback?.onReceiveValue(null)
        val intent = Intent(context, BrowserFileChooserActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(BrowserFileChooserActivity.EXTRA_ACCEPT_TYPES, acceptTypes)
            .putExtra(BrowserFileChooserActivity.EXTRA_MODE, mode)
        return runCatching {
            context.startActivity(intent)
            true
        }.onFailure {
            synchronized(fileChooserLock) {
                if (pendingFileChooser === pending) pendingFileChooser = null
            }
            callback.onReceiveValue(null)
        }.getOrDefault(false)
    }

    fun hasPendingFileChooser(): Boolean =
        synchronized(fileChooserLock) { pendingFileChooser != null }

    fun completeFileChooser(resultCode: Int, data: Intent?) {
        val pending = synchronized(fileChooserLock) {
            val current = pendingFileChooser
            pendingFileChooser = null
            current
        } ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            pending.callback.onReceiveValue(null)
            return
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let(uris::add)
            }
        }
        data.data?.let { if (uris.isEmpty()) uris += it }
        pending.callback.onReceiveValue(
            if (uris.isEmpty()) null else uris.toTypedArray()
        )
    }

    /** Complete the pending chooser with a file supplied by an agent tool. */
    fun deliverUpload(uri: Uri): Boolean {
        val pending = synchronized(fileChooserLock) {
            val current = pendingFileChooser
            pendingFileChooser = null
            current
        } ?: return false
        pending.callback.onReceiveValue(arrayOf(uri))
        return true
    }

    // ---- downloads ------------------------------------------------------

    /** Forget the previous download before a new click is expected to start one. */
    fun clearDownload() {
        downloadState = null
    }

    fun startDownload(url: String): DownloadState {
        val record = DownloadState(
            id = "download-${System.currentTimeMillis()}-${System.nanoTime()}",
            url = url,
            name = downloadName(url),
            state = "downloading"
        )
        synchronized(downloadLock) {
            downloadState = record
            _downloads.value = (listOf(record) + _downloads.value).take(MAX_DOWNLOADS)
        }
        return record
    }

    fun updateDownloadProgress(id: String, downloaded: Long, total: Long) {
        synchronized(downloadLock) {
            val current = _downloads.value.firstOrNull { it.id == id } ?: return
            val updated = current.copy(
                downloadedBytes = downloaded.coerceAtLeast(0L),
                totalBytes = total.coerceAtLeast(-1L)
            )
            replaceDownload(updated)
            if (downloadState?.id == id) downloadState = updated
        }
    }

    fun finishDownload(id: String, result: BrowserDownloader.DownloadResult): DownloadState {
        val current = _downloads.value.firstOrNull { it.id == id }
            ?: startDownload(result.uri)
        val finished = current.copy(
            name = result.name,
            state = "done",
            downloadedBytes = result.size,
            totalBytes = result.size,
            path = result.path,
            uri = result.uri,
            size = result.size,
            error = null
        )
        synchronized(downloadLock) {
            replaceDownload(finished)
            if (downloadState?.id == id) downloadState = finished
        }
        return finished
    }

    fun failDownload(id: String, error: Throwable): DownloadState {
        val current = _downloads.value.firstOrNull { it.id == id }
            ?: startDownload("")
        val failed = current.copy(
            state = "failed",
            error = error.message ?: error.javaClass.simpleName
        )
        synchronized(downloadLock) {
            replaceDownload(failed)
            if (downloadState?.id == id) downloadState = failed
        }
        return failed
    }

    suspend fun download(context: Context, url: String): DownloadState {
        val record = startDownload(url)
        return try {
            val result = BrowserDownloader.download(context, url) { downloaded, total ->
                updateDownloadProgress(record.id, downloaded, total)
            }
            finishDownload(record.id, result)
        } catch (t: Throwable) {
            failDownload(record.id, t)
            throw t
        }
    }

    fun latestDownload(): DownloadState? = downloadState

    fun deleteDownload(context: Context, id: String): Boolean {
        val target = synchronized(downloadLock) {
            val item = _downloads.value.firstOrNull { it.id == id } ?: return false
            if (item.state == "downloading") return false
            _downloads.value = _downloads.value.filterNot { it.id == id }
            if (downloadState?.id == id) downloadState = null
            item
        }
        target.uri?.let { raw ->
            runCatching {
                val uri = Uri.parse(raw)
                when (uri.scheme?.lowercase()) {
                    "content" -> context.contentResolver.delete(uri, null, null) > 0
                    "file" -> uri.path?.let { File(it).delete() } == true
                    else -> false
                }
            }
        }
        target.path?.let { path ->
            runCatching { File(path).delete() }
        }
        return true
    }

    suspend fun awaitDownload(timeoutMs: Long = 30_000): DownloadState? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = downloadState
        while (current?.state == "downloading" && System.currentTimeMillis() < deadline) {
            delay(200)
            current = downloadState
        }
        return current
    }

    private fun replaceDownload(updated: DownloadState) {
        _downloads.value = _downloads.value.map { item ->
            if (item.id == updated.id) updated else item
        }
    }

    private fun downloadName(url: String): String {
        val fromUrl = runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
        return fromUrl ?: "download"
    }

    fun normalizeInput(input: String?): String {
        val value = input?.trim().orEmpty()
        return when {
            value.isBlank() -> HOME_URL
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.contains(' ') || !value.contains('.') -> SEARCH_URL + Uri.encode(value)
            else -> "https://$value"
        }
    }
}
