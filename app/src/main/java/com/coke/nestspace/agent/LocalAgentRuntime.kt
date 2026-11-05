package com.coke.nestspace.agent

import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.data.AgentMessage
import com.coke.nestspace.tools.Caller
import com.coke.nestspace.tools.ToolContext
import com.coke.nestspace.tools.ToolRegistry
import com.coke.nestspace.tools.ToolResult
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Basic on-device agent: a deterministic command interpreter.
 * It uses the same ToolRegistry as the remote MCP agent, so both channels
 * share tool behavior, leases and error codes. A Claude/Gemini ModelAdapter
 * can replace this class later without touching the tools.
 */
class LocalAgentRuntime(
    private val registry: ToolRegistry
) {

    suspend fun run(command: String, sessionId: String): List<AgentMessage> {
        val ctx = ToolContext(sessionId, Caller.LOCAL_AGENT, "system")
        val out = mutableListOf<AgentMessage>()
        out.add(AgentMessage(AgentMessage.Role.USER, command))

        try {
            when {
                command.startsWith("打开") || command.startsWith("launch", true) -> launch(command, ctx, out)
                command.startsWith("点击") || command.startsWith("tap", true) -> tap(command, ctx, out)
                command.startsWith("输入") || command.startsWith("type", true) -> type(command, ctx, out)
                command.startsWith("等待") || command.startsWith("wait", true) -> waitFor(command, ctx, out)
                command.startsWith("观察") || command.startsWith("observe", true) -> observe(ctx, out)
                command.startsWith("截图") || command.startsWith("screenshot", true) -> screenshot(ctx, out)
                command.contains("返回") || command.equals("back", true) -> key("back", ctx, out)
                command.contains("主页") || command.equals("home", true) -> key("home", ctx, out)
                command.contains("最近") || command.equals("recents", true) -> key("recents", ctx, out)
                else -> {
                    out.add(
                        AgentMessage(
                            AgentMessage.Role.AGENT,
                            AppLanguage.tr(
                                "基础版规则 Agent 支持：\n" +
                                    "打开 <应用名>、点击 <文本>、输入 <文本>、等待 <文本>、观察、截图、返回、主页、最近。\n" +
                                    "接入 Claude/Gemini 后可以理解更复杂的任务。",
                                "The basic rule agent supports:\n" +
                                    "open <app>, tap <text>, type <text>, wait <text>, " +
                                    "observe, screenshot, back, home, recents.\n" +
                                    "Connect Claude/Gemini for more complex tasks."
                            )
                        )
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            out.add(AgentMessage(AgentMessage.Role.ERROR, t.message ?: t.javaClass.simpleName))
        } finally {
            registry.execute("session_release", JSONObject(), ctx)
        }

        return out
    }

    private suspend fun launch(command: String, ctx: ToolContext, out: MutableList<AgentMessage>) {
        val query = command.removePrefix("打开").removePrefix("launch").trim()
        if (query.isBlank()) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "要打开哪个应用？例如：打开 设置",
                    "Which app should I open? For example: open Settings"
                )))
            return
        }

        val list = call("space_list_apps", JSONObject().put("space_id", "system"), ctx, out)
        var apps = if (list.ok) parseApps(list.text) else emptyList()
        var match = apps.firstOrNull {
            it.first.contains(query, ignoreCase = true) ||
                it.second.contains(query, ignoreCase = true)
        }
        var targetSpace = "system"

        // Rule mode also serves the virtual space: if the name is not a
        // system app, look through installed virtual apps before giving up.
        if (match == null && aliasPackage(query) == null) {
            val virtual = call(
                "space_list_installed",
                JSONObject().put("space_id", "virtual-1"),
                ctx,
                out
            )
            if (virtual.ok) {
                val virtualApps = parseApps(virtual.text)
                val virtualMatch = virtualApps.firstOrNull {
                    it.first.contains(query, ignoreCase = true) ||
                        it.second.contains(query, ignoreCase = true)
                }
                if (virtualMatch != null) {
                    match = virtualMatch
                    targetSpace = "virtual-1"
                }
                if (apps.isEmpty()) apps = virtualApps
            }
        }

        val resolvedPackage = match?.second ?: aliasPackage(query)
        if (resolvedPackage == null) {
            if (apps.isEmpty() && !list.ok) {
                out.add(AgentMessage(AgentMessage.Role.ERROR, AppLanguage.tr(
                        "读取应用列表失败：${list.text}",
                        "Failed to load apps: ${list.text}"
                    )))
                return
            }
            val hints = apps.take(8).joinToString("、") { it.first }
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "没找到「$query」。可用应用示例：$hints",
                    "Could not find \"$query\". Available apps include: $hints"
                )))
            return
        }

        val result = call(
            "space_launch",
            JSONObject().put("space_id", targetSpace).put("package", resolvedPackage),
            ctx,
            out
        )
        if (result.ok) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "已打开 ${match?.first ?: query}",
                    "Opened ${match?.first ?: query}"
                )))
        }
    }

    private suspend fun tap(command: String, ctx: ToolContext, out: MutableList<AgentMessage>) {
        val query = command.removePrefix("点击").removePrefix("tap").trim()
        if (query.isBlank()) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "要点击什么？例如：点击 搜索",
                    "What should I tap? For example: tap Search"
                )))
            return
        }

        val snapshot = call("ui_observe", JSONObject(), ctx, out)
        if (!snapshot.ok) {
            out.add(AgentMessage(AgentMessage.Role.ERROR, AppLanguage.tr(
                    AppLanguage.tr(
                    "无法观察界面：${snapshot.text}",
                    "Could not inspect the screen: ${snapshot.text}"
                ),
                    "Could not inspect the screen: ${snapshot.text}"
                )))
            return
        }

        val node = parseNodes(snapshot.text).firstOrNull {
            it.text?.contains(query, ignoreCase = true) == true ||
                it.desc?.contains(query, ignoreCase = true) == true
        }
        if (node == null) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "当前界面没有找到「$query」。可以先发送「观察」看看节点。",
                    "Could not find \"$query\" on this screen. Send \"observe\" first " +
                        "to see the nodes."
                )))
            return
        }

        val result = call("ui_tap", JSONObject().put("node_id", node.id), ctx, out)
        if (result.ok) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "已点击「${node.text ?: node.desc ?: node.id}」",
                    "Tapped \"${node.text ?: node.desc ?: node.id}\""
                )))
        }
    }

    private suspend fun type(command: String, ctx: ToolContext, out: MutableList<AgentMessage>) {
        val text = command.removePrefix("输入").removePrefix("type").trim()
        if (text.isBlank()) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "要输入什么？例如：输入 hello",
                    "What should I type? For example: type hello"
                )))
            return
        }

        val snapshot = call("ui_observe", JSONObject(), ctx, out)
        if (!snapshot.ok) {
            out.add(AgentMessage(AgentMessage.Role.ERROR, AppLanguage.tr(
                    AppLanguage.tr(
                    "无法观察界面：${snapshot.text}",
                    "Could not inspect the screen: ${snapshot.text}"
                ),
                    "Could not inspect the screen: ${snapshot.text}"
                )))
            return
        }

        val editable = parseNodes(snapshot.text).firstOrNull { it.editable }
        if (editable == null) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "当前界面没有可编辑的输入框。",
                    "There is no editable text field on this screen."
                )))
            return
        }

        val result = call(
            "ui_set_text",
            JSONObject().put("node_id", editable.id).put("text", text),
            ctx,
            out
        )
        if (result.ok) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr("已输入「$text」", "Typed \"$text\"")))
        }
    }

    private suspend fun waitFor(command: String, ctx: ToolContext, out: MutableList<AgentMessage>) {
        val text = command.removePrefix("等待").removePrefix("wait").trim()
        if (text.isBlank()) {
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr(
                    "要等待什么文本？例如：等待 完成",
                    "What text should I wait for? For example: wait Done"
                )))
            return
        }
        val result = call(
            "ui_wait_for",
            JSONObject().put("text", text).put("timeout_ms", 5000),
            ctx,
            out
        )
        out.add(
            AgentMessage(
                if (result.ok) AgentMessage.Role.AGENT else AgentMessage.Role.ERROR,
                if (result.ok) {
                    AppLanguage.tr("等到了「$text」", "Found \"$text\"")
                } else {
                    AppLanguage.tr("等待「$text」超时", "Timed out waiting for \"$text\"")
                }
            )
        )
    }

    private suspend fun observe(ctx: ToolContext, out: MutableList<AgentMessage>) {
        val result = call("ui_observe", JSONObject(), ctx, out)
        if (!result.ok) {
            out.add(AgentMessage(AgentMessage.Role.ERROR, AppLanguage.tr(
                    "无法观察界面：${result.text}",
                    "Could not inspect the screen: ${result.text}"
                )))
            return
        }
        val json = JSONObject(result.text)
        val nodes = json.optJSONArray("nodes")
        val texts = mutableListOf<String>()
        if (nodes != null) {
            for (i in 0 until minOf(nodes.length(), 8)) {
                val node = nodes.optJSONObject(i) ?: continue
                val text = node.optString("text")
                if (text.isNotBlank()) texts.add(text)
            }
        }
        out.add(
            AgentMessage(
                AgentMessage.Role.AGENT,
                AppLanguage.tr(
                    "当前 ${json.optString("package")} / ${json.optString("activity")}，" +
                        "共 ${nodes?.length() ?: 0} 个节点。\n" +
                        texts.joinToString(" | "),
                    "Current ${json.optString("package")} / ${json.optString("activity")}, " +
                        "${nodes?.length() ?: 0} node(s).\n" +
                        texts.joinToString(" | ")
                )
            )
        )
    }

    private suspend fun screenshot(ctx: ToolContext, out: MutableList<AgentMessage>) {
        val result = call("ui_screenshot", JSONObject(), ctx, out)
        out.add(
            AgentMessage(
                if (result.ok) AgentMessage.Role.AGENT else AgentMessage.Role.ERROR,
                if (result.ok) {
                    AppLanguage.tr(
                        "截图成功（图片通过 MCP 返回，本地 Agent 暂不显示）",
                        "Screenshot taken (returned through MCP; the local agent does " +
                            "not display it yet)"
                    )
                } else {
                    AppLanguage.tr("截图失败：${result.text}", "Screenshot failed: ${result.text}")
                }
            )
        )
    }

    private suspend fun key(key: String, ctx: ToolContext, out: MutableList<AgentMessage>) {
        val result = call("ui_key", JSONObject().put("key", key), ctx, out)
        if (result.ok) {
            val label = when (key) {
                "back" -> AppLanguage.tr("返回", "Back")
                "home" -> AppLanguage.tr("主页", "Home")
                else -> AppLanguage.tr("最近任务", "Recents")
            }
            out.add(AgentMessage(AgentMessage.Role.AGENT, AppLanguage.tr("已执行$label", "Performed: $label")))
        }
    }

    private suspend fun call(
        name: String,
        args: JSONObject,
        ctx: ToolContext,
        out: MutableList<AgentMessage>
    ): ToolResult {
        val result = registry.execute(name, args, ctx)
        val header = if (result.ok) "ok" else (result.errorCode ?: "error")
        out.add(
            AgentMessage(
                AgentMessage.Role.TOOL,
                "$name -> $header\n${summarizeToolResult(name, result)}"
            )
        )
        return result
    }

    private fun summarizeToolResult(name: String, result: ToolResult): String {
        if (!result.ok) return result.text.take(300)

        return when (name) {
            "space_list_apps" -> {
                val count = runCatching {
                    JSONObject(result.text).optJSONArray("apps")?.length() ?: 0
                }.getOrDefault(0)
                AppLanguage.tr("返回 $count 个应用", "Returned $count app(s)")
            }

            "ui_observe" -> {
                val json = runCatching { JSONObject(result.text) }.getOrNull()
                JSONObject()
                    .put("package", json?.optString("package").orEmpty())
                    .put("activity", json?.optString("activity").orEmpty())
                    .put("nodes_count", json?.optJSONArray("nodes")?.length() ?: 0)
                    .put("needs_user", json?.optBoolean("needs_user") ?: false)
                    .put("user_reason", json?.optString("user_reason").orEmpty())
                    .toString()
            }

            else -> result.text.take(300)
        }
    }

    private fun aliasPackage(query: String): String? = when (query.trim().lowercase()) {
        "设置", "settings" -> "com.android.settings"
        "相机", "camera" -> "com.android.camera"
        "时钟", "clock", "闹钟", "alarm" -> "com.android.deskclock"
        "计算器", "calculator" -> "com.android.calculator2"
        "联系人", "contacts" -> "com.android.contacts"
        "文件", "files" -> "com.android.documentsui"
        else -> null
    }

    private fun parseApps(text: String): List<Pair<String, String>> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("apps") ?: return emptyList()
        val result = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val label = item.optString("label")
            val pkg = item.optString("package")
            if (label.isNotBlank() && pkg.isNotBlank()) result.add(label to pkg)
        }
        return result
    }

    private data class NodeRef(val id: String, val text: String?, val desc: String?, val editable: Boolean)

    private fun parseNodes(text: String): List<NodeRef> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = json.optJSONArray("nodes") ?: return emptyList()
        val result = mutableListOf<NodeRef>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            result.add(
                NodeRef(
                    id = item.optString("id"),
                    text = item.optString("text").takeIf { it.isNotBlank() && it != "null" },
                    desc = item.optString("desc").takeIf { it.isNotBlank() && it != "null" },
                    editable = item.optBoolean("editable")
                )
            )
        }
        return result
    }
}
