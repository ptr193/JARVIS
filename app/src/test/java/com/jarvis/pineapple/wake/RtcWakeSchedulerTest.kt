package com.jarvis.pineapple.wake

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 闹钟式定时唤醒的时间计算（纯函数）测试。
 * 不接触 AlarmManager，只验证下一次触发时刻。
 */
class RtcWakeSchedulerTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun schedulesLaterTodayWhenTimeHasNotPassed() {
        val now = millis(2026, 11, 22, 10, 30)

        val trigger = RtcWakeScheduler.nextTrigger(11, 0, now, zone)

        assertEquals(millis(2026, 11, 22, 11, 0), trigger)
    }

    @Test
    fun rollsToTomorrowWhenTimeHasPassed() {
        val now = millis(2026, 11, 22, 10, 30)

        val trigger = RtcWakeScheduler.nextTrigger(9, 0, now, zone)

        assertEquals(millis(2026, 11, 23, 9, 0), trigger)
    }

    @Test
    fun rollsToTomorrowWhenCurrentMinuteAlreadyReached() {
        val now = millis(2026, 11, 22, 10, 30)

        val trigger = RtcWakeScheduler.nextTrigger(10, 30, now, zone)

        assertEquals(millis(2026, 11, 23, 10, 30), trigger)
    }

    @Test
    fun clampsOutOfRangeValues() {
        val now = millis(2026, 11, 22, 10, 30)

        assertEquals(millis(2026, 11, 22, 23, 59), RtcWakeScheduler.nextTrigger(99, 99, now, zone))
    }
}
