package com.jarvis.pineapple.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.pineapple.R
import com.jarvis.pineapple.autonomous.AutonomousDecisionManager.ProactiveSuggestion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SuggestionCardPublisher {
    const val CHANNEL_ID = "jarvis_proactive_suggestions"
    const val CHANNEL_NAME = "主动建议"
    const val PREFS = "jarvis_suggestion_cards"
    const val KEY_LAST = "last_fingerprint"

    fun dayStamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))

    fun fingerprint(ids: List<String>, day: String): String =
        "$day:${ids.joinToString("|")}"

    fun shouldPublish(previousKey: String?, newKey: String, count: Int): Boolean =
        count > 0 && previousKey != newKey

    fun publish(context: Context, suggestions: List<ProactiveSuggestion>): Int {
        if (suggestions.isEmpty()) return 0
        if (!canNotify(context)) return 0
        val newKey = fingerprint(suggestions.map { it.id }, dayStamp())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_LAST, null)
        if (!shouldPublish(previous, newKey, suggestions.size)) return 0
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return 0
        suggestions.forEachIndexed { index, item ->
            manager.notify(NOTIFICATION_BASE + index, buildNotification(context, item, index))
        }
        prefs.edit().putString(KEY_LAST, newKey).apply()
        return suggestions.size
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "JARVIS 主动建议卡片" }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context, item: ProactiveSuggestion, index: Int) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_jarvis)
            .setContentTitle(item.title)
            .setContentText(item.description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.description))
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_camera,
                "识物",
                cameraPendingIntent(context, OverlayCameraController.TASK_OBJECT, 40 + index)
            )
            .addAction(
                android.R.drawable.ic_menu_gallery,
                "扫文档",
                cameraPendingIntent(context, OverlayCameraController.TASK_DOCUMENT, 60 + index)
            )
            .build()

    private fun cameraPendingIntent(context: Context, task: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, OverlayCameraActivity::class.java)
            .putExtra(OverlayCameraController.EXTRA_TASK, task)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val NOTIFICATION_BASE = 4100
}
