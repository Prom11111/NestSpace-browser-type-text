package com.coke.nestspace.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.browser.BrowserActivity
import com.coke.nestspace.browser.BrowserSession
import com.coke.nestspace.container.ContainerProcessPool
import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * One user decision at a time: a confirmation before a risky tool call, or a
 * takeover where the user must handle login/captcha/payment themselves.
 *
 * The tool call suspends until the user approves/denies (or finishes the
 * takeover). The same request is shown in the assistant UI and as a
 * high-priority notification, so it is still reachable while a virtual app
 * is in the foreground.
 */
object UserGateManager {

    data class Confirmation(
        val id: Long,
        val title: String,
        val detail: String,
        val confirmLabel: String,
        val dismissLabel: String
    )

    enum class TakeoverTarget { CONTAINER, BROWSER }

    data class Takeover(
        val id: Long,
        val title: String,
        val detail: String,
        val packageName: String,
        val instanceId: String,
        val slot: Int,
        val reason: String,
        val target: TakeoverTarget = TakeoverTarget.CONTAINER,
        val tabId: String? = null,
        val url: String? = null
    )

    enum class TakeoverResult { COMPLETED, SKIPPED, CANCELLED, TIMEOUT }

    private const val CHANNEL_ID = "user_gate"
    private const val CONFIRM_NOTIFICATION = 7101
    private const val TAKEOVER_NOTIFICATION = 7102
    private const val CONFIRM_TIMEOUT_MS = 120_000L
    private const val TAKEOVER_TIMEOUT_MS = 5 * 60_000L

    private val ids = AtomicLong(1)
    private val gate = Mutex()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var takeoverDeferred: CompletableDeferred<TakeoverResult>? = null

    private val _confirmation = MutableStateFlow<Confirmation?>(null)
    val confirmation: StateFlow<Confirmation?> = _confirmation

    private val _takeover = MutableStateFlow<Takeover?>(null)
    val takeover: StateFlow<Takeover?> = _takeover

