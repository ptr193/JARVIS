package com.jarvis.pineapple.system

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import java.util.Calendar

/**
 * 日程管理：增删改查 + 提醒
 */
class CalendarController(private val context: Context) {

    data class Event(val title: String, val startTime: Long, val endTime: Long, val description: String = "")

    fun createEvent(event: Event) {
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Events.DTSTART, event.startTime)
            put(CalendarContract.Events.DTEND, event.endTime)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.CALENDAR_ID, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
        }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    fun setReminder(eventId: Long, minutesBefore: Int = 15) {
        val values = android.content.ContentValues().apply {
            put(CalendarContract.Reminders.MINUTES, minutesBefore)
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
    }

    /** 成员召集：群发短信 */
    fun notifyMembers(phoneNumbers: List<String>, message: String) {
        phoneNumbers.forEach { SmsController(context).send(it, message) }
    }
}
