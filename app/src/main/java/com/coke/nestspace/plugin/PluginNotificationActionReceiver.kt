package com.coke.nestspace.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class PluginNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID).orEmpty()
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
        if (pluginId.isBlank() || notificationId.isBlank() || actionId.isBlank()) {
            return
        }
        PluginEventBus.publish(
            PluginEvent(
                EVENT_TYPE,
                pluginId,
                JSONObject()
                    .put("notification_id", notificationId)
                    .put("action_id", actionId)
                    .toString(),
                System.currentTimeMillis()
            )
        )
    }

    companion object {
        const val EVENT_TYPE = "notification.action"
        const val EXTRA_PLUGIN_ID = "plugin_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_ACTION_ID = "action_id"
    }
}