    fun init(context: Context) {
        appContext = context.applicationContext
        // A pending gate cannot survive process death. Remove notifications
        // left by the previous process so their actions do not look active.
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(CONFIRM_NOTIFICATION)
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(TAKEOVER_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppLanguage.tr("需要你的操作", "Your action is needed"),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    /**
     * Ask before a risky, hard-to-undo action. Returns false when the user
     * denies, dismisses, or does not answer in time.
     */
    suspend fun askConfirmation(
        title: String,
        detail: String,
        timeoutMs: Long = CONFIRM_TIMEOUT_MS,
        confirmLabel: String = AppLanguage.tr("允许一次", "Allow once"),
        dismissLabel: String = AppLanguage.tr("拒绝", "Deny")
    ): Boolean {
        if (!gate.tryLock()) return false
        try {
            if (_confirmation.value != null || _takeover.value != null) return false
            return awaitConfirmation(title, detail, confirmLabel, dismissLabel, timeoutMs)
        } finally {
            gate.unlock()
        }
    }

    /**
     * Publishes a confirmation and waits for the answer. The caller must hold
     * [gate]; cleanup here keeps a cancelled tool call from leaving a stale
     * dialog or notification behind.
     */
    private suspend fun awaitConfirmation(
        title: String,
        detail: String,
        confirmLabel: String,
        dismissLabel: String,
        timeoutMs: Long
    ): Boolean {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        _confirmation.value = Confirmation(
            id = id,
            title = title,
            detail = detail,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel
        )
        notifyConfirmation(id, title, detail, confirmLabel, dismissLabel)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            _confirmation.value = null
            confirmationDeferred = null
            cancel(CONFIRM_NOTIFICATION)
        }
    }

    fun approveConfirmation(id: Long) {
        val current = _confirmation.value ?: return
        if (current.id != id) return
        confirmationDeferred?.complete(true)
    }

    fun denyConfirmation(id: Long) {
        val current = _confirmation.value ?: return
        if (current.id != id) return
        confirmationDeferred?.complete(false)
    }

    /**
     * Bring the container app to the foreground and wait for the user to
     * finish a step that only they can do. The caller should observe the
     * screen again after [TakeoverResult.COMPLETED].
     */
    suspend fun takeOver(
        context: Context,
        title: String,
        detail: String,
        packageName: String,
        instanceId: String,
        slot: Int,
        reason: String,
        target: TakeoverTarget = TakeoverTarget.CONTAINER,
        tabId: String? = null,
        url: String? = null,
        confirmFirst: Boolean = true,
        timeoutMs: Long = TAKEOVER_TIMEOUT_MS,
        bringToFront: suspend () -> Boolean
    ): TakeoverResult {
        if (!gate.tryLock()) return TakeoverResult.CANCELLED
        try {
            if (_confirmation.value != null || _takeover.value != null) {
                return TakeoverResult.CANCELLED
            }
            // Do not yank the user into a full-screen browser/app without
            // warning: ask first, and only then take over the screen.
            if (confirmFirst) {
                val approved = awaitConfirmation(
                    title = title,
                    detail = AppLanguage.tr(
                        "确认后将打开全屏页面，请你在页面中完成这一步。\n$detail",
                        "The full-screen page will open after you confirm. " +
                            "Please complete this step there.\n$detail"
                    ),
                    confirmLabel = AppLanguage.tr("打开全屏", "Open full screen"),
                    dismissLabel = AppLanguage.tr("跳过", "Skip"),
                    timeoutMs = CONFIRM_TIMEOUT_MS
                )
                if (!approved) return TakeoverResult.SKIPPED
            }
            if (!bringToFront()) return TakeoverResult.CANCELLED
            val id = ids.getAndIncrement()
            val deferred = CompletableDeferred<TakeoverResult>()
            takeoverDeferred = deferred
            _takeover.value = Takeover(
                id = id,
                title = title,
                detail = detail,
                packageName = packageName,
                instanceId = instanceId,
                slot = slot,
                reason = reason,
                target = target,
                tabId = tabId,
                url = url
            )
            notifyTakeover(id, title, detail, target, tabId)
            return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: TakeoverResult.TIMEOUT
        } finally {
            _takeover.value = null
            takeoverDeferred = null
            cancel(TAKEOVER_NOTIFICATION)
            gate.unlock()
        }
    }

    fun completeTakeover(id: Long, result: TakeoverResult) {
        val current = _takeover.value ?: return
        if (current.id != id) return
        takeoverDeferred?.complete(result)
    }

    fun bringTakeoverToFront(context: Context, request: Takeover): Boolean =
        when (request.target) {
            TakeoverTarget.BROWSER ->
                BrowserSession.showTab(context, request.tabId)
            TakeoverTarget.CONTAINER ->
                ContainerProcessPool.bringToFront(
                    context,
                    request.slot,
                    request.packageName,
                    request.instanceId
                )
        }

    private fun notifyConfirmation(
        id: Long,
        title: String,
        detail: String,
        confirmLabel: String,
        dismissLabel: String
    ) {
        val context = appContext ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openTarget(context, id, TakeoverTarget.CONTAINER, null))
            .addAction(
                0,
                confirmLabel,
                action(context, id, 1, UserGateReceiver.ACTION_CONFIRM_ALLOW)
            )
            .addAction(
                0,
                dismissLabel,
                action(context, id, 2, UserGateReceiver.ACTION_CONFIRM_DENY)
            )
        NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION, builder.build())
    }

    private fun notifyTakeover(
        id: Long,
        title: String,
        detail: String,
        target: TakeoverTarget,
        tabId: String?
    ) {
        val context = appContext ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openTarget(context, id, target, tabId))
            .addAction(
                0,
                AppLanguage.tr("完成", "Done"),
                action(context, id, 3, UserGateReceiver.ACTION_TAKEOVER_DONE)
            )
            .addAction(
                0,
                AppLanguage.tr("跳过", "Skip"),
                action(context, id, 4, UserGateReceiver.ACTION_TAKEOVER_SKIP)
            )
        NotificationManagerCompat.from(context).notify(TAKEOVER_NOTIFICATION, builder.build())
    }

    private fun openTarget(
        context: Context,
        id: Long,
        target: TakeoverTarget,
        tabId: String?
    ): PendingIntent {
        val intent = when (target) {
            TakeoverTarget.BROWSER -> Intent(context, BrowserActivity::class.java).apply {
                if (tabId != null) putExtra(BrowserActivity.EXTRA_TAB_ID, tabId)
            }
            TakeoverTarget.CONTAINER -> Intent(context, MainActivity::class.java)
        }
        return PendingIntent.getActivity(
            context,
            id.toInt(),
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(
        context: Context,
        id: Long,
        slot: Int,
        action: String
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id.toInt() * 10) + slot,
        Intent(context, UserGateReceiver::class.java)
            .setAction(action)
            .putExtra(UserGateReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancel(notificationId: Int) {
        appContext?.let { NotificationManagerCompat.from(it).cancel(notificationId) }
    }

    /** True when tapping [label] is likely to change money, data, or send content. */
    fun isRiskyTap(label: String): Boolean {
        val text = label.lowercase()
        if (text.isBlank()) return false
        return RISKY_WORDS.any { text.contains(it) }
    }

    fun isMoneyTap(label: String): Boolean {
        val text = label.lowercase()
        if (text.isBlank()) return false
        return MONEY_WORDS.any { text.contains(it) }
    }

    fun isInstallTap(label: String): Boolean {
        val text = label.lowercase()
        if (text.isBlank()) return false
        return INSTALL_WORDS.any { text.contains(it) }
    }

    /**
     * Add a hint to a ui_observe result when the screen looks like a login,
     * captcha, payment, or permission gate. The model is told to call
     * hand_to_user instead of guessing.
     */
    fun annotateSensitive(json: String): String {
        val reason = sensitiveReason(json) ?: return json
        return runCatching {
            JSONObject(json)
                .put("needs_user", true)
                .put("user_reason", reason)
                .put(
                    "hint",
                    "Call hand_to_user with reason=$reason; do not guess, bypass, or enter credentials."
                )
                .toString()
        }.getOrDefault(json)
    }

    private fun sensitiveReason(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val nodes = root.optJSONArray("nodes") ?: return null
        val text = buildString {
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                append(node.optString("text")).append(' ')
                append(node.optString("desc")).append(' ')
            }
        }.lowercase()
        if (text.isBlank()) return null
        return when {
            SENSITIVE_PAYMENT.any { text.contains(it) } -> "payment"
            SENSITIVE_CAPTCHA.any { text.contains(it) } -> "captcha"
            SENSITIVE_LOGIN.any { text.contains(it) } -> "login"
            SENSITIVE_PERMISSION.any { text.contains(it) } -> "permission"
            else -> null
        }
    }

    private val MONEY_WORDS = listOf(
        "支付", "付款", "收银台", "付款码", "下单", "提交订单", "购买",
        "转账", "红包", "打赏", "充值", "提现", "钱包", "余额支付",
        "确认支付", "立即支付",
        "pay", "payment", "purchase", "checkout", "buy now", "transfer",
        "tip", "reward", "recharge", "top up", "wallet"
    )

    private val INSTALL_WORDS = listOf(
        "安装", "卸载", "install", "uninstall"
    )

    private val RISKY_WORDS = MONEY_WORDS + INSTALL_WORDS + listOf(
        "删除", "发送", "delete", "send"
    )

    private val SENSITIVE_PAYMENT = listOf(
        "支付", "付款", "收银台", "付款码", "payment", "pay now"
    )
    private val SENSITIVE_CAPTCHA = listOf(
        "验证码", "滑块", "安全验证", "人机验证", "captcha", "verification"
    )
    private val SENSITIVE_LOGIN = listOf(
        "登录", "手机号登录", "扫码登录", "sign in", "log in", "password login"
    )
    private val SENSITIVE_PERMISSION = listOf(
        "请求权限", "申请权限", "权限请求", "允许访问", "允许使用",
        "permission request", "allow access", "grant permission"
    )
}

/** Receives the notification buttons for confirmations and takeovers. */
class UserGateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        UserGateManager.init(context)
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0) return
        when (intent.action) {
            ACTION_CONFIRM_ALLOW -> UserGateManager.approveConfirmation(id)
            ACTION_CONFIRM_DENY -> UserGateManager.denyConfirmation(id)
            ACTION_TAKEOVER_DONE -> UserGateManager.completeTakeover(
                id,
                UserGateManager.TakeoverResult.COMPLETED
            )
            ACTION_TAKEOVER_SKIP -> UserGateManager.completeTakeover(
                id,
                UserGateManager.TakeoverResult.SKIPPED
            )
        }
    }

    companion object {
        const val EXTRA_ID = "user_gate_id"
        const val ACTION_CONFIRM_ALLOW = "com.coke.nestspace.USER_GATE_CONFIRM_ALLOW"
        const val ACTION_CONFIRM_DENY = "com.coke.nestspace.USER_GATE_CONFIRM_DENY"
        const val ACTION_TAKEOVER_DONE = "com.coke.nestspace.USER_GATE_TAKEOVER_DONE"
        const val ACTION_TAKEOVER_SKIP = "com.coke.nestspace.USER_GATE_TAKEOVER_SKIP"
    }
}
