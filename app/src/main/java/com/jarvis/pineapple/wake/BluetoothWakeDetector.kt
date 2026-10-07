package com.jarvis.pineapple.wake

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager

/**
 * 蓝牙按键唤醒：BluetoothHeadset 监听 + MediaSession 按键事件
 */
class BluetoothWakeDetector(private val context: Context) {

    private var onWake: (() -> Unit)? = null
    private var receiver: android.content.BroadcastReceiver? = null

    fun start(callback: () -> Unit) {
        onWake = callback
        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
                    "android.intent.action.MEDIA_BUTTON" -> {
                        onWake?.invoke()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(Intent.ACTION_MEDIA_BUTTON)
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
