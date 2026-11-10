package com.coke.nestspace.schedule

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.coke.nestspace.MainActivity
import com.coke.nestspace.R
import com.coke.nestspace.agent.AssistantConversation
import com.coke.nestspace.data.AppSettingsEvents
import com.coke.nestspace.data.SecureStore
import com.coke.nestspace.i18n.AppLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

enum class ScheduleRepeat(val api: String) {
    ONCE("once"),
    DAILY("daily"),
    WEEKLY("weekly"),
    INTERVAL("interval");

    companion object {
        fun from(value: String?): ScheduleRepeat =
            entries.firstOrNull { it.api == value?.lowercase() } ?: ONCE
    }
}

data class ScheduledTask(
    val id: String,
    val goal: String,
    val repeat: ScheduleRepeat,
    val nextAt: Long,
    val hour: Int,
    val minute: Int,
    val weekdays: List<Int>,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastRunAt: Long,
    val lastStatus: String,
    val lastAnswer: String
) {
    val recurring: Boolean get() = repeat != ScheduleRepeat.ONCE
}

/**
 * Persistent timed tasks backed by AlarmManager. A task runs the normal
 * assistant turn and stores the final answer; recurring tasks are scheduled
 * again before the run starts so a process restart does not lose the series.
 */
class ScheduledTaskManager(context: Context) {

    private val appContext = context.applicationContext
    // Shared across manager instances (receiver, UI, startup recovery) so a
    // concurrent update cannot drop another instance's task change.
    private val lock: Any get() = LOCK

    fun list(): List<ScheduledTask> = synchronized(lock) {
        load().sortedWith(compareBy<ScheduledTask> { it.nextAt == 0L }.thenBy { it.nextAt })
    }

    fun get(id: String): ScheduledTask? = synchronized(lock) {
        load().firstOrNull { it.id == id }
    }

