package com.coke.nestspace.agent

import android.content.Context
import com.coke.nestspace.i18n.AppLanguage

/** How much confirmation the assistant asks for before mutating actions. */
enum class ToolPermissionLevel(val storageValue: String) {
    STRICT("strict"),
    STANDARD("standard"),
    LOOSE("loose"),
    CUSTOM("custom");

    val label: String
        get() = when (this) {
            STRICT -> AppLanguage.tr("严格", "Strict")
            STANDARD -> AppLanguage.tr("标准", "Standard")
            LOOSE -> AppLanguage.tr("宽松", "Loose")
            CUSTOM -> AppLanguage.tr("自定义", "Custom")
        }

    val description: String
        get() = when (this) {
            STRICT -> AppLanguage.tr(
                "所有敏感操作都需要确认：安装/卸载、权限变更、风险点击和下载等。",
                "Confirm every sensitive action: install/uninstall, permission changes, " +
                    "risky taps and downloads."
            )
            STANDARD -> AppLanguage.tr(
                "在宽松模式基础上增加应用安装/卸载确认，其他操作直接执行。",
                "Loose mode plus confirmation for app install/uninstall; other actions run directly."
            )
            LOOSE -> AppLanguage.tr(
                "只有支付、下单、转账、红包等涉及金钱的操作才提示。",
                "Only prompt for money-related actions such as payment, checkout, " +
                    "transfers and red packets."
            )
            CUSTOM -> AppLanguage.tr(
                "由你选择哪些操作需要确认。",
                "Choose which actions need confirmation."
            )
        }
}

/** Independently toggleable confirmation categories for Custom mode. */
enum class ToolConfirmationCategory(val api: String) {
    INSTALL("install"),
    PERMISSION("permission"),
    DOWNLOAD("download"),
    RISKY("risky"),
    MONEY("money"),
    EXTERNAL("external");

    val label: String
        get() = when (this) {
            INSTALL -> AppLanguage.tr("安装/卸载软件", "Install/uninstall")
            PERMISSION -> AppLanguage.tr("权限变更", "Permission changes")
            DOWNLOAD -> AppLanguage.tr("文件下载", "File downloads")
            RISKY -> AppLanguage.tr("风险点击", "Risky taps")
            MONEY -> AppLanguage.tr("金钱操作", "Money actions")
            EXTERNAL -> AppLanguage.tr("外部 MCP 工具", "External MCP tools")
        }

    val description: String
        get() = when (this) {
            INSTALL -> AppLanguage.tr(
                "安装或卸载虚拟应用，以及点击界面中的安装/卸载按钮。",
                "Install or uninstall virtual apps and tap install/uninstall buttons in apps."
            )
            PERMISSION -> AppLanguage.tr(
                "授予或撤销虚拟应用的运行时权限。",
                "Grant or revoke runtime permissions for virtual apps."
            )
            DOWNLOAD -> AppLanguage.tr(
                "浏览器下载文件。",
                "Download files from the browser."
            )
            RISKY -> AppLanguage.tr(
                "发送、删除等不可逆的界面操作。",
                "Irreversible UI actions such as send or delete."
            )
            MONEY -> AppLanguage.tr(
                "支付、下单、转账、红包、打赏、充值等操作。",
                "Payment, checkout, transfers, red packets, tips and top-ups."
            )
            EXTERNAL -> AppLanguage.tr(
                "调用你配置的外部 MCP Server 工具；这些工具可能执行真实操作。",
                "Call tools from your external MCP servers; these tools may perform " +
                    "real actions."
            )
        }
}

object ToolPermissionSettings {

    private const val PREFS = "tool_permissions"
    private const val KEY_LEVEL = "level"
    private const val KEY_CUSTOM = "custom_categories"

    fun load(context: Context): ToolPermissionLevel {
        val value = prefs(context).getString(KEY_LEVEL, null)
        return ToolPermissionLevel.entries.firstOrNull { it.storageValue == value }
            ?: ToolPermissionLevel.STANDARD
    }

    fun save(context: Context, level: ToolPermissionLevel) {
        prefs(context).edit().putString(KEY_LEVEL, level.storageValue).apply()
    }

    /** Categories enabled in Custom mode. Defaults to all categories. */
    fun customCategories(context: Context): Set<ToolConfirmationCategory> {
        val stored = prefs(context).getString(KEY_CUSTOM, null) ?: return allCategories()
        if (stored.isBlank()) return emptySet()
        return stored.split(',')
            .mapNotNull { api -> ToolConfirmationCategory.entries.firstOrNull { it.api == api } }
            .toSet()
    }

    fun saveCustomCategories(
        context: Context,
        categories: Set<ToolConfirmationCategory>
    ) {
        val value = ToolConfirmationCategory.entries
            .filter { it in categories }
            .joinToString(",") { it.api }
        prefs(context).edit().putString(KEY_CUSTOM, value).apply()
    }

    fun enabledCategories(context: Context): Set<ToolConfirmationCategory> =
        when (val level = load(context)) {
            ToolPermissionLevel.STRICT -> allCategories()
            ToolPermissionLevel.STANDARD -> setOf(
                ToolConfirmationCategory.INSTALL,
                ToolConfirmationCategory.MONEY
            )
            ToolPermissionLevel.LOOSE -> setOf(ToolConfirmationCategory.MONEY)
            ToolPermissionLevel.CUSTOM -> customCategories(context)
        }

    fun shouldConfirm(context: Context, category: ToolConfirmationCategory): Boolean =
        when (load(context)) {
            ToolPermissionLevel.STRICT -> true
            ToolPermissionLevel.STANDARD -> category == ToolConfirmationCategory.INSTALL ||
                category == ToolConfirmationCategory.MONEY
            ToolPermissionLevel.LOOSE -> category == ToolConfirmationCategory.MONEY
            ToolPermissionLevel.CUSTOM -> category in customCategories(context)
        }

    /**
     * Saving a procedure or skill changes future assistant behavior. In the
     * default Standard mode (and Loose) this is not routed through the risky
     * confirmation gate; only Strict/Custom-risk modes keep the inline review.
     */
    fun requiresReviewForLearning(context: Context): Boolean =
        shouldConfirm(context, ToolConfirmationCategory.RISKY)

    fun parse(value: String): ToolPermissionLevel? =
        ToolPermissionLevel.entries.firstOrNull {
            it.storageValue == value.trim().lowercase()
        }

    fun parseCategory(value: String): ToolConfirmationCategory? =
        ToolConfirmationCategory.entries.firstOrNull {
            it.api == value.trim().lowercase()
        }

    fun allCategories(): Set<ToolConfirmationCategory> =
        ToolConfirmationCategory.entries.toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
