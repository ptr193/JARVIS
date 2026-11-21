package com.jarvis.pineapple.system

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 日程管理：增删改查 + 提醒
 * 所有敏感操作前检查运行时权限
 */
class CalendarController(private val context: Context) {

    data class Event(
        val title: String,
        val startTime: Long,
        val endTime: Long,
        val description: String = ""
    )

    fun createEvent(event: Event): Boolean {
        if (!PermissionGate.calendarWriteAllowed(hasWriteCalendarPermission()) ||
            !hasReadCalendarPermission() || event.title.isBlank() || event.endTime <= event.startTime
        ) return false
        val calendarId = getFirstCalendarId() ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, event.startTime)
            put(CalendarContract.Events.DTEND, event.endTime)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
        }.getOrDefault(false)
    }

    fun setReminder(eventId: Long, minutesBefore: Int = 15): Boolean {
        if (!hasWriteCalendarPermission()) return false
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        return context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values) != null
    }

    /** 成员召集：群发短信（需短信权限） */
    fun notifyMembers(phoneNumbers: List<String>, message: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (phoneNumbers.isEmpty() || message.isBlank()) return false
        val sender = SmsController(context)
        return phoneNumbers.map { sender.send(it, message) }.all { it }
    }

    // ==================== 权限检查 ====================

    fun hasReadCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWriteCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ==================== 内部方法 ====================

    /** 获取设备上真实存在的日历；无日历或查询失败时不猜测 ID。 */
    private fun getFirstCalendarId(): Long? = runCatching {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0 } else null
        }
    }.getOrNull()
}
