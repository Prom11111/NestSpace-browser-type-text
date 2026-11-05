package com.coke.nestspace.host

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class HostNode(
    val id: String,
    val role: String,
    val text: String?,
    val desc: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val selected: Boolean,
    val packageName: String
) {
    fun centerX(): Int = (left + right) / 2
    fun centerY(): Int = (top + bottom) / 2
}

/**
 * Controls the real Android device through root shell commands.
 *
 * Observation uses `uiautomator dump`; taps, keys and text use `input`;
 * screenshots use `screencap`. The last observation is cached so a node id can
 * be resolved to screen coordinates by a later tap, mirroring the virtual
 * container UI tools.
 */
object HostUiController {

    private const val DUMP_PATH = "/data/local/tmp/nestspace_host_ui.xml"
    private const val SHOT_PATH = "/data/local/tmp/nestspace_host.png"
    private const val MAX_NODES = 500

    private val packageRegex = Regex("^[A-Za-z0-9_.]+$")
    private val boundsRegex = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
    private val focusedRegex = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)")
    private val sizeRegex = Regex("(\\d+)x(\\d+)")

    @Volatile
    private var lastNodes: List<HostNode> = emptyList()

    @Volatile
    private var lastPackage: String = ""

    @Volatile
    private var lastActivity: String = ""

    @Volatile
    private var screenWidth: Int = 0

    @Volatile
    private var screenHeight: Int = 0

    suspend fun observe(): String? {
        val script = buildString {
            append("uiautomator dump ").append(DUMP_PATH).append(" >/dev/null 2>&1; ")
            append("echo __NESTSPACE_XML__; cat ").append(DUMP_PATH).append("; ")
            append("echo __NESTSPACE_META__; ")
            append("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4; ")
            append("echo __NESTSPACE_SIZE__; wm size")
        }
        val result = RootShell.exec(script, 30_000L)
        val xml = between(result.output, "__NESTSPACE_XML__", "__NESTSPACE_META__")
            ?: return null
        val cleanXml = xml.trimStart { it.isWhitespace() || it == '﻿' }
        if (!cleanXml.contains("<hierarchy")) return null
        val meta = between(result.output, "__NESTSPACE_META__", "__NESTSPACE_SIZE__").orEmpty()
        val sizeText = result.output.substringAfter("__NESTSPACE_SIZE__", "")

        val document = parseXml(cleanXml) ?: return null
        val root = document.documentElement ?: return null
        val nodes = mutableListOf<HostNode>()
        collect(root, nodes)
        lastNodes = nodes

        val focused = focusedRegex.find(meta)
        lastPackage = focused?.groupValues?.get(1)
            ?: nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName
            ?: ""
        lastActivity = focused?.groupValues?.get(2).orEmpty()

        val size = sizeRegex.find(sizeText)
        screenWidth = size?.groupValues?.get(1)?.toIntOrNull()
            ?: nodes.maxOfOrNull { it.right }
            ?: 0
        screenHeight = size?.groupValues?.get(2)?.toIntOrNull()
            ?: nodes.maxOfOrNull { it.bottom }
            ?: 0

        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject()
                    .put("id", node.id)
                    .put("role", node.role)
                    .put("text", node.text ?: JSONObject.NULL)
                    .put("desc", node.desc ?: JSONObject.NULL)
                    .put(
                        "bounds",
                        JSONArray(
                            listOf(node.left, node.top, node.right, node.bottom)
                        )
                    )
                    .put("clickable", node.clickable)
                    .put("editable", node.editable)
                    .put("scrollable", node.scrollable)
                    .put("enabled", node.enabled)
                    .put("focused", node.focused)
                    .put("selected", node.selected)
                    .put("package", node.packageName)
            )
        }
        return JSONObject()
            .put("target", "host")
            .put("package", lastPackage)
            .put("activity", lastActivity)
            .put(
                "screen",
                JSONObject().put("width", screenWidth).put("height", screenHeight)
            )
            .put("nodes", array)
            .put("timestamp_ms", System.currentTimeMillis())
            .toString()
    }

    fun labelFor(nodeId: String, text: String): String? {
        val direct = text.trim()
        if (direct.isNotBlank()) return direct
        if (nodeId.isBlank()) return null
        return lastNodes.firstOrNull { it.id == nodeId }?.let { it.text ?: it.desc }
    }

    suspend fun tap(nodeId: String, text: String, x: Int?, y: Int?): Boolean {
        val node = when {
            x != null && y != null -> null
            text.isNotBlank() -> lastNodes.firstOrNull { node ->
                node.text == text || node.desc == text ||
                    node.text?.contains(text) == true || node.desc?.contains(text) == true
            }
            nodeId.isNotBlank() -> lastNodes.firstOrNull { it.id == nodeId }
            else -> null
        }
        val px = x ?: node?.centerX() ?: return false
        val py = y ?: node?.centerY() ?: return false
        if (px < 0 || py < 0 || px > 20_000 || py > 20_000) return false
        return RootShell.exec("input tap $px $py").ok
    }

    suspend fun setText(text: String): RootResult {
        if (text.isBlank()) {
            return RootResult(false, "", error = "text is empty")
        }
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(" ", "%s")
        return RootShell.exec("input text '$escaped'")
    }

    suspend fun pressKey(key: String): RootResult {
        val code = when (key.trim().lowercase()) {
            "back" -> "KEYCODE_BACK"
            "home" -> "KEYCODE_HOME"
            "enter" -> "KEYCODE_ENTER"
            "recents", "app_switch" -> "KEYCODE_APP_SWITCH"
            "delete", "del" -> "KEYCODE_DEL"
            "tab" -> "KEYCODE_TAB"
            "escape" -> "KEYCODE_ESCAPE"
            else -> return RootResult(false, "", error = "unsupported key: $key")
        }
        return RootShell.exec("input keyevent $code")
    }

    suspend fun screenshotBase64(): String? {
        val result = RootShell.exec(
            "screencap -p $SHOT_PATH >/dev/null 2>&1; base64 $SHOT_PATH",
            30_000L
        )
        if (!result.ok) return null
        val raw = result.output.filterNot { it.isWhitespace() }
        if (raw.isBlank()) return null
        return runCatching {
            Base64.decode(raw, Base64.DEFAULT)
            raw
        }.getOrNull()
    }

    suspend fun launch(packageName: String): RootResult {
        val pkg = packageName.trim()
        if (!packageRegex.matches(pkg)) {
            return RootResult(false, "", error = "invalid package name")
        }
        val result = RootShell.exec(
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1",
            20_000L
        )
        val failed = result.output.contains("No activities found", ignoreCase = true) ||
            result.output.contains("monkey aborted", ignoreCase = true)
        return if (result.ok && !failed) {
            result
        } else {
            RootResult(false, result.output, result.exitCode, result.error ?: "launch failed")
        }
    }

    suspend fun listPackages(includeSystem: Boolean): String? {
        val command = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = RootShell.exec(command, 20_000L)
        if (!result.ok) return null
        val packages = JSONArray()
        result.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .forEach { packages.put(it.removePrefix("package:")) }
        return JSONObject()
            .put("include_system", includeSystem)
            .put("packages", packages)
            .toString()
    }

    suspend fun openUrl(url: String): RootResult {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return RootResult(false, "", error = "only http/https URLs are supported")
        }
        val escaped = trimmed.replace("'", "'\\''")
        return RootShell.exec("am start -a android.intent.action.VIEW -d '$escaped'", 20_000L)
    }

    private fun collect(element: Element, out: MutableList<HostNode>) {
        if (out.size >= MAX_NODES) return
        val text = element.getAttribute("text").takeIf { it.isNotBlank() }
        val desc = element.getAttribute("content-desc").takeIf { it.isNotBlank() }
        val className = element.getAttribute("class")
        val clickable = element.getAttribute("clickable").toBoolean()
        val scrollable = element.getAttribute("scrollable").toBoolean()
        val focused = element.getAttribute("focused").toBoolean()
        val editable = className.contains("EditText") ||
            className.contains("AutoCompleteTextView")
        val bounds = boundsRegex.find(element.getAttribute("bounds"))
        if (bounds != null &&
            (text != null || desc != null || clickable || editable || scrollable || focused)
        ) {
            val left = bounds.groupValues[1].toIntOrNull() ?: 0
            val top = bounds.groupValues[2].toIntOrNull() ?: 0
            val right = bounds.groupValues[3].toIntOrNull() ?: 0
            val bottom = bounds.groupValues[4].toIntOrNull() ?: 0
            out.add(
                HostNode(
                    id = "h${out.size}",
                    role = className.substringAfterLast('.'),
                    text = text,
                    desc = desc,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable,
                    enabled = element.getAttribute("enabled").toBoolean(),
                    focused = focused,
                    selected = element.getAttribute("selected").toBoolean(),
                    packageName = element.getAttribute("package").orEmpty()
                )
            )
        }
        val children = element.childNodes
        for (i in 0 until children.length) {
            (children.item(i) as? Element)?.let { collect(it, out) }
        }
    }

    private fun parseXml(xml: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        runCatching {
            factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
            )
        }
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    private fun between(source: String, start: String, end: String): String? {
        val from = source.indexOf(start)
        if (from < 0) return null
        val to = source.indexOf(end, from + start.length)
        if (to < 0) return null
        return source.substring(from + start.length, to)
    }
}
