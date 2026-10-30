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
        if (!hasWriteCalendarPermission()) return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, event.startTime)
            put(CalendarContract.Events.DTEND, event.endTime)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.CALENDAR_ID, getFirstCalendarId())
            put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
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
        phoneNumbers.forEach { SmsController(context).send(it, message) }
        return true
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

    /** 获取第一个有效的日历 ID（避免硬编码 1） */
    private fun getFirstCalendarId(): Long {
        if (!hasReadCalendarPermission()) return 1L
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            null, null, null
        )
        val id = cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else 1L
        } ?: 1L
        return if (id > 0) id else 1L
    }
}
