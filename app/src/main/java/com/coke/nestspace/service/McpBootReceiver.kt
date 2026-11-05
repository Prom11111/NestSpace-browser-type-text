package com.coke.nestspace.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.coke.nestspace.schedule.ScheduledTaskManager

/** Starts the MCP server after boot so agents can attach without opening the app. */
class McpBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                McpForegroundService.ensureStarted(app)
                runCatching { ScheduledTaskManager.rescheduleAll(app) }
                    .onFailure { Log.w(TAG, "Cannot reschedule tasks after boot", it) }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching { ScheduledTaskManager.rescheduleAll(app) }
                    .onFailure { Log.w(TAG, "Cannot reschedule tasks after time/package change", it) }
            }
        }
    }

    private companion object {
        const val TAG = "McpBootReceiver"
    }
}
