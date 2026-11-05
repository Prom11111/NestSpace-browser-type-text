package com.coke.nestspace.schedule

import java.util.Calendar

data class ParsedSchedule(
    val repeat: ScheduleRepeat,
    val nextAt: Long,
    val hour: Int,
    val minute: Int,
    val weekdays: List<Int>
)

/** Parses user-facing time input into the fields stored by [ScheduledTaskManager]. */
object ScheduleTime {

    private val CLOCK = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    /**
     * @param at `HH:mm` or epoch milliseconds.
     * @param weekdays comma-separated 1=Mon..7=Sun.
     */
    fun parse(
        at: String,
        repeat: ScheduleRepeat,
        weekdaysRaw: String,
        intervalMinutes: Int,
        now: Long = System.currentTimeMillis()
    ): ParsedSchedule? {
        if (repeat == ScheduleRepeat.INTERVAL) {
            val interval = intervalMinutes.coerceAtLeast(5)
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            return ParsedSchedule(
                repeat = repeat,
                nextAt = now + interval * 60_000L,
                hour = cal.get(Calendar.HOUR_OF_DAY),
                minute = cal.get(Calendar.MINUTE),
                weekdays = emptyList()
            )
        }

        val clock = CLOCK.matchEntire(at.trim())
        val epoch = at.trim().toLongOrNull()
        val hour: Int
        val minute: Int
        val onceAt: Long?
        if (clock != null) {
            hour = clock.groupValues[1].toInt()
            minute = clock.groupValues[2].toInt()
            onceAt = nextDaily(hour, minute, now)
        } else if (epoch != null) {
            if (epoch <= 0) return null
            val cal = Calendar.getInstance().apply { timeInMillis = epoch }
            hour = cal.get(Calendar.HOUR_OF_DAY)
            minute = cal.get(Calendar.MINUTE)
            onceAt = epoch
        } else {
            return null
        }

        val weekdays = weekdaysRaw
            .split(',', '，', ' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .distinct()
        return when (repeat) {
            ScheduleRepeat.ONCE -> {
                val next = onceAt ?: return null
                if (next <= now) return null
                ParsedSchedule(repeat, next, hour, minute, emptyList())
            }
            ScheduleRepeat.DAILY -> ParsedSchedule(
                repeat,
                nextDaily(hour, minute, now),
                hour,
                minute,
                emptyList()
            )
            ScheduleRepeat.WEEKLY -> {
                val days = weekdays.ifEmpty {
                    val cal = Calendar.getInstance().apply { timeInMillis = now }
                    listOf(((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1)
                }
                ParsedSchedule(
                    repeat,
                    nextWeekly(hour, minute, days, now),
                    hour,
                    minute,
                    days
                )
            }
            ScheduleRepeat.INTERVAL -> null
        }
    }

    private fun nextDaily(hour: Int, minute: Int, from: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun nextWeekly(hour: Int, minute: Int, weekdays: List<Int>, from: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
            if (day in weekdays && cal.timeInMillis > from) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return nextDaily(hour, minute, from)
    }
}
