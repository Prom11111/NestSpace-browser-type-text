package com.coke.nestspace.tools

import android.content.Context
import android.net.Uri
import android.webkit.WebView
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.browser.BrowserSession
import androidx.core.content.FileProvider
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.task.OperationTaskRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Agent-facing operations for the built-in multi-tab browser.
 *
 * browser_open creates a new headless tab by default, so the assistant can
 * keep several pages alive (compare results, read one while another loads)
 * without covering the chat. Every page tool accepts tab_id; browser_tabs
 * lists them, browser_switch_tab selects one and browser_close_tab releases it.
 */
internal object BrowserTools {

    private fun tabIdArg(args: JSONObject): String? =
        args.optString("tab_id").takeIf { it.isNotBlank() }

    private fun tabById(id: String?): BrowserSession.Tab? =
        id?.let { wanted -> BrowserSession.snapshot().firstOrNull { it.id == wanted } }

    private fun currentTab(args: JSONObject): BrowserSession.Tab? =
        tabById(tabIdArg(args))
            ?: BrowserSession.snapshot().firstOrNull { it.active }
            ?: BrowserSession.snapshot().firstOrNull { it.selected }
            ?: BrowserSession.snapshot().firstOrNull()

    private fun tabJson(tab: BrowserSession.Tab): JSONObject = JSONObject()
        .put("tab_id", tab.id)
        .put("url", tab.url)
        .put("title", tab.title)
        .put("mode", BrowserSession.modeFor(tab.id))
        .put("loaded", tab.loaded)
        .put("loading", tab.loading)
        .put("selected", tab.selected)
        .put("active", tab.active)
        .put("can_go_back", tab.canGoBack)
        .put("can_go_forward", tab.canGoForward)

    private fun tabsJson(): JSONArray = JSONArray().apply {
        BrowserSession.snapshot().forEach { put(tabJson(it)) }
    }

