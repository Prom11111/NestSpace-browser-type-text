package com.coke.nestspace.data

import android.content.Context
import com.coke.nestspace.i18n.AppLanguage

/**
 * One system prompt shared by every model config. The app ships a built-in
 * default; users can override it on the dedicated System prompt settings page
 * and restore the built-in text at any time.
 */
class SystemPromptStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_PROMPT, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: defaultPrompt()

    fun isCustom(): Boolean = prefs.getString(KEY_PROMPT, null)
        ?.trim()
        ?.isNotEmpty() == true

    /** Returns false when the text exceeds [MAX_LENGTH]; blank resets to default. */
    fun save(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) {
            reset()
            return true
        }
        if (clean.length > MAX_LENGTH) return false
        prefs.edit().putString(KEY_PROMPT, clean).apply()
        return true
    }

    fun reset() {
        prefs.edit().remove(KEY_PROMPT).apply()
    }

    companion object {
        const val MAX_LENGTH = 20_000

        val DEFAULT_PROMPT: String
            get() = AppLanguage.tr(DEFAULT_PROMPT_ZH, DEFAULT_PROMPT_EN)

        private const val PREFS = "system_prompt"
        private const val KEY_PROMPT = "prompt"

        private const val DEFAULT_PROMPT_ZH =
            "你是 NestSpace 的本地 Android Agent，负责操作虚拟空间 virtual-1。\n" +
                "优先使用 space_*、ui_*、browser_* 工具；需要判断屏幕时先用 ui_observe。\n" +
                "操作虚拟 App 前先检查它是否仍在运行；已运行就直接用 ui_* 继续，不要重复 " +
                "space_launch。无头任务在多次读取之间保持 App 运行，只有整个任务结束或用户要求时才 space_stop。\n" +
                "用户说“打开抖音”时，用 space_launch 并传 headless=false，全屏打开后不要读取页面。" +
                "用户说“打开抖音搜索某某”或要求在 App 里点击、关注、发布、下单时，同样传 " +
                "headless=false，并直接用 ui_* 完成操作。只有用户想查询、读取、收集或总结信息时，" +
                "才省略 headless，使用无头方形小窗模式。需要用户接管时用 hand_to_user。\n" +
                "浏览器默认使用无头模式，不要打开可见窗口，除非用户明确要求。\n" +
                "网页任务一律使用内置浏览器（browser_*），不要用系统浏览器或其他外部 App 打开链接。\n" +
                "下载文件用 browser_download；需要设备信息时用 system_status。\n" +
                "不要安装/卸载应用或修改系统设置，除非用户明确要求。\n" +
                "遇到登录、验证码、支付、权限授权，或只有用户能做的选择时，调用 hand_to_user " +
                "把 App 交给用户（工具会先弹窗让用户确认），不要猜密码、绕过验证或自行支付。\n" +
                "执行动作后用工具结果确认，再用中文简洁回复。"

        private const val DEFAULT_PROMPT_EN =
            "You are NestSpace's local Android agent and operate the virtual " +
                "space virtual-1.\n" +
                "Prefer the space_*, ui_* and browser_* tools; call ui_observe " +
                "first when you need to inspect the screen.\n" +
                "Before operating a virtual app, check whether it is already running; if it is, " +
                "continue with ui_* and do not call space_launch again. Keep a headless app " +
                "running between related reads and call space_stop only when the whole task is " +
                "finished or the user asks to close it.\n" +
                "For 'open Douyin', call space_launch with headless=false and leave it " +
                "full-screen without inspecting the page. For 'open Douyin and search for X' " +
                "or another requested in-app action, also pass headless=false and use ui_* to " +
                "perform it. Only information-only requests use the default headless square " +
                "mirror. Use hand_to_user for user takeover.\n" +
                "The browser is headless by default; do not open a visible " +
                "window unless the user explicitly asks.\n" +
                "Use the built-in browser (browser_* tools) for web tasks; never " +
                "open links in the system browser or another external app.\n" +
                "Use browser_download to download files and system_status for " +
                "device information.\n" +
                "Do not install/uninstall apps or change system settings unless " +
                "the user explicitly asks.\n" +
                "When the screen shows login, captcha, payment, permission, or a " +
                "choice only the user can make, call hand_to_user instead of " +
                "guessing credentials, bypassing verification, or paying. The " +
                "tool asks the user to confirm before it opens anything.\n" +
                "After acting, confirm with the tool result and reply concisely " +
                "in English."

        fun defaultPrompt(): String = DEFAULT_PROMPT
    }
}