    fun create(
        goal: String,
        repeat: ScheduleRepeat,
        nextAt: Long,
        hour: Int,
        minute: Int,
        weekdays: List<Int>,
        intervalMinutes: Int,
        enabled: Boolean = true
    ): Result<ScheduledTask> {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) return Result.failure(ScheduleException("empty_goal"))
        if (cleanGoal.length > 2_000) return Result.failure(ScheduleException("goal_too_long"))
        if (repeat == ScheduleRepeat.INTERVAL && intervalMinutes < 5) {
            return Result.failure(ScheduleException("interval_too_short"))
        }
        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            goal = cleanGoal,
            repeat = repeat,
            nextAt = nextAt,
            hour = hour,
            minute = minute,
            weekdays = weekdays.distinct().filter { it in 1..7 },
            intervalMinutes = intervalMinutes,
            enabled = enabled,
            createdAt = System.currentTimeMillis(),
            lastRunAt = 0,
            lastStatus = "",
            lastAnswer = ""
        )
        synchronized(lock) {
            val tasks = load().toMutableList()
            tasks += task
            save(tasks)
        }
        if (enabled && nextAt > 0) schedule(task)
        return Result.success(task)
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val updated = synchronized(lock) {
            val tasks = load().toMutableList()
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return false
            val task = tasks[index]
            val now = System.currentTimeMillis()
            val next = if (enabled) {
                if (task.nextAt > now) task.nextAt else nextAfter(task, now)
            } else {
                task.nextAt
            }
            // A one-shot task whose time already passed cannot be scheduled
            // again. Do not leave it enabled with nextAt=0 (the switch would
            // look on but no alarm would ever fire).
            val canEnable = !enabled || next > now
            tasks[index] = task.copy(
                enabled = enabled && canEnable,
                nextAt = next,
                lastStatus = if (enabled && !canEnable && task.lastStatus.isBlank()) {
                    "missed"
                } else {
                    task.lastStatus
                }
            )
            save(tasks)
            tasks[index]
        }
        if (enabled) {
            if (updated.nextAt > 0) schedule(updated)
        } else {
            cancel(updated.id)
        }
        return true
    }

    fun delete(id: String): Boolean {
        val removed = synchronized(lock) {
            val tasks = load().toMutableList()
            val task = tasks.firstOrNull { it.id == id } ?: return false
            tasks.removeAll { it.id == id }
            save(tasks)
            task
        }
        cancel(removed.id)
        return true
    }

    fun runNow(id: String): Boolean {
        val task = synchronized(lock) { load().firstOrNull { it.id == id } } ?: return false
        execute(task, reschedule = task.enabled)
        return true
    }

    fun onAlarm(id: String) {
        val task = synchronized(lock) {
            load().firstOrNull { it.id == id && it.enabled } ?: return
        }
        execute(task, reschedule = task.recurring)
    }

    /**
     * A schedule whose [lastStatus] is still "running" belongs to a process
     * that is gone. [startedBefore] is the caller's process start time, so
     * asynchronous recovery cannot mark a run started by the current process.
     */
    fun recoverInterrupted(startedBefore: Long = Long.MAX_VALUE): Int = synchronized(lock) {
        val tasks = load().toMutableList()
        var changed = 0
        for (index in tasks.indices) {
            if (tasks[index].lastStatus != "running") continue
            if (tasks[index].lastRunAt >= startedBefore) continue
            tasks[index] = tasks[index].copy(
                lastStatus = "interrupted",
                lastAnswer = AppLanguage.tr(
                    "上次执行被系统中断。",
                    "The previous run was interrupted."
                )
            )
            changed++
        }
        if (changed > 0) save(tasks)
        changed
    }

    fun rescheduleAll() {
        val now = System.currentTimeMillis()
        val tasks = synchronized(lock) {
            val loaded = load().toMutableList()
            var changed = false
            for (index in loaded.indices) {
                val task = loaded[index]
                if (!task.enabled) continue
                if (task.nextAt <= now) {
                    // A cold-start broadcast can arrive after Application
                    // startup. Keep a just-due one-shot runnable; only mark
                    // truly old misses.
                    if (!task.recurring && now - task.nextAt <= DUE_GRACE_MS) {
                        loaded[index] = task.copy(nextAt = now + 1_000)
                    } else {
                        val next = nextAfter(task, now)
                        loaded[index] = task.copy(
                            nextAt = next,
                            enabled = task.recurring || next > now,
                            lastStatus = if (next <= now) "missed" else task.lastStatus
                        )
                    }
                    changed = true
                }
            }
            if (changed) save(loaded)
            loaded
        }
        for (task in tasks) {
            if (task.enabled && task.nextAt > now) schedule(task)
        }
    }

    private fun execute(task: ScheduledTask, reschedule: Boolean) {
        val now = System.currentTimeMillis()
        val next = if (reschedule) nextAfter(task, now) else 0L
        synchronized(lock) {
            val tasks = load().toMutableList()
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                tasks[index] = tasks[index].copy(
                    nextAt = next,
                    enabled = if (task.recurring) tasks[index].enabled && next > now else false,
                    lastRunAt = now,
                    lastStatus = "running",
                    lastAnswer = ""
                )
                save(tasks)
            }
        }
        if (next > now) schedule(task.copy(nextAt = next))
        else cancel(task.id)
        AppSettingsEvents.notifyChanged()

        val accepted = AssistantConversation.submit(
            appContext,
            task.goal,
            queueIfBusy = true,
            origin = com.coke.nestspace.data.AgentMessage.Origin.SCHEDULED
        ) { outcome, answer ->
            val status = when (outcome) {
                AssistantConversation.RunOutcome.COMPLETED -> "done"
                AssistantConversation.RunOutcome.CANCELLED -> "cancelled"
                AssistantConversation.RunOutcome.FAILED -> "failed"
            }
            finish(task.id, status, answer.orEmpty())
        }
        if (!accepted) {
            finish(task.id, "failed", "assistant is busy")
        }
    }

    private fun finish(id: String, status: String, answer: String) {
        val task = synchronized(lock) {
            val tasks = load().toMutableList()
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) return
            val updated = tasks[index].copy(
                lastStatus = status,
                lastAnswer = answer.take(1_500)
            )
            tasks[index] = updated
            save(tasks)
            updated
        }
        AppSettingsEvents.notifyChanged()
        notifyResult(task)
    }

    private fun nextAfter(task: ScheduledTask, from: Long): Long = when (task.repeat) {
        ScheduleRepeat.ONCE -> 0L
        ScheduleRepeat.INTERVAL -> from + task.intervalMinutes.coerceAtLeast(5) * 60_000L
        ScheduleRepeat.DAILY -> nextDaily(task.hour, task.minute, from)
        ScheduleRepeat.WEEKLY -> nextWeekly(task.hour, task.minute, task.weekdays, from)
    }

    private fun nextDaily(hour: Int, minute: Int, from: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun nextWeekly(hour: Int, minute: Int, weekdays: List<Int>, from: Long): Long {
        val days = weekdays.filter { it in 1..7 }.ifEmpty {
            val today = Calendar.getInstance().apply { timeInMillis = from }
            listOf(((today.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1)
        }
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            if (day in days && cal.timeInMillis > from) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return nextDaily(hour, minute, from)
    }

    private fun schedule(task: ScheduledTask) {
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(task.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.nextAt,
                    pending
                )
            } else {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.nextAt,
                    pending
                )
            }
        }
    }

    private fun cancel(id: String) {
        val manager = appContext.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(alarmIntent(id))
    }

    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        id.hashCode(),
        Intent(appContext, ScheduledTaskReceiver::class.java)
            .setAction(ScheduledTaskReceiver.ACTION_RUN)
            .putExtra(ScheduledTaskReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notifyResult(task: ScheduledTask) {
        val context = appContext
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    AppLanguage.tr("定时任务", "Scheduled tasks"),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val title = if (task.lastStatus == "done") {
            AppLanguage.tr("定时任务完成", "Scheduled task finished")
        } else {
            AppLanguage.tr("定时任务需要查看", "Scheduled task needs attention")
        }
        val text = task.lastAnswer.ifBlank { task.goal }
        val content = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text.take(160))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(task.id.hashCode(), notification)
    }

    private fun load(): List<ScheduledTask> {
        val raw = SecureStore.read(appContext, FILE) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                ScheduledTask(
                    id = json.optString("id"),
                    goal = json.optString("goal"),
                    repeat = ScheduleRepeat.from(json.optString("repeat")),
                    nextAt = json.optLong("next_at"),
                    hour = json.optInt("hour", 9),
                    minute = json.optInt("minute", 0),
                    weekdays = json.optJSONArray("weekdays")?.let { days ->
                        (0 until days.length()).map { days.optInt(it) }
                    }.orEmpty(),
                    intervalMinutes = json.optInt("interval_minutes", 60),
                    enabled = json.optBoolean("enabled", true),
                    createdAt = json.optLong("created_at"),
                    lastRunAt = json.optLong("last_run_at"),
                    lastStatus = json.optString("last_status"),
                    lastAnswer = json.optString("last_answer")
                )
            }.filter { it.id.isNotBlank() && it.goal.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun save(tasks: List<ScheduledTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("goal", task.goal)
                    .put("repeat", task.repeat.api)
                    .put("next_at", task.nextAt)
                    .put("hour", task.hour)
                    .put("minute", task.minute)
                    .put("weekdays", JSONArray(task.weekdays))
                    .put("interval_minutes", task.intervalMinutes)
                    .put("enabled", task.enabled)
                    .put("created_at", task.createdAt)
                    .put("last_run_at", task.lastRunAt)
                    .put("last_status", task.lastStatus)
                    .put("last_answer", task.lastAnswer)
            )
        }
        SecureStore.write(appContext, FILE, array.toString())
    }

    companion object {
        private val LOCK = Any()
        private const val FILE = "schedules.v1.enc"
        private const val CHANNEL_ID = "scheduled_tasks"
        private const val DUE_GRACE_MS = 2 * 60_000L

        fun rescheduleAll(context: Context) {
            ScheduledTaskManager(context.applicationContext).rescheduleAll()
        }

        fun recoverInterrupted(
            context: Context,
            startedBefore: Long = Long.MAX_VALUE
        ): Int = ScheduledTaskManager(context.applicationContext)
            .recoverInterrupted(startedBefore)
    }
}

class ScheduleException(val code: String) : Exception(code)
