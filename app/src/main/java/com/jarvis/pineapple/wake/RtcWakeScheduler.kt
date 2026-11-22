package com.jarvis.pineapple.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.TimeZone

/**
 * 闹钟式定时唤醒调度器。
 *
 * 使用 [AlarmManager.setAlarmClock]，与系统闹钟同级别：设备处于待机 / Doze / 深度休眠时，
 * 系统会在设定时刻唤醒设备并拉起 JARVIS（用户可见的闹钟语义）。
 *
 * **平台边界**：设备完全关机后，Android 会清除所有第三方应用的 AlarmManager 闹钟，
 * 第三方应用也无法写入 RTC 开机闹钟；因此“物理关机状态下定时开机唤醒”无法由普通应用实现，
 * 只有系统时钟/闹钟应用或具有 RTC 权限的预装应用可以做到。这里实现的是可达上限。
 */
class RtcWakeScheduler(private val context: Context) {

    companion object {
        const val EXTRA_FROM_ALARM = "extra_from_rtc_wake"
        const val ACTION_RTC_WAKE = "com.jarvis.pineapple.action.RTC_WAKE"
        private const val REQUEST_CODE = 7101
        private const val PREFS = "jarvis_rtc_wake"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"

        /**
         * 计算下一次触发时间（纯函数，可单测）。
         * 若当天时间已过，则顺延到次日。
         */
        internal fun nextTrigger(
            hour: Int,
            minute: Int,
            now: Long = System.currentTimeMillis(),
            timeZone: TimeZone = TimeZone.getDefault()
        ): Long {
            val target = Calendar.getInstance(timeZone).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.timeInMillis <= now) target.add(Calendar.DAY_OF_YEAR, 1)
            return target.timeInMillis
        }
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否允许精确闹钟（Android 12+ 需要用户在系统设置中授予）。 */
    fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun savedHour(): Int = prefs.getInt(KEY_HOUR, 7)

    fun savedMinute(): Int = prefs.getInt(KEY_MINUTE, 0)

    /**
     * 设置每天固定时间唤醒。
     * @return true 表示闹钟已成功登记；false 时 [lastError] 给出原因。
     */
    fun scheduleDaily(hour: Int, minute: Int): Boolean {
        lastError = null
        if (!canScheduleExact()) {
            lastError = "系统未允许精确闹钟，请在设置中开启“闹钟和提醒”权限"
            return false
        }
        return try {
            val triggerAt = nextTrigger(hour, minute)
            val operation = alarmPendingIntent()
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, operation),
                operation
            )
            prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .apply()
            true
        } catch (e: SecurityException) {
            lastError = "登记精确闹钟被系统拒绝：${e.message ?: "权限不足"}"
            false
        } catch (e: Exception) {
            lastError = "登记定时唤醒失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** 关闭定时唤醒并清除已登记闹钟。 */
    fun cancel() {
        alarmManager.cancel(alarmPendingIntent())
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
    }

    /** 开机或已唤醒后重新登记（AlarmClock 为一次性，需要每天续订）。 */
    fun rescheduleIfEnabled(): Boolean {
        if (!isEnabled()) return false
        return scheduleDaily(savedHour(), savedMinute())
    }

    /** 最近一次失败原因；成功时为 null。 */
    var lastError: String? = null
        private set

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(context, com.jarvis.pineapple.ui.MainActivity::class.java).apply {
            action = ACTION_RTC_WAKE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_FROM_ALARM, true)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
