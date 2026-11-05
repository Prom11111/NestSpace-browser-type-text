package com.coke.nestspace.container.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.util.Log
import java.lang.reflect.Field

/**
 * Virtualizes the NotificationManager Binder surface for one container.
 *
 * Target apps are not installed for the host UID, so notifications are posted
 * under the host package. To keep apps isolated from each other we:
 *
 * - prefix tags and notification channel/group ids with the virtual package;
 * - rewrite target package/attribution arguments to the host package;
 * - re-render target small icons to bitmaps (the system would otherwise
 *   resolve the resource id against the host resource table);
 * - replace target-created PendingIntents, which the system cannot send to a
 *   package that belongs to the host UID, with a host launch trampoline;
 * - tag the notification with its virtual origin for the notification center.
 *
 * Results (channels returned to the app) are un-prefixed so target code keeps
 * seeing its own ids.
 */
object ContainerNotificationBridge {

    const val EXTRA_ORIGIN_PACKAGE = "nestspace.virtual.package"
    const val EXTRA_ORIGIN_INSTANCE = "nestspace.virtual.instance"

    private const val TAG = "ContainerNotifications"

    class Env(
        val targetPackage: String,
        val instanceId: String,
        val hostPackage: String,
        val resources: Resources?,
        val context: Context,
        val launchPendingIntent: () -> PendingIntent?
    )

