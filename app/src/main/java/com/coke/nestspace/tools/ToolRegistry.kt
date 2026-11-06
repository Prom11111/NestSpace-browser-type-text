package com.coke.nestspace.tools

import com.coke.nestspace.i18n.AppLanguage
import android.content.Context
import android.util.Log
import com.coke.nestspace.agent.AssistantBridge
import com.coke.nestspace.agent.ToolConfirmationCategory
import com.coke.nestspace.agent.ToolPermissionSettings
import com.coke.nestspace.agent.UserGateManager
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.container.HeadlessMirrorWindow
import com.coke.nestspace.control.ContainerUiClient
import com.coke.nestspace.control.LivePreview
import com.coke.nestspace.data.AppRepository
import com.coke.nestspace.data.VirtualApp
import com.coke.nestspace.engine.ContainerEngine
import com.coke.nestspace.engine.EngineResult
import com.coke.nestspace.host.HostControlSettings
import com.coke.nestspace.host.RootShell
import com.coke.nestspace.host.RootStatus
import com.coke.nestspace.knowledge.KnowledgeStore
import com.coke.nestspace.memory.MemoryStore
import com.coke.nestspace.memory.SkillStore
import com.coke.nestspace.schedule.ScheduledTaskManager
import com.coke.nestspace.replay.ReplayStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the assistant/MCP tool set.
 *
 * Tool definitions live in the per-domain `*ToolSpecs.kt` files as extensions
 * of this class; the shared state and helpers below are `internal` so those
 * files can use them without duplicating context, lease or JSON plumbing.
 */
