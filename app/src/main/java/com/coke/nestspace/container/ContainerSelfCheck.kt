package com.coke.nestspace.container

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import com.coke.nestspace.AppGraph
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.engine.EngineResult
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.service.McpForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

enum class ContainerCheckStatus { PASS, PARTIAL, FAIL, SKIPPED, RUNNING }

data class ContainerCheckResult(
    val index: Int,
    val id: String,
    val title: String,
    val status: ContainerCheckStatus,
    val detail: String,
    val advice: String? = null,
    val durationMs: Long = 0
)

data class ContainerSelfCheckReport(
    val results: List<ContainerCheckResult>,
    val startedAt: Long,
    val totalMs: Long
) {
    fun render(context: Context): String {
        val passed = results.count { it.status == ContainerCheckStatus.PASS }
        val partial = results.count { it.status == ContainerCheckStatus.PARTIAL }
        val failed = results.count { it.status == ContainerCheckStatus.FAIL }
        val skipped = results.count { it.status == ContainerCheckStatus.SKIPPED }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val builder = StringBuilder()
        builder.appendLine(AppLanguage.tr("NestSpace 容器自检", "NestSpace container self-check"))
        builder.appendLine(
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(startedAt))
        )
        builder.appendLine("app $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${abi()}")
        builder.appendLine(
            AppLanguage.tr(
                "汇总：$passed 通过，$partial 部分，$failed 失败，$skipped 跳过，" +
                    "共 ${"%.1f".format(totalMs / 1000.0)} 秒",
                "Summary: $passed passed, $partial partial, $failed failed, " +
                    "$skipped skipped in ${"%.1f".format(totalMs / 1000.0)}s"
            )
        )
        builder.appendLine()
        for (result in results) {
            builder.append("${result.index}. ${result.title}: ${statusText(result.status)}")
            if (result.detail.isNotBlank()) builder.append(" — ${result.detail}")
            builder.appendLine()
            result.advice?.takeIf { it.isNotBlank() }?.let {
                builder.appendLine("   -> $it")
            }
        }
        return builder.toString().trim()
    }

    private fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun statusText(status: ContainerCheckStatus): String = when (status) {
        ContainerCheckStatus.PASS -> AppLanguage.tr("通过", "PASS")
        ContainerCheckStatus.PARTIAL -> AppLanguage.tr("部分", "PARTIAL")
        ContainerCheckStatus.FAIL -> AppLanguage.tr("失败", "FAIL")
        ContainerCheckStatus.SKIPPED -> AppLanguage.tr("跳过", "SKIPPED")
        ContainerCheckStatus.RUNNING -> AppLanguage.tr("检查中", "RUNNING")
    }
}

/**
 * Dependency-aware compatibility checks for the virtual-app container.
 *
 * Checks run in order; a failed dependency marks dependants as skipped rather
 * than producing a cascade of misleading failures. The report never includes
 * chat content, account data, or screenshots.
 */
class ContainerSelfCheckRunner(private val context: Context) {

    private data class Outcome(
        val status: ContainerCheckStatus,
        val detail: String,
        val advice: String? = null
    )

    private val engine = AppGraph.engine(context)
    private var apps: List<VirtualApp> = emptyList()
    private var sessionPackage: String? = null
    private var sessionSlot: Int? = null
    private var launchedHere = false

