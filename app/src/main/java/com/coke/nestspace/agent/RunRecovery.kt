package com.coke.nestspace.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage

/**
 * Finishes records left behind by a process that died while an assistant run
 * was active. Runs are marked [RunRecordStore.STATUS_INTERRUPTED] once, then
 * shown in the assistant message stream and, when allowed, a notification.
 */
object RunRecovery {

    private const val TAG = "RunRecovery"
    private const val CHANNEL_ID = "run_recovery"
    private const val NOTIFICATION_ID = 0x5255

    fun recover(context: Context): List<RunSummary> {
        val app = context.applicationContext
        val recovered = runCatching {
            RunRecordStore.recoverInterrupted(app.filesDir)
        }.onFailure {
            Log.w(TAG, "Cannot recover interrupted runs", it)
        }.getOrDefault(emptyList())
        if (recovered.isEmpty()) return recovered
        // Recovery must never make app startup fail, regardless of whether
        // the assistant UI or notification permission is available yet.
        runCatching { AssistantConversation.noticeInterrupted(recovered) }
            .onFailure { Log.w(TAG, "Cannot show recovery notice", it) }
        runCatching { notify(app, recovered) }
            .onFailure { Log.w(TAG, "Cannot post recovery notification", it) }
        return recovered
    }

    private fun notify(context: Context, recovered: List<RunSummary>) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        AppLanguage.tr("任务恢复", "Run recovery"),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
        }
        val count = recovered.size
        val title = if (count == 1) {
            AppLanguage.tr("上次任务被中断", "Previous task interrupted")
        } else {
            AppLanguage.tr("$count 个任务被中断", "$count tasks interrupted")
        }
        val detail = buildString {
            recovered.take(3).forEachIndexed { index, run ->
                if (index > 0) append('\n')
                append("• ").append(run.command.take(120))
            }
            if (count > 3) {
                append('\n')
                append(AppLanguage.tr("另有 ${count - 3} 个任务", "${count - 3} more task(s)"))
            }
            append('\n')
            append(AppLanguage.tr("打开 NestSpace 查看运行历史。", "Open NestSpace to review run history."))
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(detail.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
