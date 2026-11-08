package com.coke.nestspace.tools

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.container.vpm.ContainerPermissionStore
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.control.ContainerUiKeepAlive
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.engine.EngineResult
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.spaceToolSpecs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = "space_list",
            description = "List spaces. Stage B exposes the system space and virtual space 1.",
            inputSchema = schema()
        ) { _, _ ->
            val arr = JSONArray()
            arr.put(
                JSONObject()
                    .put("id", "system")
                    .put("name", AppLanguage.tr("系统空间", "System space"))
                    .put("type", "system")
                    .put("status", AppLanguage.tr("可用", "Available"))
            )
            arr.put(
                JSONObject()
                    .put("id", "virtual-1")
                    .put("name", AppLanguage.tr("虚拟空间 1", "Virtual space 1"))
                    .put("type", "virtual")
                    .put(
                        "status",
                        AppLanguage.tr(
                            "Stage C-D：安装器 + 多开 + 进程隔离 / Job / 通知 / VPMS",
                            "Stage C-D: installer + multiple instances + process " +
                                "isolation / jobs / notifications / VPMS"
                        )
                    )
            )
            ToolResult(true, JSONObject().put("spaces", arr).toString())
        },
        ToolSpec(
            name = "space_list_apps",
            description = "List launchable apps in virtual-1 by default. Use space_id=system for installed system apps.",
            inputSchema = schema(
                "space_id" to "string"
            )
        ) { args, ctx ->
            val spaceId = args.optString("space_id", ctx.spaceId)
            if (spaceId != "system") {
                when (val result = engine.listApps(spaceId)) {
                    is EngineResult.Error -> return@ToolSpec ToolResult(
                        false, "${result.code}: ${result.message}", errorCode = result.code
                    )
                    is EngineResult.Ok -> return@ToolSpec ToolResult(
                        true, appsToJson(result.value).toString()
                    )
                }
            }
            ToolResult(true, appsToJson(apps.listLaunchableApps()).toString())
        },
        ToolSpec(
            name = "space_list_installed",
            description = "List apps installed in a virtual space, including instances, signature and native-lib status.",
            inputSchema = schema("space_id" to "string")
        ) { args, _ ->
            val spaceId = args.optString("space_id", "virtual-1")
            when (val result = engine.listInstalled(spaceId)) {
                is EngineResult.Error -> ToolResult(
                    false, "${result.code}: ${result.message}", errorCode = result.code
                )
                is EngineResult.Ok -> {
                    val arr = JSONArray()
                    result.value.forEach { arr.put(virtualAppToJson(it)) }
                    ToolResult(
                        true,
                        JSONObject().put("space_id", spaceId).put("apps", arr).toString()
                    )
                }
            }
        },
        ToolSpec(
            name = "space_running",
            description = "List running virtual apps and their slot processes. Check this (or " +
                "call ui_observe directly) before space_launch; if the target package/instance " +
                "is already running headless and the user wants to see or operate it, call " +
                "space_launch with headless=false to show it full-screen before using ui_*. " +
                "For an information-only task, continue with ui_* without reopening it.",
            inputSchema = schema()
        ) { _, _ ->
            val arr = JSONArray()
            ContainerProcessPool.runningSlots(context).forEach { slot ->
                arr.put(
                    JSONObject()
                        .put("slot", slot.slot)
                        .put("package", slot.packageName)
                        .put("instance_id", slot.instanceId)
                        .put("process", slot.processName)
                        .put("main", slot.processName == slot.packageName)
                        .put("active", slot.active)
                        .put("memory_kb", slot.memoryKb)
                )
            }
            ToolResult(true, JSONObject().put("running", arr).toString())
        },
        ToolSpec(
            name = "space_launch",
            description = "Launch an app by package name in virtual-1 by default (pass space_id=system " +
                "for a system app). instance_id is optional; omit it to use the most recently " +
                "launched instance. For a direct request to open an app, or to open it and " +
                "perform an action such as searching, tapping, following or posting, pass " +
                "headless=false and operate it full-screen. For information-only work such as " +
                "reading, checking, collecting or summarizing content, omit headless (or pass " +
                "true) to use the assistant's headless square mirror. If a sentence starts with " +
                "'open the app' but asks to send titles, results, or a summary, the information " +
                "goal wins and the whole sentence is one headless task. Do not inspect or " +
                "summarize after a plain open request.",
            inputSchema = schema(
                "space_id" to "string",
                "package" to "string",
                "instance_id" to "string",
                "headless" to "boolean"
            ),
            mutating = true
        ) { args, ctx ->
            val spaceId = args.optString("space_id", ctx.spaceId)
            val pkg = args.optString("package")
            if (pkg.isBlank()) return@ToolSpec ToolResult(
                false, "package is required", errorCode = "INVALID_ARGUMENT"
            )
            if (spaceId != "system") {
                val instanceId = args.optString("instance_id").takeIf { it.isNotBlank() }
                // Direct open/action requests use a normal window. Information
                // gathering defaults to the assistant's square headless mirror.
                val explicitVisible = args.has("headless") &&
                    !args.optBoolean("headless", true)
                val headless = !explicitVisible
                // Headless reads use the UI service directly; no mirror or
                // screenshot card needs to be shown on the assistant page.
                LivePreview.setSuppressed(headless)
                val running = ContainerProcessPool.runningSlots(context)
                    .lastOrNull {
                        it.packageName == pkg &&
                            it.processName == pkg &&
                            (instanceId == null || it.instanceId == instanceId)
                    }
                if (running != null) {
                    if (explicitVisible) {
                        val mirrored = ContainerUiClient.isMirrorMode(context, running.slot)
                            ?: (HeadlessMirrorWindow.activeSlot() == running.slot)
                        if (mirrored) {
                            // A mirror host renders into the VirtualDisplay and
                            // is not touchable. It cannot be promoted to a
                            // normal window, so restart it full-screen.
                            ContainerProcessPool.closeSlot(context, running.slot)
                            HeadlessMirrorWindow.hide()
                        } else {
                            val shown = ContainerProcessPool.bringToFront(
                                context,
                                running.slot,
                                pkg,
                                running.instanceId
                            )
                            if (shown) {
                                return@ToolSpec ToolResult(
                                    true,
                                    JSONObject()
                                        .put("launched", true)
                                        .put("already_running", true)
                                        .put("package", pkg)
                                        .put("instance_id", running.instanceId)
                                        .put("slot", running.slot)
                                        .put("headless", false)
                                        .toString()
                                )
                            }
                        }
                    } else {
                        val mirrored = ContainerUiClient.isMirrorMode(context, running.slot)
                            ?: (HeadlessMirrorWindow.activeSlot() == running.slot)
                        if (!mirrored) {
                            // A visible app cannot be reused as a headless task:
                            // it would stay in the foreground or enter PiP.
                            // Restart that slot inside the assistant mirror.
                            android.util.Log.d(
                                "SpaceToolSpecs",
                                "demoting visible ${running.packageName} slot=${running.slot} to headless"
                            )
                            ContainerProcessPool.closeSlot(context, running.slot)
                            HeadlessMirrorWindow.hide()
                        } else {
                        if (!awaitContainerReady(running.slot)) {
                            return@ToolSpec launchBlockedResult()
                        }
                        ContainerUiKeepAlive.bind(context, running.slot)
                        ensureMirrorPreview(args)
                        capturePreview(running.slot, pkg)
                        return@ToolSpec ToolResult(
                            true,
                            JSONObject()
                                .put("launched", true)
                                .put("already_running", true)
                                .put("package", pkg)
                                .put("instance_id", running.instanceId)
                                .put("slot", running.slot)
                                .put("headless", true)
                                .put("hint", "The app is already running; use ui_* directly.")
                                .toString()
                        )
                        }
                    }
                }
                when (val result = engine.launch(spaceId, pkg, instanceId, headless)) {
                    is EngineResult.Error -> return@ToolSpec ToolResult(
                        false, "${result.code}: ${result.message}", errorCode = result.code
                    )
                    is EngineResult.Ok -> {
                        val launched = result.value
                        val slot = ContainerProcessPool.slotFor(pkg, launched.instanceId)
                        if (!awaitContainerReady(slot)) {
                            return@ToolSpec launchBlockedResult()
                        }
                        if (headless) slot?.let { ContainerUiKeepAlive.bind(context, it) }
                        capturePreview(slot, pkg)
                        return@ToolSpec ToolResult(
                            true,
                            JSONObject()
                                .put("launched", true)
                                .put("already_running", false)
                                .put("package", pkg)
                                .put("instance_id", launched.instanceId)
                                .put("activity", launched.activity)
                                .put("data_dir", launched.dataDir)
                                .put("headless", headless)
                                .put("restarted_after_crash", launched.restartedAfterCrash)
                                .put(
                                    "crash_reason",
                                    launched.crashReason ?: JSONObject.NULL
                                )
                                .toString()
                        )
                    }
                }
            }
            if (isHostBrowserPackage(pkg)) {
                return@ToolSpec ToolResult(
                    false,
                    "Use browser_open for web pages; launching the system browser is disabled.",
                    errorCode = "USE_BUILT_IN_BROWSER"
                )
            }
            if (!apps.launch(pkg)) {
                return@ToolSpec ToolResult(false, "Cannot launch $pkg", errorCode = "LAUNCH_FAILED")
            }
            ToolResult(true, JSONObject().put("launched", true).put("package", pkg).toString())
        },
        ToolSpec(
            name = "space_multi_open",
            description = "Create a new instance in a virtual space and launch it. Each instance has an isolated data directory. " +
                "Pass headless=false when the user asks to open or operate the app; omit it " +
                "for information-only work so it runs in the headless square mirror.",
            inputSchema = schema(
                "space_id" to "string",
                "package" to "string",
                "headless" to "boolean"
            ),
            mutating = true
        ) { args, ctx ->
            val spaceId = args.optString("space_id", "virtual-1")
            val pkg = args.optString("package")
            if (pkg.isBlank()) return@ToolSpec ToolResult(
                false, "package is required", errorCode = "INVALID_ARGUMENT"
            )
            val headless = args.optBoolean("headless", true)
            LivePreview.setSuppressed(headless)
            when (val result = engine.multiOpen(spaceId, pkg, headless)) {
                is EngineResult.Error -> ToolResult(
                    false, "${result.code}: ${result.message}", errorCode = result.code
                )
                is EngineResult.Ok -> {
                    val opened = result.value
                    val slot = ContainerProcessPool.slotFor(pkg, opened.instanceId)
                    if (!awaitContainerReady(slot)) {
                        return@ToolSpec launchBlockedResult()
                    }
                    if (headless) slot?.let { ContainerUiKeepAlive.bind(context, it) }
                    capturePreview(slot, pkg)
                    ToolResult(
                        true,
                        JSONObject()
                            .put("multi_opened", true)
                            .put("package", pkg)
                            .put("instance_id", opened.instanceId)
                            .put("data_dir", opened.dataDir)
                            .put("headless", headless)
                            .put("restarted_after_crash", opened.restartedAfterCrash)
                            .put(
                                "crash_reason",
                                opened.crashReason ?: JSONObject.NULL
                            )
                            .toString()
                    )
                }
            }
        },
        ToolSpec(
            name = "space_install_apk",
            description = "Install a base APK plus optional split APKs into a virtual space. The URI can be a file path or content URI readable by NestSpace. Signature changes are rejected.",
            inputSchema = schema(
                "space_id" to "string",
                "apk_uri" to "string"
            ).apply {
                getJSONObject("properties").put(
                    "split_uris",
                    JSONObject()
                        .put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                )
            },
            mutating = true
        ) { args, ctx ->
            val spaceId = args.optString("space_id", "virtual-1")
            val base = args.optString("apk_uri")
            if (base.isBlank()) return@ToolSpec ToolResult(
                false, "apk_uri is required", errorCode = "INVALID_ARGUMENT"
            )
            val uris = mutableListOf(base)
            val splitUris = args.optJSONArray("split_uris")
            if (splitUris != null) {
                for (i in 0 until splitUris.length()) {
                    splitUris.optString(i).takeIf { it.isNotBlank() }?.let(uris::add)
                }
            }
            val denied = requireConfirmation(
                ctx,
                "install APK",
                AppLanguage.tr(
                    "安装 APK 到虚拟空间？\n$base",
                    "Install this APK into the virtual space?\n$base"
                ),
                ToolConfirmationCategory.INSTALL
            )
            if (denied != null) return@ToolSpec denied
            when (val result = engine.install(spaceId, uris)) {
                is EngineResult.Error -> ToolResult(
                    false, "${result.code}: ${result.message}", errorCode = result.code
                )
                is EngineResult.Ok -> ToolResult(
                    true,
                    JSONObject()
                        .put("installed", true)
                        .put("package", result.value.packageName)
                        .put("label", result.value.label)
                        .put("version_name", result.value.versionName ?: JSONObject.NULL)
                        .put("version_code", result.value.versionCode)
                        .put("native_libs", result.value.hasNativeLibs)
                        .put("signature_sha256", result.value.signatureSha256)
                        .put("instances", JSONArray().apply {
                            result.value.instances.forEach { put(it.id) }
                        })
                        .toString()
                )
            }
        },
        ToolSpec(
            name = "space_uninstall",
            description = "Uninstall a virtual app and delete its APKs, instances and data directories.",
            inputSchema = schema("space_id" to "string", "package" to "string"),
            mutating = true
        ) { args, ctx ->
            val pkg = args.optString("package")
            if (pkg.isBlank()) return@ToolSpec ToolResult(
                false, "package is required", errorCode = "INVALID_ARGUMENT"
            )
            val denied = requireConfirmation(
                ctx,
                "uninstall $pkg",
                AppLanguage.tr(
                    "卸载 $pkg？这会删除它的 APK、实例和数据目录。",
                    "Uninstall $pkg? This deletes its APKs, instances, and data."
                ),
                ToolConfirmationCategory.INSTALL
            )
            if (denied != null) return@ToolSpec denied
            when (val result = engine.uninstall(args.optString("space_id", "virtual-1"), pkg)) {
                is EngineResult.Error -> ToolResult(
                    false, "${result.code}: ${result.message}", errorCode = result.code
                )
                is EngineResult.Ok -> ToolResult(
                    true,
                    JSONObject().put("uninstalled", true).put("package", pkg).toString()
                )
            }
        },
        ToolSpec(
            name = "space_stop",
            description = "Stop showing the current virtual app (closes the container UI if it matches).",
            inputSchema = schema("space_id" to "string", "package" to "string"),
            mutating = true
        ) { args, _ ->
            when (val result = engine.stop(args.optString("space_id", "virtual-1"), args.optString("package"))) {
                is EngineResult.Error -> ToolResult(
                    false, "${result.code}: ${result.message}", errorCode = result.code
                )
                is EngineResult.Ok -> {
                    LivePreview.setSuppressed(false)
                    HeadlessMirrorWindow.hide()
                    ToolResult(true, "stopped")
                }
            }
        },
        ToolSpec(
            name = "space_permissions",
            description = "List, grant or revoke virtual runtime permissions for a package installed " +
                "in a space. The grant is app-facing: checkSelfPermission and cross-virtual-app " +
                "permission queries in the container see it immediately (within 1s).",
            inputSchema = schema(
                "space_id" to "string",
                "package" to "string",
                "instance_id" to "string",
                "permission" to "string",
                "action" to "string"
            ),
            mutating = true
        ) { args, ctx ->
            val pkg = args.optString("package")
            if (pkg.isBlank()) return@ToolSpec ToolResult(
                false, "package is required", errorCode = "INVALID_ARGUMENT"
            )
            val action = args.optString("action", "list").lowercase()
            val instanceId = args.optString("instance_id", "instance-1")
                .ifBlank { "instance-1" }
            val permission = args.optString("permission")
            when (action) {
                "grant", "revoke" -> {
                    if (permission.isBlank()) return@ToolSpec ToolResult(
                        false, "permission is required for $action",
                        errorCode = "INVALID_ARGUMENT"
                    )
                    val denied = requireConfirmation(
                        ctx,
                        "$action $permission",
                        AppLanguage.tr(
                            "为 $pkg ${if (action == "grant") "授予" else "撤销"}权限 $permission？",
                            "${if (action == "grant") "Grant" else "Revoke"} $permission for $pkg?"
                        ),
                        ToolConfirmationCategory.PERMISSION
                    )
                    if (denied != null) return@ToolSpec denied
                    val state = if (action == "grant") {
                        ContainerPermissionStore.grant(context, pkg, permission, instanceId)
                    } else {
                        ContainerPermissionStore.revoke(context, pkg, permission, instanceId)
                    }
                    ToolResult(
                        true,
                        JSONObject()
                            .put("package", pkg)
                            .put("instance_id", instanceId)
                            .put("action", action)
                            .put("permission", permission)
                            .put("granted", JSONArray(state.granted.toList()))
                            .put("denied", JSONArray(state.denied.toList()))
                            .toString()
                    )
                }
                else -> {
                    val state = ContainerPermissionStore.load(context, pkg, instanceId)
                    ToolResult(
                        true,
                        JSONObject()
                            .put("package", pkg)
                            .put("instance_id", instanceId)
                            .put("granted", JSONArray(state.granted.toList()))
                            .put("denied", JSONArray(state.denied.toList()))
                            .toString()
                    )
                }
            }
        },
    )