    suspend fun run(
        onUpdate: (ContainerCheckResult) -> Unit
    ): ContainerSelfCheckReport {
        val started = System.currentTimeMillis()
        val results = mutableListOf<ContainerCheckResult>()
        try {
            for (check in ContainerCheck.entries) {
                val blockedBy = check.needs().mapNotNull { dependency ->
                    results.firstOrNull { it.id == dependency.id }
                        ?.takeIf {
                            it.status != ContainerCheckStatus.PASS &&
                                it.status != ContainerCheckStatus.PARTIAL
                        }
                        ?.title
                }.firstOrNull()
                if (blockedBy != null) {
                    val skipped = result(
                        check,
                        Outcome(
                            ContainerCheckStatus.SKIPPED,
                            AppLanguage.tr("依赖 $blockedBy 未通过", "Blocked by $blockedBy")
                        )
                    )
                    results += skipped
                    onUpdate(skipped)
                    continue
                }

                onUpdate(result(check, Outcome(ContainerCheckStatus.RUNNING, "")))
                val begin = System.currentTimeMillis()
                val outcome = try {
                    withTimeout(check.timeoutMs) { runCheck(check) }
                } catch (timeout: TimeoutCancellationException) {
                    Outcome(
                        ContainerCheckStatus.FAIL,
                        AppLanguage.tr("检查超时", "Check timed out")
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    Outcome(
                        ContainerCheckStatus.FAIL,
                        t.message ?: t.javaClass.simpleName
                    )
                }
                val completed = result(
                    check,
                    outcome,
                    System.currentTimeMillis() - begin
                )
                results += completed
                onUpdate(completed)
            }
        } finally {
            // Only clean up a container this self-check started. A container
            // the user already had open is left exactly as it was.
            val slot = sessionSlot
            if (launchedHere && slot != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { ContainerProcessPool.closeSlot(context, slot) }
                }
            }
        }
        return ContainerSelfCheckReport(results, started, System.currentTimeMillis() - started)
    }

    private suspend fun runCheck(check: ContainerCheck): Outcome = when (check) {
        ContainerCheck.DEVICE -> checkDevice()
        ContainerCheck.SLOTS -> checkSlots()
        ContainerCheck.ENGINE -> checkEngine()
        ContainerCheck.LAUNCH -> checkLaunch()
        ContainerCheck.VIEW_TREE -> checkViewTree()
        ContainerCheck.SCREENSHOT -> checkScreenshot()
        ContainerCheck.PROCESS -> checkProcess()
        ContainerCheck.WEBVIEW -> checkWebView()
        ContainerCheck.MCP -> checkMcp()
    }

