package com.jarvis.pineapple.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.jarvis.pineapple.R
import com.jarvis.pineapple.ui.MainActivity

/**
 * 提醒广播接收器（#54 提醒事项）。
 *
 * 接收 AlarmManager 发出的提醒广播，弹出通知提醒用户。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra("reminder_id") ?: return
        val reminderTitle = intent.getStringExtra("reminder_title") ?: "提醒"
        val reminderContent = intent.getStringExtra("reminder_content") ?: ""

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建通知渠道 (Android 8+)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "提醒通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "JARVIS 提醒事项通知"
        }
        notificationManager.createNotificationChannel(channel)

        // 点击通知打开主界面
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("reminder_id", reminderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(reminderTitle)
            .setContentText(reminderContent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(reminderId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "jarvis_reminder_channel"
        const val ACTION_REMINDER = "com.jarvis.pineapple.REMINDER"
    }
}
