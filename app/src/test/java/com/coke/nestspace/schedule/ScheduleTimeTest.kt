package com.coke.nestspace.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduleTimeTest {

    private val now = 1_800_000_000_000L // fixed instant; assertions compare relative fields only

    @Test
    fun dailyClockUsesLocalHourAndMinute() {
        val parsed = ScheduleTime.parse("09:30", ScheduleRepeat.DAILY, "", 0, now)
        assertNotNull(parsed)
        assertTrue(parsed!!.nextAt > now)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed.nextAt }
        assertEquals(9, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, cal.get(Calendar.MINUTE))
    }

    @Test
    fun onceAcceptsFutureEpochAndRejectsPast() {
        val future = now + 3_600_000
        val parsed = ScheduleTime.parse(future.toString(), ScheduleRepeat.ONCE, "", 0, now)
        assertEquals(future, parsed?.nextAt)
        assertNull(
            ScheduleTime.parse(
                (now - 3_600_000).toString(),
                ScheduleRepeat.ONCE,
                "",
                0,
                now
            )
        )
    }

    @Test
    fun weeklyLandsOnOneOfTheSelectedDays() {
        val parsed = ScheduleTime.parse("08:00", ScheduleRepeat.WEEKLY, "1,3,5", 0, now)
        assertNotNull(parsed)
        val cal = Calendar.getInstance().apply { timeInMillis = parsed!!.nextAt }
        val day = ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
        assertTrue(day in listOf(1, 3, 5))
        assertTrue(parsed!!.nextAt > now)
    }

    @Test
    fun intervalIsAtLeastFiveMinutes() {
        val parsed = ScheduleTime.parse("", ScheduleRepeat.INTERVAL, "", 1, now)
        assertEquals(now + 5 * 60_000L, parsed?.nextAt)
    }

    @Test
    fun invalidTimeIsRejected() {
        assertNull(ScheduleTime.parse("25:00", ScheduleRepeat.DAILY, "", 0, now))
        assertNull(ScheduleTime.parse("not-a-time", ScheduleRepeat.ONCE, "", 0, now))
    }
}