    private fun checkDevice(): Outcome {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val availableMb = memory.availMem / (1024L * 1024L)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val detail = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} · app $version · ${availableMb} MB free"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Outcome(ContainerCheckStatus.PASS, detail)
        } else {
            Outcome(
                ContainerCheckStatus.FAIL,
                detail,
                AppLanguage.tr("需要 Android 7.0 及以上", "Android 7.0 or newer is required")
            )
        }
    }

    private fun checkSlots(): Outcome {
        val mode = ContainerProcessPool.slotLimitMode(context)
        val limit = ContainerProcessPool.slotLimit(context)
        val running = ContainerProcessPool.runningSlots(context).size
        val modeText = if (mode == ContainerProcessPool.AUTO_SLOT_LIMIT) {
            AppLanguage.tr("自动", "automatic")
        } else {
            "$limit"
        }
        return Outcome(
            ContainerCheckStatus.PASS,
            AppLanguage.tr(
                "$modeText · 最多 $limit 个 · 正在运行 $running 个",
                "$modeText · up to $limit · $running running"
            )
        )
    }

    private suspend fun checkEngine(): Outcome {
        return when (val result = engine.listInstalled("virtual-1")) {
            is EngineResult.Error -> Outcome(
                ContainerCheckStatus.FAIL,
                "${result.code}: ${result.message}",
                AppLanguage.tr("先打开应用管理确认容器可用", "Open App management and confirm the container is available")
            )
            is EngineResult.Ok -> {
                apps = result.value
                if (apps.isEmpty()) {
                    Outcome(
                        ContainerCheckStatus.PARTIAL,
                        AppLanguage.tr("容器可用，但还没有安装虚拟应用", "Container works, but no virtual app is installed"),
                        AppLanguage.tr("安装一个 APK 后再运行完整检查", "Install an APK, then run the full check")
                    )
                } else {
                    Outcome(
                        ContainerCheckStatus.PASS,
                        AppLanguage.tr(
                            "已安装 ${apps.size} 个虚拟应用",
                            "${apps.size} virtual apps installed"
                        )
                    )
                }
            }
        }
    }

    private suspend fun checkLaunch(): Outcome {
        if (apps.isEmpty()) {
            return Outcome(
                ContainerCheckStatus.SKIPPED,
                AppLanguage.tr("没有可启动的虚拟应用", "No virtual app to launch")
            )
        }
        val app = apps.firstOrNull { it.packageName == "com.coke.testapp" } ?: apps.first()
        val existing = ContainerProcessPool.slotFor(app.packageName)
        launchedHere = existing == null
        val launch = engine.launch("virtual-1", app.packageName, null, headless = true)
        if (launch is EngineResult.Error) {
            return Outcome(
                ContainerCheckStatus.FAIL,
                "${launch.code}: ${launch.message}",
                AppLanguage.tr("查看应用管理中的崩溃原因，或重启成容器后重试", "Check App management for a crash reason, or restart the container")
            )
        }
        val info = (launch as EngineResult.Ok).value
        sessionPackage = app.packageName
        val slot = ContainerProcessPool.slotFor(app.packageName, info.instanceId)
        if (slot == null) {
            return Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr("启动后没有分配到容器槽位", "No container slot was assigned after launch")
            )
        }
        sessionSlot = slot
        val ready = awaitReachable(slot, 25_000)
        return if (ready) {
            Outcome(
                ContainerCheckStatus.PASS,
                AppLanguage.tr(
                    "${app.label} · ${info.instanceId} · 槽位 ${slot + 1}",
                    "${app.label} · ${info.instanceId} · slot ${slot + 1}"
                )
            )
        } else {
            Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr(
                    "容器进程没有在 25 秒内返回界面",
                    "The container did not return a screen within 25 seconds"
                ),
                AppLanguage.tr(
                    "如果这是大型 App，稍后重试；仍失败时查看 logcat 中的容器错误",
                    "Retry for large apps; if it keeps failing, check container errors in logcat"
                )
            )
        }
    }

    private suspend fun checkViewTree(): Outcome {
        val slot = sessionSlot
            ?: return Outcome(ContainerCheckStatus.SKIPPED, AppLanguage.tr("没有容器可观察", "No container to inspect"))
        val json = ContainerUiClient.observeJson(context, slot)
            ?: return Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr("容器没有返回界面树", "The container returned no view tree")
            )
        val nodes = runCatching { JSONObject(json).optJSONArray("nodes")?.length() ?: 0 }
            .getOrDefault(0)
        return if (nodes > 0) {
            Outcome(
                ContainerCheckStatus.PASS,
                AppLanguage.tr("读到 $nodes 个控件", "Read $nodes controls")
            )
        } else {
            Outcome(
                ContainerCheckStatus.PARTIAL,
                AppLanguage.tr("界面树为空（可能是自绘界面或仍在加载）", "Empty view tree (custom-drawn UI or still loading)"),
                AppLanguage.tr("等界面加载后重试；自绘界面可依赖截图", "Retry after loading; custom-drawn screens rely on screenshots")
            )
        }
    }

    private suspend fun checkScreenshot(): Outcome {
        val slot = sessionSlot
            ?: return Outcome(ContainerCheckStatus.SKIPPED, AppLanguage.tr("没有容器可截图", "No container to capture"))
        val base64 = ContainerUiClient.screenshotBase64(context, slot)
        return if (!base64.isNullOrBlank()) {
            Outcome(
                ContainerCheckStatus.PASS,
                AppLanguage.tr(
                    "截图 ${base64.length / 1024} KB",
                    "Screenshot ${base64.length / 1024} KB"
                )
            )
        } else {
            Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr("没有拿到截图", "No screenshot was produced")
            )
        }
    }

    private fun checkProcess(): Outcome {
        val slot = sessionSlot
            ?: return Outcome(ContainerCheckStatus.SKIPPED, AppLanguage.tr("没有容器进程可检查", "No container process to check"))
        val running = ContainerProcessPool.runningSlots(context)
            .firstOrNull { it.slot == slot }
            ?: return Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr("槽位 $slot 已不在运行列表中", "Slot $slot is not in the running list")
            )
        val alive = ContainerProcessPool.isProcessAlive(context, slot)
        return if (alive) {
            Outcome(
                ContainerCheckStatus.PASS,
                AppLanguage.tr(
                    "槽位 ${slot + 1} · ${running.processName} · ${running.memoryKb / 1024} MB PSS",
                    "Slot ${slot + 1} · ${running.processName} · ${running.memoryKb / 1024} MB PSS"
                )
            )
        } else {
            Outcome(
                ContainerCheckStatus.FAIL,
                AppLanguage.tr(
                    "槽位 ${slot + 1} 的进程已退出",
                    "The process in slot ${slot + 1} exited"
                ),
                AppLanguage.tr("重新启动这个应用或关闭后重试", "Relaunch the app or close it and retry")
            )
        }
    }

    private suspend fun checkWebView(): Outcome {
        val view = withContext(Dispatchers.Main) { WebView(context) }
        return try {
            withContext(Dispatchers.Main) {
                view.settings.javaScriptEnabled = true
                view.webViewClient = object : WebViewClient() {}
                view.loadDataWithBaseURL(
                    null,
                    "<html><body><h1>ok</h1></body></html>",
                    "text/html",
                    "utf-8",
                    null
                )
            }
            var text = ""
            for (attempt in 0 until 30) {
                delay(100)
                text = evaluate(view, "document.body ? document.body.innerText : ''")
                if (text.contains("ok")) break
            }
            if (text.contains("ok")) {
                Outcome(
                    ContainerCheckStatus.PASS,
                    AppLanguage.tr("本地页面渲染正常", "Local page rendered")
                )
            } else {
                Outcome(
                    ContainerCheckStatus.PARTIAL,
                    AppLanguage.tr("WebView 已创建，但本地页面没有读出内容", "WebView was created but the local page returned no text"),
                    AppLanguage.tr("如果浏览器打不开网页，检查系统 WebView 是否已启用", "If the browser fails, check that the system WebView is enabled")
                )
            }
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { view.destroy() }
            }
        }
    }

    private fun checkMcp(): Outcome {
        val status = McpForegroundService.status.value
        return if (status.running) {
            Outcome(
                ContainerCheckStatus.PASS,
                AppLanguage.tr("127.0.0.1:8765 · 令牌已设置", "127.0.0.1:8765 · token set")
            )
        } else {
            Outcome(
                ContainerCheckStatus.PARTIAL,
                AppLanguage.tr("MCP 服务未运行", "MCP server is not running"),
                AppLanguage.tr("在 MCP 控制服务 中开启 MCP", "Enable MCP in MCP control service")
            )
        }
    }

    private suspend fun awaitReachable(slot: Int, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (ContainerUiClient.observeJson(context, slot) != null) return true
            delay(300)
        }
        return false
    }

    private suspend fun evaluate(view: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value ?: "")
                }
            }
        }

    private fun result(
        check: ContainerCheck,
        outcome: Outcome,
        durationMs: Long = 0
    ): ContainerCheckResult = ContainerCheckResult(
        index = check.ordinal + 1,
        id = check.id,
        title = check.title(),
        status = outcome.status,
        detail = outcome.detail,
        advice = outcome.advice,
        durationMs = durationMs
    )
}

