package com.coke.nestspace.tools

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.host.HostUiController
import kotlinx.coroutines.launch

internal fun ToolRegistry.hostToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "host_observe",
            description = "Observe the real Android device through root. Returns the foreground " +
                "app, screen size and text/clickable nodes. Requires Host control (root) in " +
                "settings. Call this before host_tap when using node_id or text.",
            inputSchema = schema()
        ) { _, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val json = HostUiController.observe()
                ?: return@ToolSpec ToolResult(
                    false,
                    "Could not read the host UI. Check root access and try again.",
                    errorCode = "HOST_OBSERVE_FAILED"
                )
            ToolResult(true, UserGateManager.annotateSensitive(json))
        },
        ToolSpec(
            name = "host_tap",
            description = "Tap a real-device node (node_id), a text match, or absolute screen " +
                "coordinates (x, y). Requires Host control (root). Call host_observe first when " +
                "using node_id or text. Payment, send, delete and install labels ask first.",
            inputSchema = schema(
                "node_id" to "string",
                "text" to "string",
                "x" to "integer",
                "y" to "integer"
            ),
            mutating = true
        ) { args, ctx ->
            hostGuard()?.let { return@ToolSpec it }
            val risky = HostUiController.labelFor(
                args.optString("node_id"),
                args.optString("text")
            )
            if (risky != null) {
                val category = when {
                    UserGateManager.isMoneyTap(risky) -> ToolConfirmationCategory.MONEY
                    UserGateManager.isInstallTap(risky) -> ToolConfirmationCategory.INSTALL
                    else -> ToolConfirmationCategory.RISKY
                }
                val denied = requireConfirmation(
                    ctx,
                    "tap \"$risky\" on the real device",
                    AppLanguage.tr(
                        "即将在真实设备上点击「$risky」。这可能发送内容、删除数据或产生支付。",
                        "Tap \"$risky\" on the real device? This may send content, delete data, " +
                            "or spend money."
                    ),
                    category
                )
                if (denied != null) return@ToolSpec denied
            }
            val hasCoordinates = args.has("x") && args.has("y")
            val x = if (hasCoordinates) args.optInt("x") else null
            val y = if (hasCoordinates) args.optInt("y") else null
            val ok = HostUiController.tap(
                args.optString("node_id"),
                args.optString("text"),
                x,
                y
            )
            ToolResult(
                ok,
                if (ok) "tapped the real device" else "host tap failed; run host_observe first",
                errorCode = if (ok) null else "HOST_TAP_FAILED"
            )
        },
        ToolSpec(
            name = "host_set_text",
            description = "Type text into the focused field on the real device. Requires Host " +
                "control (root). Android's input command supports ASCII reliably; non-ASCII " +
                "text may not be entered.",
            inputSchema = schema(
                "text" to "string"
            ),
            mutating = true
        ) { args, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val result = HostUiController.setText(args.optString("text"))
            ToolResult(
                result.ok,
                if (result.ok) {
                    "text sent to the real device"
                } else {
                    result.output.ifBlank { result.error ?: "host text input failed" }.take(500)
                },
                errorCode = if (result.ok) null else "HOST_TEXT_FAILED"
            )
        },
        ToolSpec(
            name = "host_key",
            description = "Press a navigation key on the real device: back, home, enter, " +
                "recents, delete, tab or escape. Requires Host control (root).",
            inputSchema = schema(
                "key" to "string"
            ),
            mutating = true
        ) { args, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val result = HostUiController.pressKey(args.optString("key", "back"))
            ToolResult(
                result.ok,
                if (result.ok) "key sent to the real device" else
                    (result.error ?: result.output.take(500)),
                errorCode = if (result.ok) null else "HOST_KEY_FAILED"
            )
        },
        ToolSpec(
            name = "host_screenshot",
            description = "Take a screenshot of the real device through root. Requires Host " +
                "control (root).",
            inputSchema = schema()
        ) { _, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val base64 = HostUiController.screenshotBase64()
                ?: return@ToolSpec ToolResult(
                    false,
                    "Host screenshot failed. Check root access and try again.",
                    errorCode = "HOST_SCREENSHOT_FAILED"
                )
            ToolResult(
                true,
                "host screenshot captured",
                imageBase64 = base64,
                imageMimeType = "image/png"
            )
        },
        ToolSpec(
            name = "host_launch",
            description = "Launch an installed app on the real device by package name. " +
                "Requires Host control (root). Browser packages must be opened with " +
                "browser_open instead.",
            inputSchema = schema(
                "package" to "string"
            ),
            mutating = true
        ) { args, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val packageName = args.optString("package").trim()
            if (isHostBrowserPackage(packageName)) {
                return@ToolSpec ToolResult(
                    false,
                    "Use browser_open for web pages; launching the system browser is disabled.",
                    errorCode = "USE_BUILT_IN_BROWSER"
                )
            }
            val result = HostUiController.launch(packageName)
            ToolResult(
                result.ok,
                if (result.ok) "launched $packageName on the real device" else
                    (result.error ?: result.output.ifBlank { "launch failed" }).take(500),
                errorCode = if (result.ok) null else "HOST_LAUNCH_FAILED"
            )
        },
        ToolSpec(
            name = "host_apps",
            description = "List installed packages on the real device. Requires Host control " +
                "(root). Set include_system=true to include system apps.",
            inputSchema = schema(
                "include_system" to "boolean"
            )
        ) { args, _ ->
            hostGuard()?.let { return@ToolSpec it }
            val json = HostUiController.listPackages(args.optBoolean("include_system", false))
                ?: return@ToolSpec ToolResult(
                    false,
                    "Could not list host packages. Check root access.",
                    errorCode = "HOST_APPS_FAILED"
                )
            ToolResult(true, json)
        },
    )

internal fun ToolRegistry.isHostBrowserPackage(packageName: String): Boolean {
    if (packageName.isBlank()) return false
    if (packageName in HOST_BROWSER_PACKAGES) return true
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://example.com")
    ).addCategory(Intent.CATEGORY_BROWSABLE)
    val resolved = context.packageManager.resolveActivity(
        intent,
        PackageManager.MATCH_DEFAULT_ONLY
    )?.activityInfo?.packageName
    return resolved == packageName
}

private val HOST_BROWSER_PACKAGES = setOf(
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "com.android.browser",
    "com.sec.android.app.sbrowser",
    "org.mozilla.firefox",
    "com.brave.browser",
    "com.microsoft.emmx",
    "com.opera.browser",
    "com.UCMobile",
    "com.quark.browser"
)