    private fun downloadResult(
        download: BrowserSession.DownloadState,
        checks: Int,
        maxChecks: Int,
        intervalMs: Long,
        timedOut: Boolean = false
    ): ToolResult {
        val percent = download.progress?.let {
            (it * 100f).toInt().coerceIn(0, 100)
        } ?: -1
        val terminal = download.state == "done" ||
            download.state == "failed" ||
            download.state == "cancelled"
        val text = JSONObject()
            .put("state", download.state)
            .put("terminal", terminal)
            .put("downloaded", download.state == "done")
            .put("download_id", download.id)
            .put("task_id", download.taskId.orEmpty())
            .put("url", download.url)
            .put("name", download.name)
            .put("path", download.path.orEmpty())
            .put("uri", download.uri.orEmpty())
            .put("size", download.size)
            .put("downloaded_bytes", download.downloadedBytes)
            .put("total_bytes", download.totalBytes)
            .put("progress_percent", percent)
            .put("checks", checks)
            .put("max_checks", maxChecks)
            .put("interval_ms", intervalMs)
            .put("poll_timed_out", timedOut)
            .put(
                "message",
                when (download.state) {
                    "done" -> "download completed"
                    "failed" -> "download failed"
                    "cancelled" -> "download cancelled"
                    "cancelling" -> "download cancellation in progress"
                    else -> "download is still running; use task_wait with this task_id"
                }
            )
            .put("error", download.error.orEmpty())
            .toString()
        return when (download.state) {
            "done" -> ToolResult(true, text)
            "failed" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_FAILED"
            )
            "cancelled" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_CANCELLED"
            )
            "cancelling" -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_CANCELLING"
            )
            else -> ToolResult(
                false,
                text,
                errorCode = "DOWNLOAD_IN_PROGRESS"
            )
        }
    }

    private fun startedDownloadResult(
        download: BrowserSession.DownloadState,
        started: Boolean
    ): ToolResult {
        val failed = download.state == "failed"
        val terminal = download.state == "done" ||
            failed ||
            download.state == "cancelled"
        return ToolResult(
            !failed && download.state != "cancelled",
            JSONObject()
                .put("started", started)
                .put("attached", !started)
                .put("state", download.state)
                .put("terminal", terminal)
                .put("download_id", download.id)
                .put("task_id", download.taskId.orEmpty())
                .put("url", download.url)
                .put("name", download.name)
                .put(
                    "next",
                    if (download.state == "downloading" || download.state == "cancelling") {
                        "Use task_wait with this task_id. Do not report completion until " +
                            "terminal=true and state=succeeded."
                    } else {
                        "Inspect state before continuing."
                    }
                )
                .put("error", download.error.orEmpty())
                .toString(),
            errorCode = if (failed) "DOWNLOAD_FAILED" else null
        )
    }

    private fun startedTaskResult(
        task: OperationTaskRegistry.Snapshot,
        started: Boolean,
        errorCode: String? = null
    ): ToolResult = ToolResult(
        errorCode == null &&
            (!task.terminal || task.state == OperationTaskRegistry.State.SUCCEEDED),
        JSONObject()
            .put("started", started)
            .put("attached", !started)
            .put("state", task.state.api)
            .put("terminal", task.terminal)
            .put("task_id", task.id)
            .put("request_id", task.requestId ?: JSONObject.NULL)
            .put("state_revision", task.stateRevision)
            .put("progress_revision", task.progressRevision)
            .put("message", task.message)
            .put("result", task.result ?: "")
            .put("error", task.error ?: "")
            .put("cleanup_error", task.cleanupError ?: "")
            .put("next", "Use task_wait until terminal=true.")
            .toString(),
        errorCode = errorCode
    )

    /**
     * Publish the page the assistant is working on so the live preview card
     * can render a small chrome-less WebView of it.
     */
    private fun publishPreview(tabId: String? = null) {
        val tab = tabById(tabId)
            ?: BrowserSession.snapshot().firstOrNull { it.active }
            ?: BrowserSession.snapshot().firstOrNull()
            ?: return
        LivePreview.publishBrowser(
            url = tab.url,
            title = tab.title,
            label = tab.title.ifBlank { tab.url }
        )
    }

    private fun noTabResult(args: JSONObject): ToolResult {
        val id = tabIdArg(args)
        return if (id != null && tabById(id) == null) {
            ToolResult(false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND")
        } else {
            ToolResult(false, "browser is not open", errorCode = "BROWSER_NOT_OPEN")
        }
    }

    fun create(context: Context): List<ToolSpec> = listOf(
        ToolSpec(
            name = "browser_open",
            description = "Open a URL in the built-in browser. It creates a NEW tab by default and " +
                "runs headless (no browser window), so several pages can stay open at once. " +
                "Pass tab_id to navigate an existing tab, or new_tab=false to reuse the active " +
                "tab. Set headless=false to show the browser UI. Non-URL text becomes a Bing search.",
            inputSchema = toolSchema(
                "url" to "string",
                "headless" to "boolean",
                "new_tab" to "boolean",
                "tab_id" to "string",
                "wait_ms" to "integer",
                required = listOf("url")
            ),
            maxWaitMs = 1_500L
        ) { args, ctx ->
            val url = BrowserSession.normalizeInput(args.optString("url"))
            val headless = args.optBoolean("headless", true)
            val newTab = args.optBoolean("new_tab", true)
            val requestedTab = tabIdArg(args)
            val waitMs = args.optInt("wait_ms", 15_000).coerceIn(0, 60_000)
            val opened = runCatching {
                withContext(Dispatchers.Main) {
                    BrowserSession.open(
                        context = context,
                        url = url,
                        headless = headless,
                        newTab = newTab,
                        tabId = requestedTab,
                        ownerId = ctx.ownerId
                    )
                }
            }.getOrElse { error ->
                val code = (error as? BrowserSession.BrowserException)?.code
                    ?: "BROWSER_OPEN_FAILED"
                return@ToolSpec ToolResult(
                    false,
                    error.message ?: error.javaClass.simpleName,
                    errorCode = code
                )
            }
            if (waitMs > 0) {
                BrowserSession.awaitPageLoad(opened, waitMs.toLong())
            }
            val tab = tabById(opened)
            val challenge = withContext(Dispatchers.Main) {
                BrowserSession.webView(opened)?.let { detectChallenge(it) }
            }
            publishPreview(opened)
            ToolResult(
                true,
                JSONObject()
                    .put("opened", true)
                    .put("tab_id", opened)
                    .put("tab_count", BrowserSession.tabCount())
                    .put("mode", BrowserSession.modeFor(opened))
                    .put("loaded", tab?.loaded ?: false)
                    .put("url", tab?.url ?: url)
                    .put("title", tab?.title.orEmpty())
                    .put("tabs", tabsJson())
                    .annotateChallenge(challenge, opened)
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_hand_to_user",
            description = "Hand a browser tab to the user when a page shows a captcha, human " +
                "verification, login wall, or blocked/anti-bot page. Asks the user to confirm, " +
                "then opens the visible browser on that tab, waits while the user completes the " +
                "step, then returns the page state. " +
                "Page tools add needs_user/user_reason when they detect such a page; call this " +
                "instead of trying to bypass the check. Call browser_read_text afterwards to verify.",
            inputSchema = toolSchema(
                "tab_id" to "string",
                "reason" to "string",
                "message" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
                ?: BrowserSession.activeTabId()
                ?: BrowserSession.selectedTabId()
                ?: return@ToolSpec ToolResult(
                    false,
                    "browser is not open",
                    errorCode = "BROWSER_NOT_OPEN"
                )
            if (tabById(id) == null) {
                return@ToolSpec ToolResult(
                    false,
                    "tab not found: $id",
                    errorCode = "BROWSER_TAB_NOT_FOUND"
                )
            }
            val detected = withContext(Dispatchers.Main) {
                BrowserSession.webView(id)?.let { detectChallenge(it) }
            }
            val reason = args.optString("reason").trim().lowercase()
                .ifBlank { detected?.reason ?: "verification" }
            val title = when (reason) {
                "captcha", "verification" ->
                    AppLanguage.tr("需要你完成验证", "Verification required")
                "blocked" -> AppLanguage.tr("需要你处理网站拦截", "Website needs your action")
                "login" -> AppLanguage.tr("需要你完成登录", "Login required")
                else -> AppLanguage.tr("需要你操作网页", "Your action is needed")
            }
            val detail = args.optString("message").takeIf { it.isNotBlank() }
                ?: AppLanguage.tr(
                    "浏览器已停在这个页面。请在页面中完成验证或操作，然后点“完成”。",
                    "The browser is on this page. Complete the check or action there, then tap Done."
                )
            val result = UserGateManager.takeOver(
                context = context,
                title = title,
                detail = detail,
                packageName = "",
                instanceId = "",
                slot = -1,
                reason = reason,
                target = UserGateManager.TakeoverTarget.BROWSER,
                tabId = id,
                url = tabById(id)?.url,
                bringToFront = {
                    withContext(Dispatchers.Main) {
                        BrowserSession.showTab(context, id)
                    }
                }
            )
            when (result) {
                UserGateManager.TakeoverResult.COMPLETED -> {
                    val tab = tabById(id)
                    val remaining = withContext(Dispatchers.Main) {
                        BrowserSession.webView(id)?.let { detectChallenge(it) }
                    }
                    val text = withContext(Dispatchers.Main) {
                        BrowserSession.webView(id)?.let { readText(it) }
                    }.orEmpty()
                    ToolResult(
                        true,
                        JSONObject()
                            .put("takeover", "completed")
                            .put("reason", reason)
                            .put("tab_id", id)
                            .put("url", tab?.url.orEmpty())
                            .put("title", tab?.title.orEmpty())
                            .put("text", text.take(2_000))
                            .put("still_blocked", remaining != null)
                            .annotateChallenge(remaining, id)
                            .toString()
                    )
                }
                UserGateManager.TakeoverResult.SKIPPED -> ToolResult(
                    false,
                    "The user skipped the browser takeover.",
                    errorCode = "TAKEOVER_SKIPPED"
                )
                UserGateManager.TakeoverResult.CANCELLED -> ToolResult(
                    false,
                    "The browser is not reachable for takeover.",
                    errorCode = "TAKEOVER_CANCELLED"
                )
                UserGateManager.TakeoverResult.TIMEOUT -> ToolResult(
                    false,
                    "Timed out waiting for the user to finish.",
                    errorCode = "TAKEOVER_TIMEOUT"
                )
            }
        },
        ToolSpec(
            name = "browser_navigate",
            description = "Navigate a browser tab to a URL or Bing search. Defaults to the active tab; " +
                "pass tab_id to target another open page.",
            inputSchema = toolSchema(
                "url" to "string",
                "tab_id" to "string",
                required = listOf("url")
            ),
            maxWaitMs = 1_500L
        ) { args, _ ->
            val url = BrowserSession.normalizeInput(args.optString("url"))
            val tabId = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.navigate(url, tabId) }
            if (ok) publishPreview(tabId)
            ToolResult(
                ok,
                if (ok) {
                    JSONObject()
                        .put("navigated", true)
                        .put("tab_id", tabId ?: BrowserSession.activeTabId())
                        .put("url", url)
                        .toString()
                } else {
                    "browser tab is not open"
                },
                errorCode = if (ok) null else "BROWSER_NOT_OPEN"
            )
        },
        ToolSpec(
            name = "browser_status",
            description = "Return the active browser tab and every open tab (id, URL, title, mode, " +
                "navigation state).",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val tab = currentTab(args) ?: return@ToolSpec noTabResult(args)
            publishPreview(tab.id)
            ToolResult(
                true,
                JSONObject()
                    .put("open", true)
                    .put("mode", BrowserSession.currentMode())
                    .put("active_tab_id", BrowserSession.activeTabId() ?: JSONObject.NULL)
                    .put("selected_tab_id", BrowserSession.selectedTabId() ?: JSONObject.NULL)
                    .put("tab", tabJson(tab))
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_tabs",
            description = "List all open browser tabs with their tab_id, URL, title and state.",
            inputSchema = toolSchema()
        ) { _, _ ->
            publishPreview()
            ToolResult(
                true,
                JSONObject()
                    .put("tabs", tabsJson())
                    .put("active_tab_id", BrowserSession.activeTabId() ?: JSONObject.NULL)
                    .put("selected_tab_id", BrowserSession.selectedTabId() ?: JSONObject.NULL)
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_switch_tab",
            description = "Make an open tab active and, when the browser UI is visible, show it.",
            inputSchema = toolSchema(
                "tab_id" to "string",
                required = listOf("tab_id")
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            if (id == null) return@ToolSpec ToolResult(
                false, "tab_id is required", errorCode = "INVALID_ARGUMENT"
            )
            val ok = withContext(Dispatchers.Main) { BrowserSession.selectTab(id) }
            if (!ok) return@ToolSpec ToolResult(
                false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND"
            )
            val tab = tabById(id)
            publishPreview(id)
            ToolResult(
                true,
                JSONObject()
                    .put("switched", true)
                    .put("tab", tab?.let(::tabJson) ?: JSONObject.NULL)
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_close_tab",
            description = "Close a browser tab. Defaults to the selected tab. Returns the remaining tabs.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args) ?: BrowserSession.selectedTabId()
                ?: BrowserSession.activeTabId()
                ?: return@ToolSpec ToolResult(
                    false, "browser is not open", errorCode = "BROWSER_NOT_OPEN"
                )
            val ok = withContext(Dispatchers.Main) { BrowserSession.closeTab(id) }
            if (!ok) return@ToolSpec ToolResult(
                false, "tab not found: $id", errorCode = "BROWSER_TAB_NOT_FOUND"
            )
            if (BrowserSession.tabCount() == 0) {
                if (LivePreview.frame.value?.kind == LivePreview.KIND_BROWSER) {
                    LivePreview.clear()
                }
            } else {
                publishPreview()
            }
            ToolResult(
                true,
                JSONObject()
                    .put("closed", id)
                    .put("tabs", tabsJson())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_back",
            description = "Go back in a browser tab's history. Defaults to the active tab; pass tab_id " +
                "to target another page.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.back(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser went back" else "browser cannot go back",
                errorCode = if (ok) null else "BROWSER_BACK_FAILED"
            )
        },
        ToolSpec(
            name = "browser_forward",
            description = "Go forward in a browser tab's history. Defaults to the active tab; pass tab_id " +
                "to target another page.",
            inputSchema = toolSchema("tab_id" to "string")
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.forward(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser went forward" else "browser cannot go forward",
                errorCode = if (ok) null else "BROWSER_FORWARD_FAILED"
            )
        },
        ToolSpec(
            name = "browser_reload",
            description = "Reload a browser tab. Defaults to the active tab; pass tab_id to target " +
                "another page.",
            inputSchema = toolSchema("tab_id" to "string"),
            maxWaitMs = 1_500L
        ) { args, _ ->
            val id = tabIdArg(args)
            val ok = withContext(Dispatchers.Main) { BrowserSession.reload(id) }
            if (ok) publishPreview(id)
            ToolResult(
                ok,
                if (ok) "browser reloaded" else "browser is not open",
                errorCode = if (ok) null else "BROWSER_NOT_OPEN"
            )
        },
        ToolSpec(
            name = "browser_links",
            description = "List links, buttons and image-buttons on a page. Each item includes a CSS " +
                "selector that can be passed to browser_click. Defaults to the active tab; pass " +
                "tab_id to inspect another page.",
            inputSchema = toolSchema(
                "max_links" to "integer",
                "tab_id" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                val limit = args.optInt("max_links", 80).coerceIn(1, 300)
                val script = """
                    (function(){
                      var out=[],seen={};
                      function add(n,kind){
                        var text=(n.innerText||n.value||n.getAttribute('aria-label')||n.getAttribute('alt')||'')
                          .replace(/\s+/g,' ').trim();
                        var url=n.href||'';
                        var selector='';
                        if(n.id) selector='#'+n.id;
                        else if(n.classList && n.classList.length) selector='.'+n.classList[0];
                        var key=kind+'|'+url+'|'+selector+'|'+text;
                        if(seen[key]) return;
                        seen[key]=1;
                        out.push({kind:kind,text:text.slice(0,160),url:url,selector:selector});
                      }
                      var links=document.querySelectorAll('a[href]');
                      for(var i=0;i<links.length && out.length<$limit;i++) add(links[i],'link');
                      var clickable=document.querySelectorAll(
                        'button,[role=button],input[type=submit],img[data-log],img[class*=Btn],img[class*=btn]'
                      );
                      for(var j=0;j<clickable.length && out.length<$limit;j++) add(clickable[j],'button');
                      return JSON.stringify(out);
                    })()
                """.trimIndent()
                val raw = evaluate(webView, script)
                val links = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
                val challenge = detectChallenge(webView)
                publishPreview(id)
                ToolResult(
                    true,
                    JSONObject()
                        .put("tab_id", id ?: BrowserSession.activeTabId())
                        .put("mode", BrowserSession.modeFor(id))
                        .put("url", webView.url.orEmpty())
                        .put("links", links)
                        .annotateChallenge(challenge, id ?: BrowserSession.activeTabId())
                        .toString()
                )
            }
        },
        ToolSpec(
            name = "browser_click",
            description = "Click an element by its visible text or by a CSS selector. Image-only " +
                "download buttons often need a selector from browser_links. Defaults to the active " +
                "tab; pass tab_id to click in another page.",
            inputSchema = toolSchema(
                "text" to "string",
                "selector" to "string",
                "tab_id" to "string"
            )
        ) { args, ctx ->
            val text = args.optString("text").trim()
            val selector = args.optString("selector").trim()
            if (text.isBlank() && selector.isBlank()) return@ToolSpec ToolResult(
                false,
                "text or selector is required",
                errorCode = "INVALID_ARGUMENT"
            )
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                BrowserSession.clearDownload(ctx.ownerId)
                BrowserSession.setDownloadOwner(
                    id ?: BrowserSession.activeTabId(),
                    ctx.ownerId
                )
                val needle = if (text.isBlank()) "null" else JSONObject.quote(text)
                val sel = if (selector.isBlank()) "null" else JSONObject.quote(selector)
                val script = """
                    (function(){
                      var needle=$needle, sel=$sel;
                      var best=null,bestText='',bestScore=1e18;
                      if (sel) {
                        best=document.querySelector(sel);
                        if (best) bestText=(best.innerText||best.value||best.getAttribute('aria-label')||best.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
                      } else {
                        var nodes=document.querySelectorAll(
                          'a,button,[role=button],input[type=submit],[onclick],div,span,p,li,img'
                        );
                        for (var i=0;i<nodes.length;i++) {
                          var n=nodes[i];
                          var t=(n.innerText||n.value||n.getAttribute('aria-label')||n.getAttribute('alt')||'').replace(/\s+/g,' ').trim();
                          if (t.indexOf(needle)<0) continue;
                          var r=n.getBoundingClientRect();
                          var visible=(r.width>0 && r.height>0) ? 0 : 1e9;
                          var score=visible + t.length*1000 + (r.width*r.height)/1000;
                          if (score<bestScore) { best=n; bestText=t; bestScore=score; }
                        }
                      }
                      if (!best) return JSON.stringify({clicked:false});
                      best.scrollIntoView({block:'center'});
                      best.click();
                      return JSON.stringify({
                        clicked:true,
                        text:bestText.slice(0,160),
                        url:best.href||'',
                        selector:sel||''
                      });
                    })()
                """.trimIndent()
                val raw = evaluate(webView, script)
                val clicked = runCatching { JSONObject(raw) }
                    .getOrDefault(JSONObject().put("clicked", false))
                clicked.put("tab_id", id ?: BrowserSession.activeTabId())
                if (clicked.optBoolean("clicked", false)) publishPreview(id)
                ToolResult(
                    clicked.optBoolean("clicked", false),
                    clicked.toString(),
                    errorCode = if (clicked.optBoolean("clicked", false)) null else "CLICK_NOT_FOUND"
                )
            }
        },
        ToolSpec(
            name = "browser_type_text",
            description = "Type text into an input, textarea, or contenteditable element in the built-in browser. " +
                "Provide a CSS selector; pass clear_first=true to replace existing content. Defaults to the active tab; pass tab_id to target another page.",
            inputSchema = toolSchema(
                "selector" to "string",
                "text" to "string",
                "clear_first" to "boolean",
                "tab_id" to "string",
                required = listOf("selector", "text")
            )
        ) { args, _ ->
            val selector = args.optString("selector").trim()
            val text = args.optString("text")
            if (selector.isBlank()) return@ToolSpec ToolResult(
                false, "selector is required", errorCode = "INVALID_ARGUMENT"
            )
            if (text.isEmpty()) return@ToolSpec ToolResult(
                false, "text is required", errorCode = "INVALID_ARGUMENT"
            )
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                val sel = JSONObject.quote(selector)
                val value = JSONObject.quote(text)
                val clearFirst = args.optBoolean("clear_first", false)
                val script = """
                    (function(){
                      var selector=$sel, text=$value, clearFirst=$clearFirst;
                      var el;
                      try { el=document.querySelector(selector); }
                      catch(e) { return JSON.stringify({typed:false,error:'invalid_selector',detail:String(e)}); }
                      if (!el) return JSON.stringify({typed:false,error:'element_not_found'});
                      var tag=(el.tagName||'').toLowerCase();
                      var editable=el.isContentEditable || el.getAttribute('contenteditable')==='true';
                      var input=(tag==='input' || tag==='textarea');
                      if (!input && !editable) return JSON.stringify({typed:false,error:'not_editable'});
                      if (el.disabled || el.readOnly || el.getAttribute('aria-disabled')==='true')
                        return JSON.stringify({typed:false,error:'not_editable'});
                      var rect=el.getBoundingClientRect(), style=getComputedStyle(el);
                      if (!rect.width || !rect.height || style.visibility==='hidden' || style.display==='none')
                        return JSON.stringify({typed:false,error:'element_not_visible'});
                      el.scrollIntoView({block:'center',inline:'nearest'});
                      el.focus();
                      if (editable) {
                        if (clearFirst) {
                          el.textContent='';
                          var range=document.createRange(); range.selectNodeContents(el);
                          var selection=window.getSelection(); selection.removeAllRanges(); selection.addRange(range);
                        }
                        var inserted=false;
                        try { inserted=document.execCommand('insertText',false,text); } catch(e) {}
                        if (!inserted) {
                          var selection=window.getSelection();
                          if (selection && selection.rangeCount && el.contains(selection.anchorNode)) {
                            var range=selection.getRangeAt(0); range.deleteContents();
                            range.insertNode(document.createTextNode(text));
                            range.collapse(false); selection.removeAllRanges(); selection.addRange(range);
                          } else { el.appendChild(document.createTextNode(text)); }
                        }
                      } else {
                        var proto=tag==='textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                        var setter=Object.getOwnPropertyDescriptor(proto,'value');
                        if (clearFirst) {
                          if (setter && setter.set) setter.set.call(el,''); else el.value='';
                          el.setSelectionRange(0,0);
                        }
                        var start=typeof el.selectionStart==='number' ? el.selectionStart : el.value.length;
                        var end=typeof el.selectionEnd==='number' ? el.selectionEnd : start;
                        if (typeof el.setRangeText==='function') el.setRangeText(text,start,end,'end');
                        else if (setter && setter.set) setter.set.call(el,el.value.slice(0,start)+text+el.value.slice(end));
                        else el.value=el.value.slice(0,start)+text+el.value.slice(end);
                      }
                      var inputEvent;
                      try { inputEvent=new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}); }
                      catch(e) { inputEvent=new Event('input',{bubbles:true}); }
                      el.dispatchEvent(inputEvent);
                      el.dispatchEvent(new Event('change',{bubbles:true}));
                      return JSON.stringify({typed:true,selector:selector,tag:tag,characters:text.length,value:(el.value!==undefined?el.value:el.innerText||'').slice(0,500)});
                    })()
                """.trimIndent()
                val raw = evaluate(webView, script)
                val result = runCatching { JSONObject(raw) }
                    .getOrDefault(JSONObject().put("typed", false).put("error", "invalid_result"))
                result.put("tab_id", id ?: BrowserSession.activeTabId())
                val typed = result.optBoolean("typed", false)
                if (typed) publishPreview(id)
                ToolResult(
                    typed,
                    result.toString(),
                    errorCode = if (typed) null else when (result.optString("error")) {
                        "element_not_found" -> "ELEMENT_NOT_FOUND"
                        "invalid_selector" -> "INVALID_SELECTOR"
                        "element_not_visible" -> "ELEMENT_NOT_VISIBLE"
                        "not_editable" -> "ELEMENT_NOT_EDITABLE"
                        else -> "TYPE_TEXT_FAILED"
                    }
                )
            }
        },
        ToolSpec(
            name = "browser_download",
            description = "Start a file download from a URL and return its download_id immediately. " +
                "Use task_wait with task_id until terminal=true. " +
                "If url is omitted, attach to the page-triggered download started by browser_click. " +
                "Starting a new download cancels only the previous download owned by the same " +
                "assistant session.",
            inputSchema = toolSchema(
                "url" to "string",
                "tab_id" to "string",
                "request_id" to "string"
            ),
            nativeAsync = true
        ) { args, ctx ->
            val input = args.optString("url").trim()
            if (input.isBlank()) {
                val state = BrowserSession.latestDownload(ctx.ownerId)
                    ?: return@ToolSpec ToolResult(
                        false,
                        "no browser download has started",
                        errorCode = "DOWNLOAD_NOT_FOUND"
                    )
                return@ToolSpec startedDownloadResult(
                    download = state,
                    started = false
                )
            }
            val id = tabIdArg(args)
            val currentUrl = withContext(Dispatchers.Main) {
                BrowserSession.webView(id)?.url
            }
            val url = resolveUrl(currentUrl, input)
            val requestId = args.optString("request_id")
                .trim()
                .takeIf(String::isNotBlank)
            if (ctx.caller != Caller.USER &&
                ToolPermissionSettings.shouldConfirm(
                    context,
                    ToolConfirmationCategory.DOWNLOAD
                )
            ) {
                val allowed = UserGateManager.askConfirmation(
                    title = AppLanguage.tr("需要确认", "Confirmation needed"),
                    detail = AppLanguage.tr(
                        "下载这个文件？\n$url",
                        "Download this file?\n$url"
                    )
                )
                if (!allowed) {
                    return@ToolSpec ToolResult(
                        false,
                        AppLanguage.tr("用户拒绝了这次下载", "The user denied this download"),
                        errorCode = "USER_DENIED"
                    )
                }
            }
            val started = BrowserSession.enqueueDownload(
                context = context,
                url = url,
                ownerId = ctx.ownerId,
                requestId = requestId,
                requestHash = requestHash(url),
                parentTaskId = ctx.parentTaskId
            )
            when {
                started.errorCode != null -> ToolResult(
                    false,
                    startedTaskResult(
                        task = started.task,
                        started = false,
                        errorCode = started.errorCode
                    ).text,
                    errorCode = started.errorCode
                )
                started.download != null -> startedDownloadResult(
                    download = started.download,
                    started = started.created
                )
                else -> startedTaskResult(
                    task = started.task,
                    started = started.created
                )
            }
        },
        ToolSpec(
            name = "browser_download_status",
            description = "Read one browser download snapshot by download_id for detailed file and " +
                "byte information. Use task_wait for lifecycle waiting. State=downloading is not " +
                "success; state=done is complete and " +
                "state=failed is terminal failure.",
            inputSchema = toolSchema(
                "download_id" to "string",
                required = listOf("download_id")
            )
        ) { args, ctx ->
            val downloadId = args.optString("download_id").trim()
            if (downloadId.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "download_id is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            val state = BrowserSession.downloadStatus(
                id = downloadId,
                ownerId = ctx.ownerId
            ) ?: return@ToolSpec ToolResult(
                false,
                "download not found: $downloadId",
                errorCode = "DOWNLOAD_NOT_FOUND"
            )
            downloadResult(
                download = state,
                checks = 1,
                maxChecks = 1,
                intervalMs = 0L
            )
        },
        ToolSpec(
            name = "browser_upload",
            description = "Complete a pending browser file-upload chooser with a local file. " +
                "First tap or click the page's upload control so its file chooser opens, then " +
                "pass either path (absolute file path) or uri (content:// or file://).",
            inputSchema = toolSchema(
                "path" to "string",
                "uri" to "string"
            ),
            mutating = true
        ) { args, ctx ->
            val rawPath = args.optString("path").trim()
            val rawUri = args.optString("uri").trim()
            if (rawPath.isBlank() && rawUri.isBlank()) {
                return@ToolSpec ToolResult(
                    false,
                    "path or uri is required",
                    errorCode = "INVALID_ARGUMENT"
                )
            }
            if (!BrowserSession.hasPendingFileChooser()) {
                return@ToolSpec ToolResult(
                    false,
                    "No pending file chooser. Tap or click the page's upload button first.",
                    errorCode = "NO_FILE_CHOOSER"
                )
            }
            val uri = if (rawUri.isNotBlank()) {
                Uri.parse(rawUri)
            } else {
                val path = if (rawPath.startsWith("file://")) {
                    Uri.parse(rawPath).path.orEmpty()
                } else {
                    rawPath
                }
                val file = File(path)
                if (!file.isFile || !file.canRead()) {
                    return@ToolSpec ToolResult(
                        false,
                        "file is missing or not readable: $path",
                        errorCode = "FILE_NOT_FOUND"
                    )
                }
                runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }.getOrElse {
                    return@ToolSpec ToolResult(
                        false,
                        "cannot expose file to the browser: ${it.message}",
                        errorCode = "UPLOAD_FAILED"
                    )
                }
            }
            val readable = runCatching {
                context.contentResolver.openInputStream(uri)?.use { true } == true
            }.getOrDefault(false)
            if (!readable) {
                return@ToolSpec ToolResult(
                    false,
                    "cannot read upload URI: $uri",
                    errorCode = "FILE_NOT_FOUND"
                )
            }
            if (ctx.caller != Caller.USER &&
                ToolPermissionSettings.shouldConfirm(
                    context,
                    ToolConfirmationCategory.RISKY
                )
            ) {
                val allowed = UserGateManager.askConfirmation(
                    title = AppLanguage.tr("上传文件？", "Upload a file?"),
                    detail = AppLanguage.tr(
                        "即将把文件上传到当前网页：${uri.lastPathSegment ?: uri}",
                        "Upload this file to the current page: ${uri.lastPathSegment ?: uri}"
                    )
                )
                if (!allowed) {
                    return@ToolSpec ToolResult(
                        false,
                        AppLanguage.tr("用户拒绝了这次上传", "The user denied this upload"),
                        errorCode = "USER_DENIED"
                    )
                }
            }
            val delivered = withContext(Dispatchers.Main) {
                BrowserSession.deliverUpload(uri)
            }
            if (!delivered) {
                return@ToolSpec ToolResult(
                    false,
                    "The file chooser is no longer pending.",
                    errorCode = "NO_FILE_CHOOSER"
                )
            }
            ToolResult(
                true,
                JSONObject()
                    .put("uploaded", true)
                    .put("uri", uri.toString())
                    .toString()
            )
        },
        ToolSpec(
            name = "browser_read_text",
            description = "Read the visible text of a browser tab. Defaults to the active tab; pass " +
                "tab_id to read another page. Useful after browser_open to let the model inspect a page.",
            inputSchema = toolSchema(
                "max_chars" to "integer",
                "tab_id" to "string"
            )
        ) { args, _ ->
            val id = tabIdArg(args)
            withContext(Dispatchers.Main) {
                val webView = BrowserSession.webView(id)
                    ?: return@withContext noTabResult(args)
                val text = readText(webView)
                val limit = args.optInt("max_chars", 20_000).coerceIn(500, 100_000)
                val challenge = detectChallenge(webView)
                publishPreview(id)
                ToolResult(
                    true,
                    JSONObject()
                        .put("tab_id", id ?: BrowserSession.activeTabId())
                        .put("mode", BrowserSession.modeFor(id))
                        .put("url", webView.url.orEmpty())
                        .put("title", webView.title.orEmpty())
                        .put("text", text.take(limit))
                        .annotateChallenge(challenge, id ?: BrowserSession.activeTabId())
                        .toString()
                )
            }
        }
    )

    private suspend fun readText(webView: WebView): String =
        evaluate(webView, "(function(){return document.body ? document.body.innerText : '';})()")

    private data class BrowserChallenge(val reason: String, val evidence: String)

    /**
     * Conservative anti-bot detection: interactive widgets or strong block
     * wording on a short page. Long articles that merely mention captchas do
     * not trigger the handoff hint.
     */
    private suspend fun detectChallenge(webView: WebView): BrowserChallenge? {
        val raw = evaluate(webView, CHALLENGE_SCRIPT)
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val signals = root.optJSONArray("signals").stringList()
        val words = root.optJSONArray("words").stringList()
        val url = root.optString("url").lowercase()
        val title = root.optString("title").lowercase()
        val textLength = root.optInt("text_length", 0)
        val captchaWord = words.any { CAPTCHA_WORDS.contains(it) }
        val blockedWord = words.any { BLOCKED_WORDS.contains(it) }
        val captchaUrl = CAPTCHA_URL_HINTS.any { url.contains(it) }
        val blockedUrl = BLOCKED_URL_HINTS.any { url.contains(it) }
        val titleHit = (CAPTCHA_WORDS + BLOCKED_WORDS).any { title.contains(it) }
        val shortPage = textLength in 1..1_500
        val textEvidence = (captchaWord || blockedWord) && (shortPage || titleHit)
        if (signals.isEmpty() && !textEvidence && !captchaUrl && !blockedUrl) return null
        val reason = if (signals.isNotEmpty() || captchaWord || captchaUrl) {
            "captcha"
        } else {
            "blocked"
        }
        val evidence = buildList {
            addAll(signals)
            addAll(words)
            if (captchaUrl || blockedUrl) add("url")
        }.distinct().joinToString(", ").take(240)
        return BrowserChallenge(reason, evidence)
    }

    private fun JSONObject.annotateChallenge(
        challenge: BrowserChallenge?,
        tabId: String?
    ): JSONObject {
        if (challenge == null) return this
        val target = tabId?.let { " with tab_id=$it" }.orEmpty()
        return put("needs_user", true)
            .put("user_reason", challenge.reason)
            .put(
                "challenge",
                JSONObject()
                    .put("reason", challenge.reason)
                    .put("evidence", challenge.evidence)
            )
            .put(
                "hint",
                "This page looks like an anti-bot or verification page. Call " +
                    "browser_hand_to_user$target to let the user complete it; " +
                    "do not try to bypass it."
            )
    }

    private fun JSONArray?.stringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private const val CHALLENGE_SCRIPT = """
        (function(){
          function visible(el){
            if(!el) return false;
            var r=el.getBoundingClientRect();
            if(r.width<=0 || r.height<=0) return false;
            var s=window.getComputedStyle(el);
            return s.display!=='none' && s.visibility!=='hidden' && s.opacity!=='0';
          }
          function shown(sel){
            try{
              var list=document.querySelectorAll(sel);
              for(var i=0;i<list.length;i++){ if(visible(list[i])) return true; }
            }catch(e){}
            return false;
          }
          var signals=[];
          if(window._cf_chl_opt || shown('#challenge-form') || shown('.cf-turnstile') ||
             shown('#cf-please-wait')) signals.push('cloudflare');
          if(shown('iframe[src*="recaptcha"]') || shown('.g-recaptcha') ||
             shown('iframe[src*="hcaptcha"]') || shown('.h-captcha')) signals.push('recaptcha');
          if(shown('.geetest_panel') || shown('.geetest_holder') ||
             shown('#nc_1_wrapper') || shown('[class*="nc_wrapper"]') ||
             shown('[id*="captcha"]') || shown('[class*="captcha"]') ||
             shown('img[src*="captcha"]')) signals.push('captcha-element');
          var text=((document.body&&document.body.innerText)||'')
            .replace(/\s+/g,' ').slice(0,4000);
          var hay=(text+' '+(document.title||'')+' '+(location.href||'')).toLowerCase();
          var checks=[
            '验证码','安全验证','人机验证','滑动验证','拖动滑块','完成验证','请完成验证','身份验证',
            '环境异常','异常流量','访问过于频繁','请求过于频繁','操作过于频繁','访问受限','拒绝访问',
            'captcha','verify you are human','are you a robot','human verification','unusual traffic',
            'access denied','too many requests','checking your browser','just a moment','security check'
          ];
          var words=[];
          for(var i=0;i<checks.length;i++){ if(hay.indexOf(checks[i])>=0) words.push(checks[i]); }
          return JSON.stringify({
            signals:signals,
            words:words,
            title:document.title||'',
            url:location.href||'',
            text_length:text.length
          });
        })()
    """

    private val CAPTCHA_WORDS = listOf(
        "验证码", "安全验证", "人机验证", "滑动验证", "拖动滑块", "完成验证",
        "请完成验证", "身份验证", "captcha", "verify you are human",
        "are you a robot", "human verification", "checking your browser",
        "just a moment", "security check"
    )

    private val BLOCKED_WORDS = listOf(
        "环境异常", "异常流量", "访问过于频繁", "请求过于频繁", "操作过于频繁",
        "访问受限", "拒绝访问", "unusual traffic", "access denied",
        "too many requests"
    )

    private val CAPTCHA_URL_HINTS = listOf(
        "captcha", "challenge", "/verify", "verify?", "antispider",
        "punish", "_security_check"
    )

    private val BLOCKED_URL_HINTS = listOf(
        "/sorry/", "403", "412", "blocked", "rate-limit", "too-many-requests"
    )

    private suspend fun evaluate(webView: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { value ->
                if (!continuation.isActive) return@evaluateJavascript
                val decoded = runCatching {
                    JSONTokener(value).nextValue() as? String ?: value
                }.getOrDefault(value)
                continuation.resume(decoded)
            }
        }

    private fun resolveUrl(base: String?, input: String): String {
        if (input.startsWith("http://") || input.startsWith("https://")) return input
        if (base.isNullOrBlank()) return input
        return runCatching {
            java.net.URI(base).resolve(input).toString()
        }.getOrDefault(input)
    }

    private fun requestHash(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