enum class ContainerCheck(val id: String, val timeoutMs: Long) {
    DEVICE("device", 5_000),
    SLOTS("slots", 5_000),
    ENGINE("engine", 10_000),
    LAUNCH("launch", 35_000),
    VIEW_TREE("view_tree", 10_000),
    SCREENSHOT("screenshot", 10_000),
    PROCESS("process", 5_000),
    WEBVIEW("webview", 15_000),
    MCP("mcp", 5_000);

    fun needs(): List<ContainerCheck> = when (this) {
        LAUNCH -> listOf(ENGINE)
        VIEW_TREE, SCREENSHOT, PROCESS -> listOf(ENGINE, LAUNCH)
        else -> emptyList()
    }

    fun title(): String = when (this) {
        DEVICE -> AppLanguage.tr("设备与应用", "Device & app")
        SLOTS -> AppLanguage.tr("容器槽位", "Container slots")
        ENGINE -> AppLanguage.tr("容器引擎", "Container engine")
        LAUNCH -> AppLanguage.tr("启动虚拟应用", "Launch virtual app")
        VIEW_TREE -> AppLanguage.tr("读取界面控件", "Read view tree")
        SCREENSHOT -> AppLanguage.tr("界面截图", "Screenshot")
        PROCESS -> AppLanguage.tr("独立进程", "Isolated process")
        WEBVIEW -> AppLanguage.tr("WebView 内核", "WebView engine")
        MCP -> AppLanguage.tr("MCP 服务", "MCP server")
    }
}
