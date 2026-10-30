package com.jarvis.pineapple.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 通知栏唤醒：通过常驻通知的按钮/点击唤醒。
 */
class NotificationWakeDetector(private val context: Context) {

    private val _detected = MutableStateFlow(false)
    val detected: StateFlow<Boolean> = _detected.asStateFlow()

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "jarvis_wake_channel"
    private val notificationId = 1001

    companion object {
        /** 唤醒按钮使用的 Intent action */
        const val ACTION_WAKE = "com.jarvis.pineapple.WAKE_ACTION"
    }

    fun start() {
        createChannel()
        showWakeNotification()
    }

    fun stop() {
        notificationManager.cancel(notificationId)
        _detected.value = false
    }

    /** 由 Notification 的 Action/ContentIntent 回调 */
    fun onWakeAction() {
        _detected.value = true
    }

    /** 消费本次唤醒信号，返回是否消费成功 */
    fun consume(): Boolean {
        if (_detected.value) {
            _detected.value = false
            return true
        }
        return false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "JARVIS 唤醒",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "JARVIS 语音助手唤醒通道"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showWakeNotification() {
        val wakeIntent = Intent(context, com.jarvis.pineapple.ui.MainActivity::class.java).apply {
            action = "com.jarvis.pineapple.WAKE"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 唤醒按钮：直接以 WAKE_ACTION 启动 MainActivity（无需独立 Receiver，避免未注册崩溃）
        val actionIntent = Intent(context, com.jarvis.pineapple.ui.MainActivity::class.java).apply {
            action = ACTION_WAKE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val actionPendingIntent = PendingIntent.getActivity(
            context, 1, actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(context, channelId)
            .setContentTitle("JARVIS 待命中")
            .setContentText("点击唤醒 / 长按电源键")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                "唤醒",
                actionPendingIntent
            )
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