    fun rewriteCall(
        methodName: String,
        originalArgs: Array<Any?>?,
        env: Env
    ): Array<Any?>? {
        val args = originalArgs ?: return null
        val copy = args.copyOf()
        for (index in copy.indices) {
            val value = copy[index] ?: continue
            when (value) {
                is String -> {
                    copy[index] = when (value) {
                        env.targetPackage -> env.hostPackage
                        else -> when {
                            isGroupMethod(methodName) && value != env.hostPackage ->
                                prefixGroupId(env, value)
                            isChannelMethod(methodName) && value != env.hostPackage ->
                                prefixChannelId(env, value)
                            else -> value
                        }
                    }
                }
                is Notification -> copy[index] = munge(env, value)
                else -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    when (value) {
                        is NotificationChannel -> prefixChannel(env, value)
                        is NotificationChannelGroup -> prefixGroup(env, value)
                        is List<*> -> value.forEach { item ->
                            when (item) {
                                is NotificationChannel -> prefixChannel(env, item)
                                is NotificationChannelGroup -> prefixGroup(env, item)
                            }
                        }
                        is Array<*> -> value.forEach { item ->
                            when (item) {
                                is NotificationChannel -> prefixChannel(env, item)
                                is NotificationChannelGroup -> prefixGroup(env, item)
                            }
                        }
                        else -> {
                            // createNotificationChannels passes a
                            // ParceledListSlice, not a List.
                            val slice = parceledListSlice(value)
                            slice?.forEach { item ->
                                when (item) {
                                    is NotificationChannel -> prefixChannel(env, item)
                                    is NotificationChannelGroup -> prefixGroup(env, item)
                                }
                            }
                        }
                    }
                }
            }
        }
        prefixTagArgument(methodName, copy, env)
        return copy
    }

    fun rewriteResult(methodName: String, result: Any?, env: Env): Any? {
        if (result == null) return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return result
        parceledListSlice(result)?.let { items ->
            val mapped = items.map { rewriteResult(methodName, it, env) }
            return newParceledListSlice(mapped) ?: result
        }
        return when (result) {
            is NotificationChannel -> stripChannel(env, result)
            is NotificationChannelGroup -> stripGroup(env, result)
            is List<*> -> {
                val mapped = result.map { rewriteResult(methodName, it, env) }
                if (result is java.util.ArrayList<*>) ArrayList(mapped) else mapped
            }
            is Array<*> -> {
                val mapped = result.map { rewriteResult(methodName, it, env) }
                java.lang.reflect.Array.newInstance(
                    result.javaClass.componentType ?: Any::class.java,
                    mapped.size
                ).also { array ->
                    mapped.forEachIndexed { index, item ->
                        java.lang.reflect.Array.set(array, index, item)
                    }
                }
            }
            else -> result
        }
    }

    private fun isChannelMethod(name: String): Boolean =
        name.contains("NotificationChannel", ignoreCase = true) ||
            name.contains("NotificationChannels", ignoreCase = true)

    private fun isGroupMethod(name: String): Boolean =
        name.contains("NotificationChannelGroup", ignoreCase = true) ||
            name.contains("NotificationChannelGroups", ignoreCase = true)

    private fun prefixTagArgument(
        methodName: String,
        args: Array<Any?>,
        env: Env
    ) {
        val tagIndex = when {
            methodName.startsWith("enqueueNotificationWithTag") && args.size > 2 -> 2
            methodName.startsWith("cancelNotificationWithTag") && args.size > 2 -> 2
            methodName.startsWith("cancelAsPackage") && args.size > 1 -> 1
            else -> -1
        }
        if (tagIndex in args.indices) {
            val tag = args[tagIndex] as? String
            if (tag != null) args[tagIndex] = prefixTag(env, tag)
        }
    }

    private fun munge(env: Env, notification: Notification): Notification {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            setStringField(notification, listOf("mChannelId", "channelId"),
                notification.channelId?.let { prefixChannelId(env, it) })
            setStringField(notification, listOf("mGroupKey", "groupKey"),
                notification.group?.let { prefixGroupId(env, it) })
            setStringField(notification, listOf("mSortKey", "sortKey"),
                notification.sortKey?.let { prefixSortKey(env, it) })
        }

        notification.getSmallIcon()?.let { icon ->
            val bitmapIcon = renderIcon(env, icon)
            if (bitmapIcon != null) {
                if (!setField(notification, listOf("mSmallIcon", "smallIcon"), bitmapIcon)) {
                    Log.w(TAG, "Notification.mSmallIcon field not found")
                }
            }
        }

        // The ActivityManager proxy rewrites the creator package to the host,
        // so target PendingIntents report creatorPackage == hostPackage. Wrap
        // those too, otherwise tapping the notification asks system_server to
        // start an Activity from the uninstalled target package and does
        // nothing.
        notification.contentIntent = wrapPendingIntent(
            env,
            notification.contentIntent,
            allowHostCreator = true
        )
        notification.deleteIntent = wrapPendingIntent(env, notification.deleteIntent)
        notification.fullScreenIntent = wrapPendingIntent(env, notification.fullScreenIntent)
        // Action PendingIntents also point at the uninstalled target package.
        // A full action dispatcher is not virtualized yet, so route them to
        // the app launch trampoline instead of leaving dead buttons.
        notification.actions?.forEach { action ->
            action.actionIntent = wrapPendingIntent(
                env,
                action.actionIntent,
                allowHostCreator = true
            )
        }

        notification.extras = (notification.extras ?: android.os.Bundle()).apply {
            putString(EXTRA_ORIGIN_PACKAGE, env.targetPackage)
            putString(EXTRA_ORIGIN_INSTANCE, env.instanceId)
        }
        return notification
    }

    private fun wrapPendingIntent(
        env: Env,
        pendingIntent: PendingIntent?,
        allowHostCreator: Boolean = false
    ): PendingIntent? {
        val creator = runCatching { pendingIntent?.creatorPackage }.getOrNull() ?: return pendingIntent
        val owned = creator == env.targetPackage ||
            (allowHostCreator && creator == env.hostPackage)
        if (!owned) return pendingIntent
        return runCatching { env.launchPendingIntent() }.getOrNull() ?: pendingIntent
    }

    private fun renderIcon(env: Env, icon: Icon): Icon? {
        if (icon.type != Icon.TYPE_RESOURCE) return null
        val resPackage = icon.resPackage
        if (!resPackage.isNullOrEmpty() && resPackage != env.targetPackage) return null
        val drawable = env.resources?.let {
            runCatching { it.getDrawable(icon.resId, null) }.getOrNull()
        } ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
        return runCatching {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            Icon.createWithBitmap(bitmap)
        }.onFailure { Log.w(TAG, "render notification icon failed", it) }.getOrNull()
    }

    private fun prefixChannel(env: Env, channel: NotificationChannel) {
        val id = channel.id ?: return
        if (!id.startsWith(env.targetPackage)) setChannelId(channel, prefixChannelId(env, id))
        channel.group?.let {
            if (!it.startsWith(env.targetPackage)) channel.setGroup(prefixGroupId(env, it))
        }
    }

    private fun prefixGroup(env: Env, group: NotificationChannelGroup) {
        val id = group.id ?: return
        if (!id.startsWith(env.targetPackage)) setGroupId(group, prefixGroupId(env, id))
    }

    private fun stripChannel(env: Env, channel: NotificationChannel): NotificationChannel {
        setChannelId(channel, stripPrefix(env, channel.id) ?: channel.id)
        channel.group?.let { channel.setGroup(stripPrefix(env, it) ?: it) }
        return channel
    }

    private fun stripGroup(env: Env, group: NotificationChannelGroup): NotificationChannelGroup {
        setGroupId(group, stripPrefix(env, group.id) ?: group.id)
        return group
    }

    private fun setChannelId(channel: NotificationChannel, id: String): Boolean {
        val updated = setField(channel, listOf("mId", "id"), id)
        if (!updated) Log.w(TAG, "NotificationChannel.mId field not found")
        return updated
    }

    private fun setGroupId(group: NotificationChannelGroup, id: String): Boolean {
        val updated = setField(group, listOf("mId", "id"), id)
        if (!updated) Log.w(TAG, "NotificationChannelGroup.mId field not found")
        return updated
    }

    private fun parceledListSlice(value: Any): List<*>? {
        if (!value.javaClass.name.contains("ParceledListSlice")) return null
        return runCatching { value.javaClass.getMethod("getList").invoke(value) as? List<*> }
            .getOrNull()
    }

    private fun newParceledListSlice(items: List<*>): Any? = runCatching {
        val cls = Class.forName("android.content.pm.ParceledListSlice")
        cls.getConstructor(List::class.java).apply { isAccessible = true }.newInstance(items)
    }.getOrNull()

    private fun prefixTag(env: Env, tag: String): String =
        if (tag.startsWith(env.targetPackage)) tag else "${env.targetPackage}:$tag"

    private fun prefixChannelId(env: Env, id: String): String =
        if (id.startsWith(env.targetPackage)) id else "${env.targetPackage}:$id"

    private fun prefixGroupId(env: Env, id: String): String =
        if (id.startsWith(env.targetPackage)) id else "${env.targetPackage}:g:$id"

    private fun prefixSortKey(env: Env, key: String): String =
        if (key.startsWith(env.targetPackage)) key else "${env.targetPackage}:s:$key"

    private fun stripPrefix(env: Env, value: String?): String? {
        if (value == null) return null
        val packagePrefix = "${env.targetPackage}:"
        return when {
            value.startsWith("$packagePrefix" + "g:") -> value.removePrefix("$packagePrefix" + "g:")
            value.startsWith("$packagePrefix" + "s:") -> value.removePrefix("$packagePrefix" + "s:")
            value.startsWith(packagePrefix) -> value.removePrefix(packagePrefix)
            else -> value
        }
    }

    private fun setStringField(target: Any, names: List<String>, value: String?): Boolean {
        if (value == null) return false
        return setField(target, names, value)
    }

    private fun setField(target: Any, names: List<String>, value: Any?): Boolean {
        val field = findField(target.javaClass, names) ?: return false
        return runCatching {
            field.isAccessible = true
            field.set(target, value)
            true
        }.getOrDefault(false)
    }

    private fun findField(type: Class<*>, names: List<String>): Field? {
        var current: Class<*>? = type
        while (current != null) {
            names.forEach { name ->
                runCatching { current!!.getDeclaredField(name) }.getOrNull()?.let { return it }
            }
            current = current.superclass
        }
        return null
    }
}