class ToolRegistry(
    internal val context: Context,
    internal val apps: AppRepository,
    internal val engine: ContainerEngine
) {
    internal val leases = LeaseManager()
    private val tools: List<ToolSpec> = buildTools()
    private val LEASE_RENEW_MS = 10_000L
    private val knowledge by lazy { KnowledgeStore.get(context) }
    internal val memory by lazy { MemoryStore(context) }
    internal val skills by lazy { SkillStore(context) }
    internal val schedules by lazy { ScheduledTaskManager(context) }
    internal val replays by lazy { ReplayStore(context) }
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Read-only tools available to a child agent; write/UI actions stay with the parent. */
    internal val subagentTools = setOf(
        "space_list",
        "space_list_apps",
        "space_list_installed",
        "ui_observe",
        "ui_wait_for",
        "ui_screenshot",
        "appfn_status",
        "appfn_list",
        "browser_open",
        "browser_navigate",
        "browser_status",
        "browser_tabs",
        "browser_back",
        "browser_forward",
        "browser_reload",
        "browser_links",
        "browser_read_text",
        "memory_search",
        "skill_list",
        "skill_read",
        "replay_list",
        "system_status"
    )

    fun list(): List<ToolSpec> {
        if (HostControlSettings.isEnabled(context)) return tools
        return tools.filterNot { it.name.startsWith("host_") }
    }

    /** Release leases owned by an MCP session that ended or expired. */
    fun releaseSession(sessionId: String) {
        leases.release(sessionId)
    }

    suspend fun execute(name: String, args: JSONObject, ctx: ToolContext): ToolResult {
        val spec = tools.firstOrNull { it.name == name }
            ?: return ToolResult(false, "Unknown tool: $name", errorCode = "UNKNOWN_TOOL")

        if (name.startsWith("host_") && !HostControlSettings.isEnabled(context)) {
            return ToolResult(
                false,
                "Host control (root) is disabled. Enable it in Space settings > Host control.",
                errorCode = "HOST_CONTROL_DISABLED"
            )
        }

        val spaceId = if (name.startsWith("host_")) {
            // Host tools are serialized per agent session so one CLI/MCP
            // connection cannot block another session for the lease TTL.
            "host:${ctx.sessionId}"
        } else {
            args.optString("space_id", ctx.spaceId)
        }
        if (spec.mutating && !leases.acquire(spaceId, ctx.sessionId)) {
            return ToolResult(
                false,
                "SPACE_BUSY: another agent is controlling $spaceId",
                errorCode = "SPACE_BUSY"
            )
        }

        if (name.startsWith("ui_")) ensureMirrorPreview(args)

        val result = try {
            if (spec.mutating) {
                coroutineScope {
                    val renew = launch(Dispatchers.Default) {
                        while (isActive) {
                            delay(LEASE_RENEW_MS)
                            leases.renew(spaceId, ctx.sessionId)
                        }
                    }
                    try {
                        spec.handler(args, ctx.copy(spaceId = spaceId))
                    } finally {
                        renew.cancel()
                    }
                }
            } else {
                spec.handler(args, ctx.copy(spaceId = spaceId))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ToolResult(
                false,
                "Tool $name failed: ${t.message ?: t.javaClass.simpleName}",
                errorCode = "TOOL_ERROR"
            )
        }
        return withKnowledge(name, args, result, ctx)
    }

    /**
     * Attach app/site guidance the first time this run reaches that target.
     * The guide lives in the tool result, so it is visible exactly when the
     * model is about to act instead of bloating the system prompt for every
     * turn.
     */
    private fun withKnowledge(
        name: String,
        args: JSONObject,
        result: ToolResult,
        ctx: ToolContext
    ): ToolResult {
        if (!result.ok) return result
        val entry = runCatching { knowledge.forTool(name, args, result.text) }.getOrNull()
            ?: return result
        if (!ctx.seenKnowledge.add(entry.key)) return result
        return result.copy(text = entry.withGuide(result.text))
    }

    private fun buildTools(): List<ToolSpec> =
        spaceToolSpecs() +
            uiToolSpecs() +
            hostToolSpecs() +
            appfnToolSpecs() +
            scheduleToolSpecs() +
            agentToolSpecs() +
            replayToolSpecs() +
            memoryToolSpecs() +
            sessionToolSpecs() +
            BrowserTools.create(context)


    internal fun virtualAppToJson(app: VirtualApp): JSONObject = JSONObject()
        .put("space_id", app.spaceId)
        .put("package", app.packageName)
        .put("label", app.label)
        .put("version_name", app.versionName ?: JSONObject.NULL)
        .put("version_code", app.versionCode)
        .put("launcher_activity", app.launcherActivity)
        .put("signature_sha256", app.signatureSha256)
        .put("native_libs", app.hasNativeLibs)
        .put("installed_at", app.installedAt)
        .put("updated_at", app.updatedAt)
        .put(
            "instances",
            JSONArray().apply {
                app.instances.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("data_dir", it.dataDir)
                            .put("created_at", it.createdAt)
                            .put("last_launched_at", it.lastLaunchedAt)
                    )
                }
            }
        )

    internal fun appsToJson(apps: List<com.coke.nestspace.data.AppInfo>): JSONObject {
        val arr = JSONArray()
        apps.forEach {
            arr.put(
                JSONObject()
                    .put("package", it.packageName)
                    .put("label", it.label)
                    .put("system", it.isSystem)
            )
        }
        return JSONObject().put("apps", arr)
    }

    internal fun containerSlot(args: JSONObject): Int? = ContainerProcessPool.slotFor(
        args.optString("package").takeIf { it.isNotBlank() },
        args.optString("instance_id").takeIf { it.isNotBlank() }
    )

    /**
     * The assistant closes the mirror window when a run ends, but the headless
     * target stays alive for follow-ups. Re-open the window when a later run
     * starts observing or operating that app; the container host follows the
     * new display id and attaches its existing target view to it.
     */
    internal suspend fun ensureMirrorPreview(args: JSONObject) {
        val slot = containerSlot(args) ?: return
        val activeMirrorSlot = HeadlessMirrorWindow.activeSlot()
        // Never take over a mirror that is currently showing another app.
        if (activeMirrorSlot >= 0 && activeMirrorSlot != slot) return
        if (ContainerUiClient.isMirrorMode(context, slot) != true) return
        val displayId = runCatching { HeadlessMirrorWindow.awaitDisplay(context) }
            .onFailure { Log.w("ToolRegistry", "mirror preview unavailable", it) }
            .getOrNull() ?: return
        if (HeadlessMirrorWindow.activeSlot() != slot) HeadlessMirrorWindow.setActiveSlot(slot)
        Log.d("ToolRegistry", "mirror preview on display $displayId for slot $slot")
    }

    /**
     * A package/instance was explicitly requested but has no running slot.
     * Do not let ContainerUiClient fall back to the last launched app.
     */
    internal fun missingRequestedContainer(args: JSONObject, slot: Int?): Boolean =
        slot == null && (
            args.optString("package").isNotBlank() ||
                args.optString("instance_id").isNotBlank()
            )

    /**
     * Refresh the assistant's live preview after a container action without
     * delaying the tool result. The UI polls the same slot while a run is
     * active to keep the frame moving between model steps.
     */
    internal fun capturePreview(slot: Int?, label: String?) {
        val actualSlot = slot ?: ContainerProcessPool.lastSlot() ?: return
        previewScope.launch {
            val base64 = ContainerUiClient.screenshotJpegBase64(context, actualSlot)
                ?: return@launch
            LivePreview.publish(
                base64 = base64,
                mimeType = "image/jpeg",
                slot = actualSlot,
                label = label.orEmpty()
            )
        }
    }

    internal suspend fun awaitContainerReady(slot: Int?, timeoutMs: Long = 20_000L): Boolean {
        if (slot == null) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ContainerUiClient.observeJson(context, slot) != null) return true
            delay(250)
        }
        return false
    }

    /**
     * If the local assistant asks to observe a headless app whose slot process
     * was reclaimed, relaunch the same package/instance headless instead of
     * requiring a manual space_launch. The visible UI is unaffected.
     */
    internal suspend fun recoverHeadlessContainer(
        args: JSONObject,
        ctx: ToolContext,
        slotHint: Int?
    ): Int? {
        if (ctx.caller != Caller.LOCAL_AGENT) return null
        val requestedPackage = args.optString("package").takeIf { it.isNotBlank() }
        val requestedInstance = args.optString("instance_id").takeIf { it.isNotBlank() }
        val owner = if (requestedPackage == null || requestedInstance == null) {
            slotHint?.let { ContainerProcessPool.ownerOfSlot(it) }
        } else {
            null
        }
        val packageName = requestedPackage ?: owner?.packageName ?: return null
        val instanceId = requestedInstance ?: owner?.instanceId ?: return null
        val spaceId = args.optString("space_id", ctx.spaceId)
        when (val result = engine.launch(
            spaceId = spaceId,
            packageName = packageName,
            instanceId = instanceId,
            headless = true
        )) {
            is EngineResult.Error -> return null
            is EngineResult.Ok -> Unit
        }
        val slot = ContainerProcessPool.slotFor(packageName, instanceId) ?: return null
        return slot.takeIf { awaitContainerReady(it, 20_000L) }
    }

    internal fun launchBlockedResult(): ToolResult = ToolResult(
        false,
        "Container launch was blocked or did not become reachable. If NestSpace is in the " +
            "background, grant overlay permission (Services & connections → Allow overlay).",
        errorCode = "LAUNCH_BLOCKED"
    )

    internal fun noContainerResult(slot: Int?, args: JSONObject): ToolResult {
        val packageName = args.optString("package").ifBlank { "*" }
        val instanceId = args.optString("instance_id").ifBlank { "*" }
        return ToolResult(
            false,
            if (slot == null) {
                "No running virtual app matches package=$packageName instance_id=$instanceId."
            } else {
                "Container process for package=$packageName instance_id=$instanceId is not reachable."
            },
            errorCode = "CONTAINER_NOT_RUNNING"
        )
    }

    internal suspend fun tapInContainer(args: JSONObject, slot: Int?): ToolResult? {
        val nodeId = args.optString("node_id")
        if (nodeId.isNotBlank()) {
            val ok = ContainerUiClient.tapNode(context, slot, nodeId) ?: return null
            return ToolResult(
                ok,
                if (ok) "tapped $nodeId" else "tap failed",
                errorCode = if (ok) null else "TAP_FAILED"
            )
        }
        val text = args.optString("text")
        if (text.isNotBlank()) {
            val ok = ContainerUiClient.tapText(context, slot, text) ?: return null
            return ToolResult(
                ok,
                if (ok) "tapped \"$text\"" else "node not found: $text",
                errorCode = if (ok) null else "NODE_NOT_FOUND"
            )
        }
        if (args.has("x") && args.has("y")) {
            val ok = ContainerUiClient.tapAt(context, slot, args.optInt("x"), args.optInt("y"))
                ?: return null
            return ToolResult(
                ok,
                if (ok) "tapped coordinates" else "tap failed",
                errorCode = if (ok) null else "TAP_FAILED"
            )
        }
        return ToolResult(false, "provide node_id, text, or x/y", errorCode = "INVALID_ARGUMENT")
    }

    internal fun assistantSnapshotJson(
        snapshot: AssistantBridge.Snapshot,
        fromIndex: Int = 0
    ): JSONObject {
        val messages = JSONArray()
        snapshot.messages.drop(fromIndex.coerceAtLeast(0)).takeLast(40).forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role.name)
                    .put("text", message.text.take(4_000))
            )
        }
        return JSONObject()
            .put("busy", snapshot.busy)
            .put("message_count", snapshot.messages.size)
            .put("messages", messages)
    }

    internal suspend fun hostGuard(): ToolResult? {
        if (!HostControlSettings.isEnabled(context)) {
            return ToolResult(
                false,
                "Host control (root) is disabled. Enable it in Space settings > Host control.",
                errorCode = "HOST_CONTROL_DISABLED"
            )
        }
        val status = RootShell.status()
        if (status != RootStatus.AVAILABLE) {
            return ToolResult(
                false,
                "Root access is not available ($status). Re-check Host control in settings.",
                errorCode = "HOST_ROOT_UNAVAILABLE"
            )
        }
        return null
    }

    internal suspend fun requireConfirmation(
        ctx: ToolContext,
        action: String,
        detail: String,
        category: ToolConfirmationCategory = ToolConfirmationCategory.RISKY
    ): ToolResult? {
        if (ctx.caller == Caller.USER) return null
        if (!ToolPermissionSettings.shouldConfirm(context, category)) return null
        val allowed = UserGateManager.askConfirmation(
            title = AppLanguage.tr("需要确认", "Confirmation needed"),
            detail = detail
        )
        return if (allowed) {
            null
        } else {
            ToolResult(
                false,
                AppLanguage.tr(
                    "用户拒绝了这次操作：$action",
                    "The user denied this action: $action"
                ),
                errorCode = "USER_DENIED"
            )
        }
    }

    internal suspend fun riskyTapLabel(args: JSONObject, slot: Int?): String? {
        val direct = args.optString("text").trim()
        if (direct.isNotBlank()) {
            return direct.takeIf(UserGateManager::isRiskyTap)
        }
        val nodeId = args.optString("node_id").trim()
        if (nodeId.isBlank()) return null
        val json = ContainerUiClient.observeJson(context, slot) ?: return null
        val nodes = runCatching { JSONObject(json).optJSONArray("nodes") }
            .getOrNull() ?: return null
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("id") != nodeId) continue
            val label = node.optString("text").ifBlank { node.optString("desc") }.trim()
            return label.takeIf(UserGateManager::isRiskyTap)
        }
        return null
    }

    internal fun replayJson(
        item: com.coke.nestspace.replay.ReplayItem,
        includeSteps: Boolean
    ): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("description", item.description)
        .put("goal", item.goal)
        .put("steps", item.steps.size)
        .put("created_at", item.createdAt)
        .apply {
            if (includeSteps) {
                put("step_list", JSONArray().apply {
                    item.steps.forEach { step ->
                        put(JSONObject().put("name", step.name).put("args", step.argsJson))
                    }
                })
            }
        }

    internal fun scheduleJson(task: com.coke.nestspace.schedule.ScheduledTask): JSONObject =
        JSONObject()
            .put("id", task.id)
            .put("goal", task.goal)
            .put("repeat", task.repeat.api)
            .put("next_at", task.nextAt)
            .put("enabled", task.enabled)
            .put("hour", task.hour)
            .put("minute", task.minute)
            .put("weekdays", JSONArray(task.weekdays))
            .put("interval_minutes", task.intervalMinutes)
            .put("last_run_at", task.lastRunAt)
            .put("last_status", task.lastStatus)
            .put("last_answer", task.lastAnswer)

    internal fun memoryJson(item: com.coke.nestspace.memory.MemoryItem): JSONObject =
        JSONObject()
            .put("id", item.id)
            .put("kind", item.kind.api)
            .put("content", item.content)
            .put("keywords", JSONArray(item.keywords))
            .put("source", item.source)
            .put("approved", item.approved)
            .put("pinned", item.pinned)
            .put("updated_at", item.updatedAt)

    internal fun schema(
        vararg props: Pair<String, String>,
        required: List<String> = emptyList()
    ): JSONObject {
        val properties = JSONObject()
        props.forEach { (name, type) ->
            properties.put(name, JSONObject().put("type", type))
        }
        val req = JSONArray()
        required.forEach { req.put(it) }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", req)
            .put("additionalProperties", false)
    }
}
