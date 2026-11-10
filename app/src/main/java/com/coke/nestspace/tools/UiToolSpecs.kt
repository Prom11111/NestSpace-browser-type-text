package com.coke.nestspace.tools

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.control.ContainerPreviewCapture
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.control.ContainerUiKeepAlive
import org.json.JSONObject
import android.util.Base64

internal fun ToolRegistry.uiToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "ui_observe",
            description = "Observe a virtual app's view tree. Optionally pass package/instance_id " +
                "to target a specific container; otherwise the most recently launched one is used.",
            inputSchema = schema(
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer"
            )
        ) { args, ctx ->
            var slot = containerSlot(args)
            var observation = slot?.let { ContainerUiClient.observeJson(context, it) }
            if (observation == null) {
                val recovered = recoverHeadlessContainer(args, ctx, slot)
                if (recovered != null) {
                    slot = recovered
                    observation = ContainerUiClient.observeJson(context, slot)
                }
            }
            if (observation == null) {
                return@ToolSpec noContainerResult(slot, args)
            }
            slot?.let { ContainerUiKeepAlive.bind(context, it) }
            capturePreview(slot, args.optString("package"))
            ToolResult(true, UserGateManager.annotateSensitive(observation))
        },
        ToolSpec(
            name = "ui_tap",
            description = "Tap a node (node_id), a text match, or container coordinates (x, y). " +
                "Optionally pass package/instance_id to target a specific container. Taps on " +
                "payment, send, delete, install, or similar labels ask the user first.",
            inputSchema = schema(
                "node_id" to "string",
                "text" to "string",
                "x" to "integer",
                "y" to "integer",
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer"
            ),
            mutating = true
        ) { args, ctx ->
            val slot = containerSlot(args)
            if (missingRequestedContainer(args, slot)) {
                return@ToolSpec noContainerResult(null, args)
            }
            val risky = riskyTapLabel(args, slot)
            if (risky != null) {
                val category = when {
                    UserGateManager.isMoneyTap(risky) -> ToolConfirmationCategory.MONEY
                    UserGateManager.isInstallTap(risky) -> ToolConfirmationCategory.INSTALL
                    else -> ToolConfirmationCategory.RISKY
                }
                val denied = requireConfirmation(
                    ctx,
                    "tap \"$risky\"",
                    AppLanguage.tr(
                        "即将点击「$risky」。这可能发送内容、删除数据或产生支付。",
                        "Tap \"$risky\"? This may send content, delete data, or spend money."
                    ),
                    category
                )
                if (denied != null) return@ToolSpec denied
            }
            val result = tapInContainer(args, slot)
            if (result == null) {
                noContainerResult(slot, args)
            } else {
                capturePreview(slot, args.optString("package"))
                result
            }
        },
        ToolSpec(
            name = "ui_key",
            description = "Press a navigation key on a virtual app. Only 'back' is supported.",
            inputSchema = schema(
                "key" to "string",
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer"
            ),
            mutating = true
        ) { args, _ ->
            val key = args.optString("key", "back")
            if (key != "back") {
                return@ToolSpec ToolResult(
                    false, "only 'back' is supported", errorCode = "INVALID_ARGUMENT"
                )
            }
            val slot = containerSlot(args)
            if (missingRequestedContainer(args, slot)) {
                return@ToolSpec noContainerResult(null, args)
            }
            val ok = ContainerUiClient.pressBack(context, slot)
                ?: return@ToolSpec noContainerResult(slot, args)
            capturePreview(slot, args.optString("package"))
            ToolResult(
                ok,
                if (ok) "pressed back" else "back failed",
                errorCode = if (ok) null else "KEY_FAILED"
            )
        },
        ToolSpec(
            name = "ui_set_text",
            description = "Set text on an editable node. If node_id is omitted, uses the first editable node. " +
                "Optionally pass package/instance_id to target a specific container.",
            inputSchema = schema(
                "node_id" to "string",
                "text" to "string",
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer"
            ),
            mutating = true
        ) { args, _ ->
            val slot = containerSlot(args)
            if (missingRequestedContainer(args, slot)) {
                return@ToolSpec noContainerResult(null, args)
            }
            val ok = ContainerUiClient.setText(
                context,
                slot,
                args.optString("node_id"),
                args.optString("text")
            ) ?: return@ToolSpec noContainerResult(slot, args)
            capturePreview(slot, args.optString("package"))
            ToolResult(
                ok,
                if (ok) "text set" else "no editable node",
                errorCode = if (ok) null else "SET_TEXT_FAILED"
            )
        },
        ToolSpec(
            name = "hand_to_user",
            description = "Pause and hand the virtual app to the user for login, captcha, payment, " +
                "permission, or a decision only they can make. Asks the user to confirm, then " +
                "brings the app to the foreground and waits for the user to tap Done or Skip. " +
                "Call ui_observe afterwards to verify.",
            inputSchema = schema(
                "reason" to "string",
                "message" to "string",
                "package" to "string",
                "instance_id" to "string"
            )
        ) { args, _ ->
            val slot = containerSlot(args)
                ?: return@ToolSpec noContainerResult(null, args)
            val reason = args.optString("reason", "other").lowercase()
            val pkg = args.optString("package")
            val instanceId = args.optString("instance_id")
            val title = when (reason) {
                "login" -> AppLanguage.tr("需要你完成登录", "Login required")
                "captcha" -> AppLanguage.tr("需要你完成验证", "Verification required")
                "payment" -> AppLanguage.tr("需要你完成支付", "Payment required")
                "permission" -> AppLanguage.tr("需要你处理权限", "Permission required")
                else -> AppLanguage.tr("需要你操作", "Your action is needed")
            }
            val detail = args.optString("message").takeIf { it.isNotBlank() }
                ?: when (reason) {
                    "login" -> AppLanguage.tr(
                        "请在虚拟 App 中完成登录，然后点“完成”。",
                        "Complete the login in the virtual app, then tap Done."
                    )
                    "captcha" -> AppLanguage.tr(
                        "请在虚拟 App 中完成验证码或安全验证，然后点“完成”。",
                        "Complete the captcha or verification in the virtual app, then tap Done."
                    )
                    "payment" -> AppLanguage.tr(
                        "支付必须由你本人完成。完成后点“完成”，或点“跳过”。",
                        "Payment must be completed by you. Tap Done when finished, or Skip."
                    )
                    "permission" -> AppLanguage.tr(
                        "请处理虚拟 App 的权限或授权弹窗，然后点“完成”。",
                        "Handle the permission prompt in the virtual app, then tap Done."
                    )
                    else -> AppLanguage.tr(
                        "请在虚拟 App 中完成这一步，然后点“完成”。",
                        "Complete this step in the virtual app, then tap Done."
                    )
                }
            val result = UserGateManager.takeOver(
                context = context,
                title = title,
                detail = detail,
                packageName = pkg,
                instanceId = instanceId,
                slot = slot,
                reason = reason,
                bringToFront = {
                    // Prefer the in-process UI service: it moves the existing
                    // task to the front and keeps the target session/page.
                    ContainerUiClient.bringToFront(context, slot)
                        ?: ContainerProcessPool.bringToFront(context, slot, pkg, instanceId)
                }
            )
            when (result) {
                UserGateManager.TakeoverResult.COMPLETED -> ToolResult(
                    true,
                    JSONObject()
                        .put("takeover", "completed")
                        .put("reason", reason)
                        .put("package", pkg)
                        .put("hint", "Call ui_observe to verify the current screen before continuing.")
                        .toString()
                )
                UserGateManager.TakeoverResult.SKIPPED -> ToolResult(
                    false,
                    "The user skipped the takeover.",
                    errorCode = "TAKEOVER_SKIPPED"
                )
                UserGateManager.TakeoverResult.CANCELLED -> ToolResult(
                    false,
                    "The container is not reachable for takeover.",
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
            name = "ui_wait_for",
            description = "Compatibility shortcut for waiting until text appears in a virtual app. " +
                "New workflows should use poll_tool with ui_observe so UI waits share the same " +
                "check-count, interval and timeout semantics as other asynchronous work.",
            inputSchema = schema(
                "text" to "string",
                "timeout_ms" to "integer",
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer",
                required = listOf("text")
            )
        ) { args, _ ->
            val text = args.optString("text").trim()
            if (text.isEmpty()) return@ToolSpec ToolResult(
                false,
                "text is required",
                errorCode = "INVALID_ARGUMENT"
            )
            val timeout = args.optLong("timeout_ms", 5000L).coerceIn(500L, 60_000L)
            val slot = containerSlot(args)
            if (missingRequestedContainer(args, slot)) {
                return@ToolSpec noContainerResult(null, args)
            }
            val ok = ContainerUiClient.waitForText(context, slot, text, timeout)
                ?: return@ToolSpec noContainerResult(slot, args)
            capturePreview(slot, args.optString("package"))
            ToolResult(ok, if (ok) "found: $text" else "timeout: $text", errorCode = if (ok) null else "TIMEOUT")
        },
        ToolSpec(
            name = "ui_screenshot",
            description = "Screenshot a virtual app's view hierarchy. Optionally pass package/instance_id " +
                "to target a specific container.",
            inputSchema = schema(
                "package" to "string",
                "instance_id" to "string",
                "process" to "string",
                "slot" to "integer"
            )
        ) { args, _ ->
            val slot = containerSlot(args)
            if (missingRequestedContainer(args, slot)) {
                return@ToolSpec noContainerResult(null, args)
            }
            val actualSlot = slot ?: ContainerProcessPool.lastSlot() ?: -1
            val jpeg = ContainerPreviewCapture.capture(
                context = context,
                slot = actualSlot,
                label = args.optString("package"),
                rejectBlank = false
            )
                ?: return@ToolSpec noContainerResult(slot, args)
            ToolResult(
                true,
                "screenshot captured",
                imageBase64 = Base64.encodeToString(jpeg, Base64.NO_WRAP),
                imageMimeType = "image/jpeg"
            )
        },
    )
